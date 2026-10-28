package io.cleanyourtv.ambilight;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.audiofx.Visualizer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.nio.ByteBuffer;

/**
 * Mirrors the screen into a 64x36 image (the GPU does the scaling) and listens to the TV's sound; about 20 times
 * a second {@link Director} picks the colour from the two and it goes to the strip. Stops on its own when the
 * projection ends; goes dark while the screen is off.
 */
public class AmbiService extends Service {

    static final String EXTRA_RESULT = "result", EXTRA_DATA = "data";
    static final String PREFS = "ambilight", PREF_ENABLED = "enabled", PREF_BRIGHTNESS = "brightness",
            PREF_SATURATION = "saturation", PREF_LED = "led";

    /** Calibration: hold this colour on the strip, uncorrected, until {@link #testUntil} (uptime ms). */
    static volatile int testColor = -1;
    static volatile long testUntil;
    /** Calibration: send the test colour through the LED correction (to check a correction against a photo). */
    static volatile boolean testCorrected;
    private static final String TAG = "Ambilight", CHANNEL = "ambilight";
    private static final int WIDTH = 64, HEIGHT = 36, FRAME_MS = 50;
    private static final long LOG_EVERY_MS = 10_000;

    static volatile boolean running;

    private HandlerThread thread;
    private Handler handler;
    private Strip strip;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private final AmbiColor smoother = AmbiColor.smoother();
    private final Director director = new Director();
    private final Pulse pulse = new Pulse();
    private Visualizer visualizer;
    private volatile int screenColor, cameraColor, takerColor;
    private CameraSource camera;
    private ColorTakerSource taker;
    /** When the captured picture last changed; a protected video freezes rather than going black. */
    private volatile long lastChange;
    private long signature, soundCheck;
    private boolean soundOn;
    private android.media.AudioManager audio;
    private static final long FROZEN_MS = 2000, SOUND_CHECK_MS = 500;
    private int sent = -1;
    private long lastFrame, lastLog;
    private boolean screenOn = true;
    private byte[] row;
    private SharedPreferences prefs;
    private LedCorrection led = LedCorrection.parse(null);

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            screen(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
        }
    };

    /**
     * Standby switches the strip off and lets go of it; waking connects again at once. The TV may cut the USB
     * power that feeds the strip while it sleeps, so the old link can't be trusted after a wake.
     */
    private void screen(boolean on) {
        handler.post(() -> {
            if (on == screenOn) return;
            Log.i(TAG, on ? "screen on" : "screen off");
            screenOn = on;
            sent = -1;
            taker.pause(!on);
            if (on) strip.wake();
            else strip.sleep(Leddmx.power(false));
            handler.removeCallbacks(tick);
            if (on) handler.post(tick);
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        thread = new HandlerThread("ambilight");
        thread.start();
        handler = new Handler(thread.getLooper());
        strip = new Strip(this, handler, prefs);
        audio = getSystemService(android.media.AudioManager.class);
        taker = new ColorTakerSource(cells -> takerColor = AmbiColor.averageColor(Dominant.pick(cells), Dominant.BLACK));
        camera = new CameraSource(this, handler, prefs,
                (rgba, w, h, stride) -> cameraColor = AmbiColor.frameColor(rgba, w, h, stride));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent data = intent == null ? null : intent.getParcelableExtra(EXTRA_DATA);
        // Android 10+ only hands out a projection to a foreground service that declares the mediaProjection type;
        // Android 11+ also wants the camera type for a webcam used from the service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (data != null) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            startForeground(1, notification(), types);
        } else {
            startForeground(1, notification());
        }
        // Sticky: if Android kills the process for memory, it starts the service again (with no intent).
        if (running) return START_STICKY;
        // The TV's own colour taking needs no screen capture. Screen capture is only started where it's missing:
        // its virtual display, appearing while a protected film plays, makes Prime Video stop with an HDCP
        // ("HDMI connection") error, and on TVs with a hardware video plane it can't see films anyway.
        boolean tvColors = taker.start();
        if (!tvColors && data != null) {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED), data);
        }
        if (!tvColors && projection == null) {
            Log.w(TAG, "no colour taking and no screen capture permission");
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (projection != null) {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "projection stopped");
                    stopSelf();
                }
            }, handler);
            reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::onImage, handler);
            display = projection.createVirtualDisplay("ambilight", WIDTH, HEIGHT, 160,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, filter);
        strip.open();
        camera.start();
        handler.post(this::listen);
        handler.post(tick);
        // Restarted by Android, or by an update, while the TV is in standby: stay dark until it wakes.
        if (!getSystemService(android.os.PowerManager.class).isInteractive()) screen(false);
        Log.i(TAG, tvColors ? "started with the TV's colour taking" : "started with screen capture");
        return START_STICKY;
    }

    private void onImage(ImageReader r) {
        Image image = r.acquireLatestImage();
        if (image == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            if (!screenOn || now - lastFrame < FRAME_MS) return;
            lastFrame = now;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int stride = plane.getRowStride();
            int size = stride * (HEIGHT - 1) + WIDTH * 4;
            if (row == null || row.length < size) row = new byte[size];
            buffer.rewind();
            buffer.get(row, 0, Math.min(size, buffer.remaining()));
            screenColor = AmbiColor.frameColor(row, WIDTH, HEIGHT, stride);
            long sig = AmbiColor.signature(row, WIDTH, HEIGHT, stride);
            if (sig != signature) {
                signature = sig;
                lastChange = now;
            }
        } finally {
            image.close();
        }
    }

    /** The screen only produces frames when it changes, so the strip runs on its own clock. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!screenOn) return;
            long now = SystemClock.uptimeMillis();
            if (now - soundCheck > SOUND_CHECK_MS) {
                soundCheck = now;
                AmbiColor.saturation = prefs.getFloat(PREF_SATURATION, (float) AmbiColor.SATURATION);
                led = LedCorrection.parse(prefs.getString(PREF_LED, null));
                soundOn = audio.isMusicActive();
            }
            // The TV's own colour taking sees the real output, video and protected apps included; a webcam on the
            // screen sees it too; screen capture (menus only on TVs with a hardware video plane) is the fallback.
            boolean tv = taker.live(), cam = !tv && camera.live();
            boolean frozen = !tv && !cam && soundOn && now - lastChange > FROZEN_MS;
            int picture = tv ? takerColor : cam ? cameraColor : frozen ? 0 : screenColor;
            String source = tv ? "tv" : cam ? "camera" : "screen";
            int target = director.next(now, picture, visualizer == null ? null : pulse, soundOn, tv || cam);
            int shown = AmbiColor.dim(smoother.smooth(target, director.alpha()), prefs.getInt(PREF_BRIGHTNESS, 100));
            // What the screen shows, then what the LEDs need to be sent to look like it.
            boolean testing = testColor >= 0 && now < testUntil;
            int color = testing ? (testCorrected ? led.apply(testColor) : testColor) : led.apply(shown);
            if (sent == -1 || AmbiColor.differs(color, sent, 2)) {
                sent = color;
                strip.send(Leddmx.color(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF));
            }
            if (now - lastLog > LOG_EVERY_MS) {
                lastLog = now;
                Log.i(TAG, String.format("%s from %s colour #%06x level %.2f tone %.2f sound %b frozen %b strip %s",
                        director.mode(), source, color, pulse.level(), pulse.tone(), soundOn, frozen,
                        strip.connected() ? "connected" : "waiting"));
            }
            handler.postDelayed(this, FRAME_MS);
        }
    };

    /**
     * The TV's sound, from the output mix (audio session 0). Needs RECORD_AUDIO, granted over ADB; without it
     * the strip still follows the screen and just fades on black instead of following the music.
     */
    private void listen() {
        try {
            Visualizer v = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.max(range[0], Math.min(range[1], 1024)));
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer vis, byte[] waveform, int samplingRate) {
                }

                @Override
                public void onFftDataCapture(Visualizer vis, byte[] fft, int samplingRate) {
                    // samplingRate is in milliHertz.
                    pulse.update(fft, samplingRate / 1000, SystemClock.uptimeMillis());
                }
            }, Math.min(Visualizer.getMaxCaptureRate(), 20_000), false, true);
            v.setEnabled(true);
            visualizer = v;
            Log.i(TAG, "listening to the TV's sound");
        } catch (RuntimeException e) {
            Log.w(TAG, "no sound: " + e);
        }
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.app_name),
                NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.running))
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            unregisterReceiver(screen);
        } catch (IllegalArgumentException ignored) {
            // Never registered: the capture permission was missing.
        }
        handler.removeCallbacks(tick);
        taker.stop();
        camera.stop();
        if (visualizer != null) visualizer.release();
        if (display != null) display.release();
        if (projection != null) projection.stop();
        strip.close(Leddmx.power(false));
        handler.postDelayed(() -> {
            if (reader != null) reader.close();
            thread.quitSafely();
        }, 400);
        Log.i(TAG, "stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

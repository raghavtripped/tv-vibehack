package io.cleanyourtv.ambilight;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import java.util.Collections;

/**
 * The picture from a USB webcam that looks at the screen, for TVs whose video never reaches screen capture: many
 * play video on a hardware plane, and protected apps are blanked anyway. Plug the camera in and point it at the
 * screen; the part of its view that holds the screen is set by the {@code crop} extra (left, top, right, bottom
 * as fractions, default 0.1,0.1,0.9,0.9). Each camera frame is cut to that part and shrunk to the same 64x36 RGBA
 * picture screen capture gives, so the rest of the add-on can't tell the two apart.
 *
 * <p>Opens the first external camera when one is (or becomes) available, and lets go of it when it's unplugged.
 */
@SuppressLint("MissingPermission") // checked in open()
final class CameraSource {

    interface Sink {
        void frame(byte[] rgba, int width, int height, int stride);
    }

    static final String PREF_CROP = "camera_crop";
    private static final String TAG = "Ambilight";
    static final int WIDTH = 64, HEIGHT = 36;
    private static final long LIVE_MS = 1000;

    private final Context context;
    private final Handler handler;
    private final SharedPreferences prefs;
    private final Sink sink;
    private final CameraManager manager;
    private final byte[] rgba = new byte[WIDTH * HEIGHT * 4];

    private CameraDevice device;
    private ImageReader reader;
    private boolean opening, closed, warned;
    private volatile long lastFrame;

    CameraSource(Context context, Handler handler, SharedPreferences prefs, Sink sink) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.prefs = prefs;
        this.sink = sink;
        manager = context.getSystemService(CameraManager.class);
    }

    void start() {
        if (manager == null) return;
        manager.registerAvailabilityCallback(availability, handler);
        handler.post(this::open);
    }

    void stop() {
        closed = true;
        if (manager != null) manager.unregisterAvailabilityCallback(availability);
        handler.post(this::release);
    }

    /** A camera frame arrived within the last second. */
    boolean live() {
        return SystemClock.uptimeMillis() - lastFrame < LIVE_MS;
    }

    private final CameraManager.AvailabilityCallback availability = new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraAvailable(String id) {
            if (device == null) open();
        }
    };

    private void open() {
        if (closed || device != null || opening) return;
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!warned) Log.i(TAG, "no CAMERA permission: camera mode off");
            warned = true;
            return;
        }
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_EXTERNAL) continue;
                StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                Size size = smallest(map.getOutputSizes(ImageFormat.YUV_420_888));
                if (size == null) continue;
                Log.i(TAG, "camera " + id + " at " + size);
                reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
                reader.setOnImageAvailableListener(this::onImage, handler);
                opening = true;
                manager.openCamera(id, state, handler);
                return;
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "camera: " + e);
            opening = false;
        }
    }

    /** The smallest size at least 160 wide: plenty for one colour, and cheap to read. */
    static Size smallest(Size[] sizes) {
        Size best = null;
        if (sizes == null) return null;
        for (Size s : sizes) {
            if (s.getWidth() < 160) continue;
            if (best == null || (long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    private final CameraDevice.StateCallback state = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            opening = false;
            if (closed) {
                camera.close();
                return;
            }
            device = camera;
            try {
                camera.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        try {
                            CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            b.addTarget(reader.getSurface());
                            session.setRepeatingRequest(b.build(), null, handler);
                        } catch (CameraAccessException | IllegalStateException e) {
                            Log.w(TAG, "camera request: " + e);
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        Log.w(TAG, "camera session failed");
                        release();
                    }
                }, handler);
            } catch (CameraAccessException | IllegalStateException e) {
                Log.w(TAG, "camera session: " + e);
                release();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.i(TAG, "camera unplugged");
            camera.close();
            if (camera == device) release();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.w(TAG, "camera error " + error);
            camera.close();
            opening = false;
            if (camera == device || device == null) release();
        }
    };

    private void release() {
        if (device != null) device.close();
        device = null;
        opening = false;
        if (reader != null) reader.close();
        reader = null;
    }

    private void onImage(ImageReader r) {
        Image image = r.acquireLatestImage();
        if (image == null) return;
        try {
            float[] crop = crop(prefs.getString(PREF_CROP, null));
            Image.Plane[] p = image.getPlanes();
            toRgba(image.getWidth(), image.getHeight(),
                    p[0].getBuffer(), p[0].getRowStride(), p[0].getPixelStride(),
                    p[1].getBuffer(), p[2].getBuffer(), p[1].getRowStride(), p[1].getPixelStride(), crop, rgba);
            lastFrame = SystemClock.uptimeMillis();
            sink.frame(rgba, WIDTH, HEIGHT, WIDTH * 4);
        } catch (RuntimeException e) {
            Log.w(TAG, "camera frame: " + e);
        } finally {
            image.close();
        }
    }

    /** "l,t,r,b" fractions of the camera's view; anything unreadable gives the default. */
    static float[] crop(String spec) {
        float[] d = {0.1f, 0.1f, 0.9f, 0.9f};
        if (spec == null) return d;
        String[] parts = spec.split(",");
        if (parts.length != 4) return d;
        float[] c = new float[4];
        try {
            for (int i = 0; i < 4; i++) c[i] = Math.max(0, Math.min(1, Float.parseFloat(parts[i].trim())));
        } catch (NumberFormatException e) {
            return d;
        }
        return c[2] - c[0] < 0.05f || c[3] - c[1] < 0.05f ? d : c;
    }

    /** Samples the crop of a YUV_420_888 frame into a 64x36 RGBA picture (BT.601, full range as webcams send). */
    static void toRgba(int w, int h, java.nio.ByteBuffer y, int yRow, int yPixel, java.nio.ByteBuffer u,
                       java.nio.ByteBuffer v, int uvRow, int uvPixel, float[] crop, byte[] out) {
        for (int oy = 0; oy < HEIGHT; oy++) {
            int sy = Math.min(h - 1, (int) ((crop[1] + (oy + 0.5f) / HEIGHT * (crop[3] - crop[1])) * h));
            for (int ox = 0; ox < WIDTH; ox++) {
                int sx = Math.min(w - 1, (int) ((crop[0] + (ox + 0.5f) / WIDTH * (crop[2] - crop[0])) * w));
                int yy = y.get(sy * yRow + sx * yPixel) & 0xFF;
                int uvIndex = (sy / 2) * uvRow + (sx / 2) * uvPixel;
                int uu = (u.get(uvIndex) & 0xFF) - 128, vv = (v.get(uvIndex) & 0xFF) - 128;
                int o = (oy * WIDTH + ox) * 4;
                out[o] = (byte) clamp(Math.round(yy + 1.402f * vv));
                out[o + 1] = (byte) clamp(Math.round(yy - 0.344f * uu - 0.714f * vv));
                out[o + 2] = (byte) clamp(Math.round(yy + 1.772f * uu));
                out[o + 3] = (byte) 0xFF;
            }
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}

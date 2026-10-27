package io.cleanyourtv.ambilight;

import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Method;

/**
 * The picture's colour from the TV itself, on Xiaomi MediaTek TVs that have "colour taking": the TvService system
 * service asks the picture engine for a pixel report of a screen area (summed R, G, B of the final output), so it
 * sees video, protected apps included, that screen capture never gets. Found on a MiTV-MOOR2 by reading the
 * firmware: {@code mitv.internal.ITvService} transactions 89 ({@code supportColorTaking}) and 87
 * ({@code getScreenColors(List<ArrayWrapper>)}, one {left, right, top, bottom} int[4] per area, replying an int[10]
 * per area whose first value is the average colour as 0xAARRGGBB).
 *
 * <p>What the reports mean, measured on the MOOR2 with known colours on screen: channels are 6-bit (0..63, a
 * quarter of 8-bit), and over a large area the blue sum overflows (the whole 4K panel reads blue 48 as 15), so the
 * screen is measured as a 3x2 grid of areas, each small enough to stay exact. One area takes about 60 ms of the
 * TvService's time whatever its size; the areas are measured in turn, one about every 75 ms (the whole grid about
 * twice a second), and not at all while the screen is off. The sink gets every area's latest colour each time.
 */
final class ColorTakerSource {

    interface Sink {
        /** Each area's latest colour, 0xRRGGBB on the 8-bit scale. */
        void cells(int[] rgb);
    }

    static final int COLUMNS = 3, ROWS = 2;

    private static final String TAG = "Ambilight";
    private static final String DESCRIPTOR = "mitv.internal.ITvService";
    private static final int GET_SCREEN_COLORS = 87, SUPPORT_COLOR_TAKING = 89;
    private static final long PERIOD_MS = 75, LIVE_MS = 1000;

    private final Sink sink;
    private volatile boolean running, paused;
    private volatile long lastColor;
    private Thread thread;

    ColorTakerSource(Sink sink) {
        this.sink = sink;
    }

    /** This TV has colour taking and lets the app call it. */
    static boolean available() {
        IBinder service = service();
        return service != null && supported(service);
    }

    /** Starts measuring if this TV has colour taking and lets the app call it; false otherwise. */
    boolean start() {
        IBinder service = service();
        if (service == null || !supported(service)) return false;
        int[] area = panelArea();
        int[][] grid = grid(area[1] + 1, area[3] + 1, COLUMNS, ROWS);
        running = true;
        thread = new Thread(() -> loop(service, grid), "colortaker");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
        Log.i(TAG, "colour taking on, area " + area[1] + "x" + area[3]);
        return true;
    }

    /** Stops asking while the screen is off. */
    void pause(boolean pause) {
        paused = pause;
        if (!pause && thread != null) thread.interrupt();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    /** A colour arrived within the last second. */
    boolean live() {
        return SystemClock.uptimeMillis() - lastColor < LIVE_MS;
    }

    /** {left, right, top, bottom} of each cell of a columns x rows grid over a width x height panel. */
    static int[][] grid(int width, int height, int columns, int rows) {
        int[][] cells = new int[columns * rows][];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                cells[r * columns + c] = new int[]{c * width / columns, (c + 1) * width / columns - 1,
                        r * height / rows, (r + 1) * height / rows - 1};
            }
        }
        return cells;
    }

    /** A report's average colour (6-bit channels at [1..3]) as 0xRRGGBB on the 8-bit scale. */
    static int toRgb(int[] report) {
        return scale(report[1]) << 16 | scale(report[2]) << 8 | scale(report[3]);
    }

    private static int scale(int sixBit) {
        return Math.max(0, Math.min(255, Math.round(sixBit * 255f / 63f)));
    }

    private void loop(IBinder service, int[][] grid) {
        int failures = 0, next = 0;
        int[] cells = new int[grid.length];
        while (running) {
            long start = SystemClock.uptimeMillis();
            if (paused) {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    if (!running) return;
                }
                continue;
            }
            try {
                int[] report = screenColor(service, grid[next]);
                if (report != null) {
                    failures = 0;
                    cells[next] = toRgb(report);
                    next = (next + 1) % grid.length;
                    lastColor = SystemClock.uptimeMillis();
                    sink.cells(cells.clone());
                } else if (++failures % 50 == 1) {
                    Log.w(TAG, "colour taking returned nothing");
                }
            } catch (Exception e) {
                if (++failures % 50 == 1) Log.w(TAG, "colour taking: " + e);
            }
            long wait = PERIOD_MS - (SystemClock.uptimeMillis() - start);
            try {
                Thread.sleep(Math.max(10, wait) * (failures > 20 ? 20 : 1));
            } catch (InterruptedException e) {
                if (!running) return;
            }
        }
    }

    /** One area's report, or null when the TV refused it. */
    static int[] screenColor(IBinder service, int[] area) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            writeAreas(data, new int[][]{area});
            service.transact(GET_SCREEN_COLORS, data, reply, 0);
            reply.readException();
            int n = reply.readInt();
            if (n < 1 || reply.readInt() == 0) return null;
            int[] report = reply.createIntArray();
            return report != null && report.length >= 4 ? report : null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Parcel.writeTypedList of mitv.graphics.ArrayWrapper: a count, then a non-null marker and the int[] each. */
    static void writeAreas(Parcel data, int[][] areas) {
        data.writeInt(areas.length);
        for (int[] a : areas) {
            data.writeInt(1);
            data.writeIntArray(a);
        }
    }

    private static boolean supported(IBinder service) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            service.transact(SUPPORT_COLOR_TAKING, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Exception e) {
            Log.i(TAG, "no colour taking: " + e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** {left, right, top, bottom} of the whole panel, from ro.boot.mi.panel_resolution (default 1920x1080). */
    static int[] panelArea() {
        int w = 1920, h = 1080;
        try {
            String res = (String) hidden("android.os.SystemProperties").getMethod("get", String.class, String.class)
                    .invoke(null, "ro.boot.mi.panel_resolution", "1920x1080");
            String[] p = res.split("x");
            w = Integer.parseInt(p[0].trim());
            h = Integer.parseInt(p[1].trim());
        } catch (Exception ignored) {
            // keep 1080p
        }
        return new int[]{0, w - 1, 0, h - 1};
    }

    static IBinder service() {
        try {
            exemptHiddenApis();
            return (IBinder) hidden("android.os.ServiceManager").getMethod("getService", String.class)
                    .invoke(null, "TvService");
        } catch (Throwable e) {
            Log.i(TAG, "no TvService: " + e);
            return null;
        }
    }

    private static Class<?> hidden(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    /**
     * ServiceManager is a hidden API. On Android 9-11 an app can lift the restriction for itself by calling
     * VMRuntime.setHiddenApiExemptions through reflection on reflection; later versions close that door, and then
     * this source just isn't used.
     */
    private static void exemptHiddenApis() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclared = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclared.invoke(vmRuntime, "getRuntime", null);
            Method exemptions = (Method) getDeclared.invoke(vmRuntime, "setHiddenApiExemptions", new Class[]{String[].class});
            exemptions.invoke(getRuntime.invoke(null), new Object[]{new String[]{"L"}});
        } catch (Throwable e) {
            Log.i(TAG, "hidden API exemption unavailable: " + e);
        }
    }
}

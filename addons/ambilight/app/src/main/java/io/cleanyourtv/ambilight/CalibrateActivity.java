package io.cleanyourtv.ambilight;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;
import java.util.Arrays;

/**
 * Shows a row of known colours full-screen and logs what the TV's colour taking reports for each, so the numbers
 * can be matched to real colours ({@code logcat -s Ambilight}). Started over ADB:
 * {@code am start -n io.cleanyourtv.ambilight/.CalibrateActivity}. About 25 seconds; Back ends it early.
 */
public class CalibrateActivity extends Activity {

    private static final String TAG = "Ambilight";
    static final int[] COLORS = {0x000000, 0x101010, 0x202020, 0x404040, 0x808080, 0xFFFFFF, 0xFF8000, 0x804000,
            0xFFE0C0, 0x3060C0};
    private static final long STEP_MS = 2600, MEASURE_AFTER_MS = 1100;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView view;
    private int step;
    private IBinder service;
    private int[] area;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new TextView(this);
        view.setGravity(Gravity.BOTTOM | Gravity.END);
        view.setPadding(0, 0, 40, 30);
        view.setTextSize(14);
        setContentView(view);
        service = ColorTakerSource.service();
        area = ColorTakerSource.panelArea();
        if (!solid(getIntent())) handler.post(this::next);
    }

    /** {@code --es color RRGGBB}: just hold that colour full-screen (for photographing the strip next to it). */
    private boolean solid(android.content.Intent intent) {
        String c = intent.getStringExtra("color");
        if (c == null) return false;
        handler.removeCallbacksAndMessages(null);
        view.setText("");
        view.setBackgroundColor(0xFF000000 | Integer.parseInt(c, 16));
        return true;
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        solid(intent);
    }

    private void next() {
        if (isFinishing()) return;
        if (step >= COLORS.length) {
            Log.i(TAG, "calibration done");
            finish();
            return;
        }
        int c = COLORS[step];
        view.setBackgroundColor(0xFF000000 | c);
        view.setTextColor(Color.luminance(0xFF000000 | c) > 0.5 ? Color.BLACK : Color.WHITE);
        view.setText("");
        handler.postDelayed(() -> measure(c), MEASURE_AFTER_MS);
        handler.postDelayed(this::next, STEP_MS);
        step++;
    }

    private void measure(int shown) {
        new Thread(() -> {
            try {
                if (service == null) return;
                // The service divides the hardware's sum by the area it was given; try larger areas than the
                // panel to see whether the hardware clamps them (and so the average stops overflowing).
                int w = area[1], h = area[3], cx = w / 2, cy = h / 2;
                int[][] tries = {{0, w, 0, h}, {cx - 200, cx + 200, cy - 200, cy + 200}, {cx - 4, cx + 4, cy - 4, cy + 4}};
                StringBuilder sb = new StringBuilder();
                for (int[] t : tries) {
                    long start = System.nanoTime();
                    int[] r = ColorTakerSource.screenColor(service, t);
                    long ms = (System.nanoTime() - start) / 1_000_000;
                    sb.append(r == null ? " null" : String.format(" [%dms avg %d,%d,%d min/max %d-%d %d-%d %d-%d]",
                            ms, r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9]));
                }
                Log.i(TAG, String.format("calibration shown #%06x full/400px/8px:%s", shown, sb));
            } catch (Exception e) {
                Log.w(TAG, "calibration: " + e);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}

package io.cleanyourtv.ambilight;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * No screen of its own: opening the app switches the ambient light on (opening it again keeps it on, so a
 * double press can't switch it off by accident); the separate "Ambient light off" entry switches it off.
 * Screen capture normally asks "Start recording?" every time; granting the app PROJECT_MEDIA once over ADB
 * (see the README) makes Android answer yes without showing it.
 *
 * <p>Extras, for ADB: {@code boot} (start only if it was on), {@code brightness} 0..100, {@code address} (the
 * strip's Bluetooth address, to skip the first scan), {@code forget} (scan for the strip again), {@code crop}
 * (where the screen is in a webcam's view: "left,top,right,bottom" fractions), {@code saturation} (float 0.5..2,
 * default 1.35: how much colour boost the strip gets), {@code off} (switch it off). Settings sent while it is on
 * don't switch it off.
 */
public class AmbiActivity extends Activity {

    private static final int CAPTURE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(AmbiService.PREFS, MODE_PRIVATE);
        Intent in = getIntent();
        SharedPreferences.Editor edit = prefs.edit();
        if (in.hasExtra("brightness")) edit.putInt(AmbiService.PREF_BRIGHTNESS, in.getIntExtra("brightness", 100));
        if (in.hasExtra("address")) edit.putString(Strip.PREF_ADDRESS, in.getStringExtra("address"));
        if (in.getBooleanExtra("forget", false)) edit.remove(Strip.PREF_ADDRESS);
        if (in.hasExtra("crop")) edit.putString(CameraSource.PREF_CROP, in.getStringExtra("crop"));
        if (in.hasExtra("led")) edit.putString(AmbiService.PREF_LED, in.getStringExtra("led"));
        if (in.hasExtra("test")) {
            // Calibration: show this colour on the strip as sent, uncorrected, for test_ms (default 4 s).
            AmbiService.testColor = Integer.parseInt(in.getStringExtra("test"), 16) & 0xFFFFFF;
            AmbiService.testUntil = android.os.SystemClock.uptimeMillis() + in.getIntExtra("test_ms", 4000);
            AmbiService.testCorrected = in.getBooleanExtra("corrected", false);
        }
        if (in.hasExtra("saturation")) {
            edit.putFloat(AmbiService.PREF_SATURATION, Math.max(0.5f, Math.min(2f, in.getFloatExtra("saturation", 1.35f))));
        }
        edit.apply();

        boolean boot = in.getBooleanExtra("boot", false);
        if (boot && !prefs.getBoolean(AmbiService.PREF_ENABLED, false)) {
            finish();
            return;
        }
        boolean settingsOnly = in.hasExtra("brightness") || in.hasExtra("address") || in.hasExtra("crop")
                || in.hasExtra("forget") || in.hasExtra("saturation") || in.hasExtra("led") || in.hasExtra("test");
        if (settingsOnly && AmbiService.running) {
            // Changing a setting from ADB must not switch the light off; the service reads it on the next tick.
            finish();
            return;
        }
        boolean off = in.getBooleanExtra("off", false)
                || (in.getComponent() != null && in.getComponent().getClassName().endsWith(".OffActivity"));
        if (off) {
            prefs.edit().putBoolean(AmbiService.PREF_ENABLED, false).apply();
            stopService(new Intent(this, AmbiService.class));
            Toast.makeText(this, R.string.off, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (AmbiService.running) {
            prefs.edit().putBoolean(AmbiService.PREF_ENABLED, true).apply();
            if (!boot) Toast.makeText(this, R.string.on, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (ColorTakerSource.available()) {
            // This TV measures its own picture: no screen capture to ask for.
            start(null, RESULT_OK);
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAPTURE && resultCode == RESULT_OK && data != null) {
            start(data, resultCode);
            return;
        }
        Toast.makeText(this, R.string.denied, Toast.LENGTH_LONG).show();
        finish();
    }

    /** Starts the service, with the screen capture grant when there is one. */
    private void start(Intent capture, int resultCode) {
        getSharedPreferences(AmbiService.PREFS, MODE_PRIVATE).edit().putBoolean(AmbiService.PREF_ENABLED, true).apply();
        Intent service = new Intent(this, AmbiService.class);
        if (capture != null) {
            service.putExtra(AmbiService.EXTRA_RESULT, resultCode).putExtra(AmbiService.EXTRA_DATA, capture);
        }
        startForegroundService(service);
        Toast.makeText(this, R.string.on, Toast.LENGTH_SHORT).show();
        finish();
    }
}

package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * With stock_home_redirect set, it also sends the stock launcher's home screen back here, for firmware that turns
 * that launcher on again after it was disabled (XGIMI's xrmservice does, on its own remote keys).
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same maps InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();

    /** Waits for the user to unlock when Android binds this service at boot; null once the config is read. */
    private BroadcastReceiver unlockReceiver;
    /**
     * stock_home_redirect without key filtering: the Home button, seen through the system's "homekey" broadcast.
     * The window redirect alone misses a press made while the stock home is already in front (after a voice
     * search's genre results, say), since no window changes then.
     */
    private BroadcastReceiver homeKeyReceiver;
    /** The stock launcher's HOME activities (stock_home_redirect), disabled ones included. */
    private final Set<String> stockHome = new HashSet<>();
    private String stockHomePackage = "";
    /** volume_acceleration: watches volume changes while this service is bound. */
    private VolumeKeys volumeKeys;
    /** The last app other than the stock launcher that came to the front, and when. */
    private String lastWindowPackage = "";
    private long lastWindowAt;
    private static final String VOICE_SEARCH = "com.google.android.katniss";
    private static final long VOICE_RESULT_WINDOW_MS = 30_000;
    /**
     * Leaving the screensaver, Google TV starts its home screen, which can show a second or two late on a TV short
     * of memory: after the person already opened an app from this launcher. Sending it back then lands on top of
     * that app mid-start (Hotstar stayed on a blank splash, Xiaomi MOOR2). So the redirect waits REDIRECT_DELAY_MS
     * and gives up if any other window comes forward, and is skipped within APP_OPEN_GRACE_MS of opening an app.
     */
    private static final long REDIRECT_DELAY_MS = 1_000;
    private static final long APP_OPEN_GRACE_MS = 5_000;
    /** SystemClock.uptimeMillis() when this launcher last opened an app; 0 before the first. */
    private static volatile long appOpenedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable redirect = this::goHome;

    /** Called just before this launcher (Home or a remote app key) opens an app. */
    static void noteAppOpened() {
        appOpenedAt = SystemClock.uptimeMillis();
    }

    /** Whether a stock home screen that appeared at {@code now} came too soon after opening an app to send back. */
    static boolean justOpenedApp(long now, long openedAt) {
        return openedAt != 0 && now - openedAt < APP_OPEN_GRACE_MS;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // The service is directBootAware: Android 11 skips a service that is not while the user is still
        // locked at boot, writes accessibility_enabled=0 and, on TCL G03 firmware, never binds it after the
        // unlock. Config.load reads credential-protected storage, so wait for the unlock before reading it.
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadConfig();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // The unlock may have happened between the check and the registration.
        if (users.isUserUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    @Override
    public void onDestroy() {
        stopWaitingForUnlock();
        handler.removeCallbacks(redirect);
        if (volumeKeys != null) volumeKeys.stop();
        if (homeKeyReceiver != null) {
            unregisterReceiver(homeKeyReceiver);
            homeKeyReceiver = null;
        }
        super.onDestroy();
    }

    private void loadConfig() {
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        stockHome.clear();
        stockHomePackage = config.stockHomeRedirect;
        if (volumeKeys != null) volumeKeys.stop();
        volumeKeys = config.volumeAcceleration ? new VolumeKeys(this) : null;
        if (volumeKeys != null) volumeKeys.start();
        if (!config.stockHomeRedirect.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(config.stockHomeRedirect);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
                stockHome.add(ri.activityInfo.name);
            }
        }
        // home_key_service.xml subscribes to no events, so onAccessibilityEvent never ran and the redirect
        // could not fire. Ask for window changes only while a redirect is configured.
        if (!stockHome.isEmpty() && homeKeyReceiver == null) {
            homeKeyReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("homekey".equals(intent.getStringExtra("reason"))) goHome();
                }
            };
            // ACTION_CLOSE_SYSTEM_DIALOGS reaches apps up to Android 11; later versions simply never send it here,
            // and the window redirect below still covers the stock home appearing.
            registerSystemReceiver(homeKeyReceiver, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        }
        if (!stockHome.isEmpty()) {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
                setServiceInfo(info);
            }
        }
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, appScancodes.get(event.getScanCode()),
                holdAppScancodes.get(event.getScanCode()))) return true;
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : SystemSettings.intent(this, Settings.ACTION_SETTINGS);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        }
        if (RemoteKeys.handle(this, event, appKeys.get(event.getKeyCode()),
                holdAppKeys.get(event.getKeyCode()))) return true;
        // With stock_home_redirect the stock launcher is enabled and owns Home, so take the Home button too (only
        // while key_filter is on; without it the homekey broadcast does the same job).
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && !stockHome.isEmpty()) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) goHome();
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // stock_home_redirect: the firmware turned the stock launcher back on and its home screen came up.
        if (stockHome.isEmpty() || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || pkg.toString().equals(stockHomePackage)) {
            // Voice search opens genre and browse results inside the stock home screen: leave those up.
            if (cls == null || !stockHome.contains(cls.toString())) return;
            if (VOICE_SEARCH.equals(lastWindowPackage)
                    && System.currentTimeMillis() - lastWindowAt < VOICE_RESULT_WINDOW_MS) return;
            if (justOpenedApp(SystemClock.uptimeMillis(), appOpenedAt)) return;
        } else {
            // Something else came forward, so the stock home screen is no longer in front.
            handler.removeCallbacks(redirect);
            lastWindowPackage = pkg.toString();
            lastWindowAt = System.currentTimeMillis();
            return;
        }
        handler.removeCallbacks(redirect);
        handler.postDelayed(redirect, REDIRECT_DELAY_MS);
    }

    /** A receiver for a broadcast only the system sends, so it has to be exported (the flag exists from Android 13). */
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerSystemReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    /** Brings this launcher's home screen to the front. */
    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override
    public void onInterrupt() {
    }
}

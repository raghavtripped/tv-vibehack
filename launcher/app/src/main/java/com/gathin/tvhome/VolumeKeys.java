package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.SystemClock;

/**
 * volume_acceleration: Volume up/down speed up while pressed again quickly or held, 1, 2, 4, then 8 steps at a
 * time, the press-and-hold acceleration of car stereos and Kodi's volume ramp. A single press still moves one
 * step, so fine control is unchanged.
 *
 * It watches volume changes instead of the keys: on TVs the system adjusts the volume before accessibility
 * services see a volume key (MiTV-MOOR2, Android 11: D-pad keys reach the helpers, volume keys never do), and a
 * key-filtering helper costs the remote's auto-repeat there, so a held button would move one step. The TV makes
 * its own one-step change for every press or repeat; while those come in an unbroken streak this tops each one up
 * to the current step. Changes of more than one step (a slider, a phone app, this class's own) are left alone.
 * Runs inside HomeKeyService, which is bound all the time, so it works over any app.
 */
final class VolumeKeys {

    /** Volume steps closer together than this continue one streak. */
    static final long STREAK_GAP_MS = 500;
    /** The step doubles after each of these within a streak. */
    static final long DOUBLE_EVERY_MS = 400;
    /** A held button repeats every 70 ms or so; top up at most this often, so a hold stays controllable. */
    static final long MIN_BOOST_INTERVAL_MS = 200;

    private static final String ACTION = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";

    /** The streak bookkeeping, apart from Android so it can be tested on the JVM. */
    static final class Streak {
        private int direction;
        private long start, last, lastBoost;

        /** Extra steps to add after the TV changed the volume from prev to value at time now (0: none). */
        int extra(long now, int prev, int value, int maxStep) {
            int delta = value - prev;
            // The MOOR2 follows every Volume up step with a second broadcast that changes nothing (21 -> 21,
            // about 50 ms later). It says nothing about the streak, so it must not end it.
            if (delta == 0) return 0;
            if (Math.abs(delta) != 1) {
                direction = 0;
                return 0;
            }
            int dir = Integer.signum(delta);
            if (dir != direction || now - last > STREAK_GAP_MS) {
                direction = dir;
                start = last = lastBoost = now;
                return 0;   // the first step of a streak is the TV's own single step
            }
            last = now;
            if (now - lastBoost < MIN_BOOST_INTERVAL_MS) return 0;
            lastBoost = now;
            return step(now - start, maxStep) - 1;
        }
    }

    private final Context context;
    private final AudioManager audio;
    private final Streak streak = new Streak();
    /** The volume this class last set, so its own change broadcast is not taken for a press. */
    private int expected = -1;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return;
            onVolumeChanged(intent.getIntExtra(EXTRA_PREV, -1), intent.getIntExtra(EXTRA_VALUE, -1));
        }
    };

    VolumeKeys(Context context) {
        this.context = context;
        this.audio = context.getSystemService(AudioManager.class);
    }

    void start() {
        if (registered || audio == null) return;
        registered = true;
        // A system broadcast (only AudioService sends it), so exported is right on Android 13+.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, new IntentFilter(ACTION));
        }
    }

    void stop() {
        if (!registered) return;
        registered = false;
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void onVolumeChanged(int prev, int value) {
        if (prev < 0 || value < 0) return;
        if (value == expected) {
            expected = -1;
            return;
        }
        if (audio.isVolumeFixed() || audio.isStreamMute(AudioManager.STREAM_MUSIC)) return;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int extra = streak.extra(SystemClock.uptimeMillis(), prev, value, maxStep(max));
        if (extra <= 0) return;
        int target = next(value, Integer.signum(value - prev), extra, max);
        if (target == value) return;
        expected = target;
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
    }

    /** Largest step for a stream with this many steps: an eighth of the range, at least 1. */
    static int maxStep(int maxVolume) {
        return Math.max(1, maxVolume / 12);
    }

    /** Step size this far into a streak: 1, then doubling every DOUBLE_EVERY_MS, capped at maxStep. */
    static int step(long streakMs, int maxStep) {
        long level = Math.max(0, streakMs) / DOUBLE_EVERY_MS;
        int step = level >= 30 ? Integer.MAX_VALUE : 1 << level;
        return Math.max(1, Math.min(step, maxStep));
    }

    /** Volume after one step from current, kept inside 0..max. */
    static int next(int current, int direction, int step, int max) {
        return Math.max(0, Math.min(max, current + direction * step));
    }
}

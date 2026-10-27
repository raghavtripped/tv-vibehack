package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VolumeKeysTest {

    @Test
    public void stepDoublesEveryIntervalUpToTheCap() {
        int cap = VolumeKeys.maxStep(100);
        assertEquals(8, cap);
        assertEquals(1, VolumeKeys.step(0, cap));
        assertEquals(1, VolumeKeys.step(399, cap));
        assertEquals(2, VolumeKeys.step(400, cap));
        assertEquals(4, VolumeKeys.step(800, cap));
        assertEquals(8, VolumeKeys.step(1200, cap));
        assertEquals(8, VolumeKeys.step(60_000, cap));
    }

    @Test
    public void smallScalesStayAtOneStep() {
        assertEquals(1, VolumeKeys.maxStep(15));
        assertEquals(1, VolumeKeys.step(5_000, VolumeKeys.maxStep(15)));
    }

    @Test
    public void hugeStreaksDoNotOverflow() {
        assertEquals(8, VolumeKeys.step(Long.MAX_VALUE, 8));
        assertEquals(1, VolumeKeys.step(-5, 8));
    }

    @Test
    public void nextStaysInsideTheRange() {
        assertEquals(28, VolumeKeys.next(20, 1, 8, 100));
        assertEquals(100, VolumeKeys.next(97, 1, 8, 100));
        assertEquals(0, VolumeKeys.next(3, -1, 8, 100));
    }

    @Test
    public void singlePressGetsNoTopUp() {
        VolumeKeys.Streak s = new VolumeKeys.Streak();
        assertEquals(0, s.extra(1_000, 20, 21, 8));
        // A second press after a pause starts a new streak: still one step.
        assertEquals(0, s.extra(3_000, 21, 22, 8));
    }

    @Test
    public void quickTapsAccelerate() {
        VolumeKeys.Streak s = new VolumeKeys.Streak();
        int total = 0, volume = 20;
        for (int i = 0; i < 8; i++) {
            int extra = s.extra(i * 300L, volume, volume + 1, 8);
            volume += 1 + extra;
            total += 1 + extra;
        }
        // 1, 1, 2, 4, 8, 8, 8, 8 (doubling at 0.4 s, 0.8 s, 1.2 s into the streak).
        assertEquals(40, total);
    }

    @Test
    public void heldButtonIsToppedUpAtMostEvery200ms() {
        VolumeKeys.Streak s = new VolumeKeys.Streak();
        int boosts = 0;
        int volume = 20;
        for (long t = 0; t <= 1_000; t += 70) {
            int extra = s.extra(t, volume, volume + 1, 8);
            if (extra > 0) boosts++;
            volume += 1 + extra;
        }
        // Repeats every 70 ms for a second: checked at 210, 420, 630 and 840 ms, not on every repeat. At 210 ms
        // the step is still 1 (nothing to add), so three top-ups: +1, +1, +3.
        assertEquals(3, boosts);
    }

    @Test
    public void noChangeBroadcastsDoNotBreakTheStreak() {
        // Measured on the MOOR2: each Volume up press sends 20 -> 21, then 21 -> 21 about 50 ms later.
        VolumeKeys.Streak s = new VolumeKeys.Streak();
        int total = 0, volume = 20;
        for (int i = 0; i < 6; i++) {
            long t = i * 360L;
            int extra = s.extra(t, volume, volume + 1, 8);
            volume += 1 + extra;
            total += 1 + extra;
            assertEquals(0, s.extra(t + 50, volume, volume, 8));
        }
        // 1, 1, 2, 4, 8, 8 at 0, 360, 720, 1080, 1440, 1800 ms.
        assertEquals(24, total);
    }

    @Test
    public void directionChangeOrBigJumpResetsTheStreak() {
        VolumeKeys.Streak s = new VolumeKeys.Streak();
        s.extra(0, 20, 21, 8);
        s.extra(300, 21, 22, 8);
        assertEquals(0, s.extra(600, 22, 21, 8));   // now going down: a new streak
        assertEquals(0, s.extra(700, 21, 30, 8));   // a slider or phone app jump: not a press
        assertEquals(0, s.extra(800, 30, 31, 8));   // and the next press starts afresh
    }
}

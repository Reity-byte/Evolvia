package evolvia.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long FRAME_60HZ = SECOND / 60;

    /** Simulates {@code seconds} of real time in 60 Hz frames and returns the ticks produced. */
    private static int runFrames(Time time, int seconds) {
        int total = 0;
        long remaining = seconds * SECOND;
        while (remaining > 0) {
            long delta = Math.min(FRAME_60HZ, remaining);
            total += time.advance(delta);
            remaining -= delta;
        }
        return total;
    }

    @Test
    void normalSpeedRunsTwentyTicksPerSecond() {
        Time time = new Time();
        assertEquals(20 * 5, runFrames(time, 5));
    }

    @Test
    void speedMultipliesTickRate() {
        Time fast = new Time();
        fast.setSpeed(Time.Speed.FAST);
        assertEquals(60, runFrames(fast, 1));

        Time fastest = new Time();
        fastest.setSpeed(Time.Speed.FASTEST);
        assertEquals(200, runFrames(fastest, 1));
    }

    @Test
    void pausedRunsNoTicksAndKeepsAlpha() {
        Time time = new Time();
        time.advance(Time.TICK_NANOS / 2);
        float alpha = time.alpha();

        time.setSpeed(Time.Speed.PAUSED);
        assertEquals(0, runFrames(time, 3));
        assertEquals(alpha, time.alpha());
    }

    @Test
    void alphaIsFractionOfTick() {
        Time time = new Time();
        assertEquals(0, time.advance(Time.TICK_NANOS / 4));
        assertEquals(0.25f, time.alpha(), 1e-6f);
        assertEquals(1, time.advance(Time.TICK_NANOS));
        assertEquals(0.25f, time.alpha(), 1e-6f);
    }

    @Test
    void longStallIsCappedToMaxLag() {
        Time time = new Time();
        // 5 s freeze (e.g. window dragged): only 1 s worth of ticks is caught up, the rest is dropped.
        assertEquals(Time.TICKS_PER_SECOND, time.advance(5 * SECOND));
        assertEquals(4 * Time.TICKS_PER_SECOND, time.droppedTicks());
    }

    @Test
    void nextTickCountsUp() {
        Time time = new Time();
        assertEquals(0, time.nextTick());
        assertEquals(1, time.nextTick());
        assertEquals(2, time.tickCount());
    }
}

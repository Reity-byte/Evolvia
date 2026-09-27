package evolvia.core;

/**
 * Fixed-timestep simulation clock.
 * <p>
 * Converts real elapsed time into a whole number of simulation ticks (20 ticks/s at 1x speed)
 * using an accumulator, and exposes the interpolation factor for rendering between ticks.
 * Works in integer nanoseconds so the tick count is exact (no floating-point drift).
 * Contains no GLFW/OpenGL code so it can be unit tested.
 */
public final class Time {

    /** Simulation ticks per second of game time. */
    public static final int TICKS_PER_SECOND = 20;
    /** Length of one tick in nanoseconds of game time. */
    public static final long TICK_NANOS = 1_000_000_000L / TICKS_PER_SECOND;
    /** Maximum real time the simulation may fall behind before excess ticks are dropped (DESIGN.md §10). */
    public static final long MAX_LAG_NANOS = 1_000_000_000L;

    /** Game speed = number of game seconds per real second. */
    public enum Speed {
        PAUSED(0), NORMAL(1), FAST(3), FASTEST(10);

        public final int multiplier;

        Speed(int multiplier) {
            this.multiplier = multiplier;
        }
    }

    private Speed speed = Speed.NORMAL;
    /** Accumulated, not yet simulated game time in nanoseconds. */
    private long accumulatorNanos;
    /** Index of the next tick to run. */
    private long tick;
    /** Total ticks dropped because the simulation could not keep up. */
    private long droppedTicks;

    /**
     * Adds real elapsed time and returns how many ticks should be simulated now.
     * Call {@link #nextTick()} once for each of them.
     */
    public int advance(long realDeltaNanos) {
        if (realDeltaNanos <= 0 || speed == Speed.PAUSED) {
            return 0;
        }
        accumulatorNanos += realDeltaNanos * speed.multiplier;

        long maxDebt = Math.max(TICK_NANOS, MAX_LAG_NANOS * speed.multiplier);
        if (accumulatorNanos > maxDebt) {
            droppedTicks += (accumulatorNanos - maxDebt) / TICK_NANOS;
            accumulatorNanos = maxDebt;
        }

        int ticks = (int) (accumulatorNanos / TICK_NANOS);
        accumulatorNanos -= ticks * TICK_NANOS;
        return ticks;
    }

    /** Returns the index of the tick about to be simulated and advances the counter. */
    public long nextTick() {
        return tick++;
    }

    /** Number of ticks simulated so far. */
    public long tickCount() {
        return tick;
    }

    /** Interpolation factor in [0, 1) between the previous and the current tick state. */
    public float alpha() {
        return (float) ((double) accumulatorNanos / TICK_NANOS);
    }

    public Speed speed() {
        return speed;
    }

    public void setSpeed(Speed speed) {
        this.speed = speed;
    }

    public long droppedTicks() {
        return droppedTicks;
    }
}

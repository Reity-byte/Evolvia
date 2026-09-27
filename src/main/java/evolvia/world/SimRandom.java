package evolvia.world;

import java.util.Random;

/**
 * The world's random generator: the same algorithm as {@link Random} (so the same seed gives the same
 * worlds as before), but its state can be read and restored for save games (DESIGN.md §11, phase 8).
 * Not thread-safe (the simulation is single-threaded).
 */
public final class SimRandom extends Random {

    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    // No field initializers: Random's constructor calls setSeed before they would run.
    private long state;
    private double nextNextGaussian;
    private boolean haveNextNextGaussian;

    public SimRandom(long seed) {
        super(seed);
    }

    /** Generator continuing from a saved {@link #state()}. */
    public static SimRandom restore(State saved) {
        SimRandom random = new SimRandom(0L);
        random.state = saved.seed();
        random.haveNextNextGaussian = saved.haveNextNextGaussian();
        random.nextNextGaussian = saved.nextNextGaussian();
        return random;
    }

    /** Complete generator state. */
    public record State(long seed, boolean haveNextNextGaussian, double nextNextGaussian) {
    }

    public State state() {
        return new State(state, haveNextNextGaussian, nextNextGaussian);
    }

    @Override
    public void setSeed(long seed) {
        state = (seed ^ MULTIPLIER) & MASK;
        haveNextNextGaussian = false;
    }

    @Override
    protected int next(int bits) {
        state = (state * MULTIPLIER + ADDEND) & MASK;
        return (int) (state >>> (48 - bits));
    }

    @Override
    public double nextGaussian() {
        // Same polar method as java.util.Random.
        if (haveNextNextGaussian) {
            haveNextNextGaussian = false;
            return nextNextGaussian;
        }
        double v1;
        double v2;
        double s;
        do {
            v1 = 2 * nextDouble() - 1;
            v2 = 2 * nextDouble() - 1;
            s = v1 * v1 + v2 * v2;
        } while (s >= 1 || s == 0);
        double multiplier = StrictMath.sqrt(-2 * StrictMath.log(s) / s);
        nextNextGaussian = v2 * multiplier;
        haveNextNextGaussian = true;
        return v1 * multiplier;
    }
}

package evolvia.core;

/**
 * Per-second loop measurements for the debug overlay. Updated by {@link GameLoop}.
 */
public final class LoopStats {

    int fps;
    int tps;
    double avgTickMillis;

    /** Rendered frames during the last full second. */
    public int fps() {
        return fps;
    }

    /** Simulation ticks during the last full second. */
    public int tps() {
        return tps;
    }

    /** Average duration of one simulation tick during the last full second, in milliseconds. */
    public double avgTickMillis() {
        return avgTickMillis;
    }
}

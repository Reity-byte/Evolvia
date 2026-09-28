package evolvia.world;

/** Counts of creature deaths by cause since the world started. */
public final class DeathStats {

    public enum Cause { STARVATION, THIRST, EXPOSURE, OLD_AGE, LIGHTNING, FIGHT, DISEASE, FIRE, DROWNING }

    private final int[] counts = new int[Cause.values().length];

    /** Restores a saved count (save games). */
    public void restore(Cause cause, int count) {
        counts[cause.ordinal()] = count;
    }

    public void record(Cause cause) {
        counts[cause.ordinal()]++;
    }

    public int count(Cause cause) {
        return counts[cause.ordinal()];
    }

    public int total() {
        int total = 0;
        for (int count : counts) {
            total += count;
        }
        return total;
    }
}

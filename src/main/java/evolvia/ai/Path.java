package evolvia.ai;

/** A walkable path: waypoints in world coordinates (tile units), followed in order. */
public final class Path {

    private final float[] xs;
    private final float[] zs;
    private int next;

    public Path(float[] xs, float[] zs) {
        if (xs.length != zs.length || xs.length == 0) {
            throw new IllegalArgumentException("Path needs at least one waypoint");
        }
        this.xs = xs;
        this.zs = zs;
    }

    /** Path restored from a save game, continuing at waypoint {@code next}. */
    public Path(float[] xs, float[] zs, int next) {
        this(xs, zs);
        if (next < 0 || next > xs.length) {
            throw new IllegalArgumentException("Waypoint index " + next + " outside the path");
        }
        this.next = next;
    }

    /** Index of the next waypoint. */
    public int nextIndex() {
        return next;
    }

    public int length() {
        return xs.length;
    }

    /** Waypoints not yet reached (including the current target waypoint). */
    public int remaining() {
        return xs.length - next;
    }

    public boolean isFinished() {
        return next >= xs.length;
    }

    public float nextX() {
        return xs[next];
    }

    public float nextZ() {
        return zs[next];
    }

    /** Moves on to the following waypoint. */
    public void advance() {
        next++;
    }

    public float x(int index) {
        return xs[index];
    }

    public float z(int index) {
        return zs[index];
    }
}

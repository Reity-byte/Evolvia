package evolvia.components;

/** A creature scared by a lightning strike: it runs away from the strike until {@link #untilTick}. */
public final class Fear {

    /** Where the danger was. */
    public float fromX;
    public float fromZ;
    /** How far to run. */
    public float distance;
    public int untilTick;

    public boolean isActive(int tick) {
        return tick < untilTick;
    }
}

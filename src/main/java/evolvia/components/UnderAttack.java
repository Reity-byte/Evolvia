package evolvia.components;

/** A creature that was hit in a fight recently: it fights back or runs, and dies "in a fight". */
public final class UnderAttack {

    /** The creature that hit it last. */
    public int attacker;
    public int untilTick;

    public boolean isActive(int tick) {
        return tick < untilTick;
    }
}

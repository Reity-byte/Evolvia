package evolvia.components;

/** Random wandering: walk to a nearby target, wait, pick another target. */
public final class WanderState {

    public float targetX;
    public float targetZ;
    /** True while walking to the target. */
    public boolean moving;
    /** Ticks left to wait before picking the next target. */
    public int waitTicks;
}

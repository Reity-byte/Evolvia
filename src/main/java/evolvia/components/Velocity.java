package evolvia.components;

/** Movement on the ground plane. */
public final class Velocity {

    /** Normalized direction on the XZ plane. */
    public float dirX;
    public float dirZ;
    /** Distance in tiles per tick; 0 = standing. */
    public float speed;
    /** Set by the movement system when the next step would leave passable terrain. */
    public boolean blocked;
}

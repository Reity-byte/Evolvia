package evolvia.components;

/**
 * Where a creature last drank and ate. Only used by species with the {@code memory} ability:
 * when nothing is in sight, they walk back to the remembered place.
 */
public final class Memory {

    public boolean knowsWater;
    public float waterX;
    public float waterZ;

    public boolean knowsFood;
    public float foodX;
    public float foodZ;
}

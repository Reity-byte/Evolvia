package evolvia.components;

import evolvia.world.ResourceDefinition;

/** A resource in the world (berry bush, water source). Static: has a Transform but never moves. */
public final class ResourceNode {

    public final ResourceDefinition type;
    /** Food units left (unused for water, which is unlimited). */
    public float amount;
    /** Regrowth per tick at this node (type rate scaled by the tile's fertility). */
    public final float regrowPerTick;

    public ResourceNode(ResourceDefinition type, float amount, float regrowPerTick) {
        this.type = type;
        this.amount = amount;
        this.regrowPerTick = regrowPerTick;
    }
}

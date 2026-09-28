package evolvia.systems;

import evolvia.components.ResourceNode;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Nature;
import evolvia.world.ResourceKind;
import evolvia.world.SpatialGrid;

/**
 * Food regrows over time (faster on fertile tiles; rate fixed per node at spawn). Decaying food
 * (carcasses) loses amount instead and disappears when empty.
 */
public final class ResourceRegrowthSystem implements GameSystem {

    private final SpatialGrid foodGrid;
    private final Nature nature;

    public ResourceRegrowthSystem(SpatialGrid foodGrid) {
        this(foodGrid, null);
    }

    /** @param nature seasons and weather change the regrowth, burnt ground grows nothing (null = neither) */
    public ResourceRegrowthSystem(SpatialGrid foodGrid, Nature nature) {
        this.foodGrid = foodGrid;
        this.nature = nature;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<ResourceNode> nodes = world.store(ResourceNode.class);
        ComponentStore<Transform> transforms = world.store(Transform.class);
        float multiplier = nature != null ? nature.regrowMultiplier(tick) : 1f;
        boolean fires = nature != null && nature.hasBurntGround();
        for (int i = 0; i < nodes.size(); i++) {
            ResourceNode node = nodes.componentAt(i);
            if (node.type.decays()) {
                node.ageTicks++;
                node.amount -= SpeciesDefinition.perTick(node.type.decayPerSecond());
                if (node.amount < 1f) { // less than one bite left: gone
                    int entity = nodes.entityAt(i);
                    Transform t = transforms.get(entity);
                    if (node.type.kind() == ResourceKind.FOOD && t != null) {
                        foodGrid.remove(entity, t.position.x, t.position.z);
                    }
                    world.destroyEntity(entity);
                }
            } else if (node.regrowPerTick > 0 && node.amount < node.type.capacity()) {
                if (fires) {
                    Transform t = transforms.get(nodes.entityAt(i));
                    if (nature.isBurnt((int) Math.floor(t.position.x), (int) Math.floor(t.position.z), tick)) {
                        continue;
                    }
                }
                node.amount = Math.min(node.type.capacity(), node.amount + node.regrowPerTick * multiplier);
            }
        }
    }
}

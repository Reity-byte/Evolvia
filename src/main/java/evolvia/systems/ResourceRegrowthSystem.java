package evolvia.systems;

import evolvia.components.ResourceNode;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.ResourceKind;
import evolvia.world.SpatialGrid;

/**
 * Food regrows over time (faster on fertile tiles; rate fixed per node at spawn). Decaying food
 * (carcasses) loses amount instead and disappears when empty.
 */
public final class ResourceRegrowthSystem implements GameSystem {

    private final SpatialGrid foodGrid;

    public ResourceRegrowthSystem(SpatialGrid foodGrid) {
        this.foodGrid = foodGrid;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<ResourceNode> nodes = world.store(ResourceNode.class);
        ComponentStore<Transform> transforms = world.store(Transform.class);
        for (int i = 0; i < nodes.size(); i++) {
            ResourceNode node = nodes.componentAt(i);
            if (node.type.decays()) {
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
                node.amount = Math.min(node.type.capacity(), node.amount + node.regrowPerTick);
            }
        }
    }
}

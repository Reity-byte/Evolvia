package evolvia.systems;

import evolvia.components.ResourceNode;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;

/** Food regrows over time, faster on fertile tiles (rate fixed per node at spawn). */
public final class ResourceRegrowthSystem implements GameSystem {

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<ResourceNode> nodes = world.store(ResourceNode.class);
        for (int i = 0; i < nodes.size(); i++) {
            ResourceNode node = nodes.componentAt(i);
            if (node.regrowPerTick > 0 && node.amount < node.type.capacity()) {
                node.amount = Math.min(node.type.capacity(), node.amount + node.regrowPerTick);
            }
        }
    }
}

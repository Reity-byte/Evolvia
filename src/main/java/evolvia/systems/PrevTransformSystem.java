package evolvia.systems;

import evolvia.components.PrevTransform;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;

/**
 * Runs first in every tick: remembers where each entity was, so rendering can interpolate
 * between the previous and the current tick.
 */
public final class PrevTransformSystem implements GameSystem {

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<PrevTransform> previous = world.store(PrevTransform.class);
        ComponentStore<Transform> transforms = world.store(Transform.class);
        for (int i = 0; i < previous.size(); i++) {
            Transform current = transforms.get(previous.entityAt(i));
            if (current != null) {
                PrevTransform prev = previous.componentAt(i);
                prev.position.set(current.position);
                prev.yaw = current.yaw;
            }
        }
    }
}

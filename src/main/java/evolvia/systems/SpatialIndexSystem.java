package evolvia.systems;

import evolvia.components.PrevTransform;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.world.SpatialGrid;

/**
 * Keeps the creature {@link SpatialGrid} in sync after movement (the previous transform holds the
 * position the grid knows, since it is copied at the start of the tick, before anything moves).
 */
public final class SpatialIndexSystem implements GameSystem {

    private final SpatialGrid creatureGrid;

    public SpatialIndexSystem(SpatialGrid creatureGrid) {
        this.creatureGrid = creatureGrid;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<SpeciesRef> creatures = world.store(SpeciesRef.class);
        ComponentStore<Transform> transforms = world.store(Transform.class);
        ComponentStore<PrevTransform> previous = world.store(PrevTransform.class);
        for (int i = 0; i < creatures.size(); i++) {
            int entity = creatures.entityAt(i);
            Transform current = transforms.get(entity);
            PrevTransform prev = previous.get(entity);
            if (current == null || prev == null) {
                continue;
            }
            if (current.position.x != prev.position.x || current.position.z != prev.position.z) {
                creatureGrid.move(entity, prev.position.x, prev.position.z, current.position.x, current.position.z);
            }
        }
    }
}

package evolvia.systems;

import evolvia.ai.Navigation;
import evolvia.ai.Pathfinder;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.components.Velocity;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;

/**
 * Moves entities along their {@link Velocity}, keeps them on the ground (or swimming at the water
 * surface) and never lets them step onto a tile their species cannot cross (deep water, outside the
 * map, water for non-swimmers): such a step is refused and the velocity is marked {@link Velocity#blocked}.
 */
public final class MovementSystem implements GameSystem {

    private final Navigation navigation;

    public MovementSystem(Navigation navigation) {
        this.navigation = navigation;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<Velocity> velocities = world.store(Velocity.class);
        ComponentStore<Transform> transforms = world.store(Transform.class);
        ComponentStore<SpeciesRef> species = world.store(SpeciesRef.class);

        for (int i = 0; i < velocities.size(); i++) {
            Velocity velocity = velocities.componentAt(i);
            if (velocity.speed <= 0) {
                continue;
            }
            int entity = velocities.entityAt(i);
            Transform transform = transforms.get(entity);
            SpeciesRef ref = species.get(entity);
            if (transform == null) {
                continue;
            }
            Pathfinder space = ref != null ? navigation.forCreature(ref) : navigation.land();
            float x = transform.position.x + velocity.dirX * velocity.speed;
            float z = transform.position.z + velocity.dirZ * velocity.speed;
            if (!space.isWalkable((int) Math.floor(x), (int) Math.floor(z))) {
                velocity.speed = 0;
                velocity.blocked = true;
                continue;
            }
            transform.position.set(x, navigation.groundHeight(x, z), z);
            transform.yaw = (float) Math.atan2(velocity.dirX, velocity.dirZ);
        }
    }
}

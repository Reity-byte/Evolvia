package evolvia.systems;

import evolvia.components.Transform;
import evolvia.components.Velocity;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.world.Terrain;

/**
 * Moves entities along their {@link Velocity}, keeps them on the terrain surface and never lets
 * them step onto an impassable tile (water, outside the map): such a step is refused and the
 * velocity is marked {@link Velocity#blocked}.
 */
public final class MovementSystem implements GameSystem {

    private final Terrain terrain;

    public MovementSystem(Terrain terrain) {
        this.terrain = terrain;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<Velocity> velocities = world.store(Velocity.class);
        ComponentStore<Transform> transforms = world.store(Transform.class);

        for (int i = 0; i < velocities.size(); i++) {
            Velocity velocity = velocities.componentAt(i);
            if (velocity.speed <= 0) {
                continue;
            }
            Transform transform = transforms.get(velocities.entityAt(i));
            if (transform == null) {
                continue;
            }
            float x = transform.position.x + velocity.dirX * velocity.speed;
            float z = transform.position.z + velocity.dirZ * velocity.speed;
            if (!terrain.isPassable((int) Math.floor(x), (int) Math.floor(z))) {
                velocity.speed = 0;
                velocity.blocked = true;
                continue;
            }
            transform.position.set(x, terrain.heightAt(x, z), z);
            transform.yaw = (float) Math.atan2(velocity.dirX, velocity.dirZ);
        }
    }
}

package evolvia.systems;

import evolvia.components.Transform;
import evolvia.components.Velocity;
import evolvia.components.WanderState;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Terrain;

import java.util.Random;

/**
 * Wandering: pick a random passable target nearby, walk to it in a straight line, wait a random
 * time, repeat. If the way is blocked (water ahead) the creature stops and soon picks a new target.
 * Sets the {@link Velocity}; the {@link MovementSystem} does the actual moving.
 */
public final class WanderSystem implements GameSystem {

    private static final int TARGET_ATTEMPTS = 8;
    private static final float TWO_PI = (float) (Math.PI * 2);

    private final Terrain terrain;
    private final Random random;
    private final float speedPerTick;
    private final float radius;
    private final int pauseMinTicks;
    private final int pauseMaxTicks;

    public WanderSystem(Terrain terrain, Random random, SpeciesDefinition species) {
        this.terrain = terrain;
        this.random = random;
        this.speedPerTick = species.speedPerTick();
        this.radius = species.wanderRadius();
        this.pauseMinTicks = SpeciesDefinition.secondsToTicks(species.wanderPauseMinSeconds());
        this.pauseMaxTicks = SpeciesDefinition.secondsToTicks(species.wanderPauseMaxSeconds());
    }

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<WanderState> wanders = world.store(WanderState.class);
        ComponentStore<Transform> transforms = world.store(Transform.class);
        ComponentStore<Velocity> velocities = world.store(Velocity.class);

        for (int i = 0; i < wanders.size(); i++) {
            int entity = wanders.entityAt(i);
            WanderState wander = wanders.componentAt(i);
            Transform transform = transforms.get(entity);
            Velocity velocity = velocities.get(entity);
            if (transform == null || velocity == null) {
                continue;
            }

            if (wander.moving) {
                if (velocity.blocked) {
                    stop(wander, velocity, randomTicks(0, pauseMinTicks));
                    continue;
                }
                float dx = wander.targetX - transform.position.x;
                float dz = wander.targetZ - transform.position.z;
                float distance = (float) Math.sqrt(dx * dx + dz * dz);
                if (distance <= speedPerTick) {
                    stop(wander, velocity, randomTicks(pauseMinTicks, pauseMaxTicks));
                } else {
                    velocity.dirX = dx / distance;
                    velocity.dirZ = dz / distance;
                    velocity.speed = speedPerTick;
                }
            } else if (wander.waitTicks > 0) {
                wander.waitTicks--;
            } else {
                pickTarget(transform, wander, velocity);
            }
        }
    }

    private void pickTarget(Transform transform, WanderState wander, Velocity velocity) {
        for (int attempt = 0; attempt < TARGET_ATTEMPTS; attempt++) {
            float angle = random.nextFloat() * TWO_PI;
            float distance = radius * (0.3f + 0.7f * random.nextFloat());
            float x = transform.position.x + (float) Math.sin(angle) * distance;
            float z = transform.position.z + (float) Math.cos(angle) * distance;
            if (terrain.isPassable((int) Math.floor(x), (int) Math.floor(z))) {
                wander.targetX = x;
                wander.targetZ = z;
                wander.moving = true;
                velocity.blocked = false;
                velocity.dirX = (float) Math.sin(angle);
                velocity.dirZ = (float) Math.cos(angle);
                velocity.speed = speedPerTick;
                return;
            }
        }
        // No passable target around (e.g. a tiny island): try again later.
        wander.waitTicks = Math.max(1, pauseMinTicks);
    }

    private static void stop(WanderState wander, Velocity velocity, int waitTicks) {
        wander.moving = false;
        wander.waitTicks = waitTicks;
        velocity.speed = 0;
        velocity.blocked = false;
    }

    private int randomTicks(int min, int max) {
        return max > min ? min + random.nextInt(max - min + 1) : min;
    }
}

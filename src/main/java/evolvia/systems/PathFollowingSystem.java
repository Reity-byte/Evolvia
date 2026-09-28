package evolvia.systems;

import evolvia.ai.Path;
import evolvia.ai.PathQueue;
import evolvia.components.AiState;
import evolvia.components.Genome;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.components.Velocity;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;

/**
 * Steers creatures along their path: sets the {@link Velocity} towards the next waypoint and
 * marks the path ARRIVED at the end. Paths are kept (cached) until the way gets blocked; then the
 * path is recomputed, and after {@link #MAX_RETRIES} blocked attempts it FAILS.
 */
public final class PathFollowingSystem implements GameSystem {

    public static final int MAX_RETRIES = 3;

    private final PathQueue queue;

    public PathFollowingSystem(PathQueue queue) {
        this.queue = queue;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<AiState> aiStore = world.store(AiState.class);
        ComponentStore<Velocity> velocities = world.store(Velocity.class);
        ComponentStore<Transform> transforms = world.store(Transform.class);
        ComponentStore<SpeciesRef> species = world.store(SpeciesRef.class);
        ComponentStore<Genome> genomes = world.store(Genome.class);

        for (int i = 0; i < aiStore.size(); i++) {
            AiState ai = aiStore.componentAt(i);
            if (ai.pathStatus != AiState.PathStatus.FOLLOWING) {
                continue;
            }
            int entity = aiStore.entityAt(i);
            Velocity velocity = velocities.get(entity);
            Transform transform = transforms.get(entity);
            SpeciesRef ref = species.get(entity);
            if (velocity == null || transform == null || ref == null) {
                continue;
            }

            if (velocity.blocked) {
                velocity.blocked = false;
                velocity.speed = 0;
                ai.path = null;
                if (++ai.pathRetries > MAX_RETRIES) {
                    ai.pathStatus = AiState.PathStatus.FAILED;
                } else {
                    ai.pathStatus = AiState.PathStatus.PENDING;
                    queue.add(entity);
                }
                continue;
            }

            Path path = ai.path;
            Genome genome = genomes.get(entity);
            float step = ref.stats().speedPerTick() * (genome != null ? genome.speed : 1f);
            float x = transform.position.x;
            float z = transform.position.z;
            // Skip intermediate waypoints that are already (almost) reached.
            while (path.remaining() > 1 && distance(x, z, path.nextX(), path.nextZ()) <= step) {
                path.advance();
            }
            float dx = path.nextX() - x;
            float dz = path.nextZ() - z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (path.remaining() == 1 && dist <= step) {
                // Final step lands exactly on the goal.
                if (dist > 1e-5f) {
                    velocity.dirX = dx / dist;
                    velocity.dirZ = dz / dist;
                }
                velocity.speed = dist;
                path.advance();
                ai.pathStatus = AiState.PathStatus.ARRIVED;
            } else {
                velocity.dirX = dx / dist;
                velocity.dirZ = dz / dist;
                velocity.speed = step;
            }
        }
    }

    private static float distance(float x0, float z0, float x1, float z1) {
        float dx = x1 - x0;
        float dz = z1 - z0;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }
}

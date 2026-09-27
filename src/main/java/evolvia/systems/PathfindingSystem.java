package evolvia.systems;

import evolvia.ai.Path;
import evolvia.ai.PathQueue;
import evolvia.ai.Pathfinder;
import evolvia.components.AiState;
import evolvia.components.Transform;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;

/**
 * Serves path requests from the {@link PathQueue}, at most {@link #MAX_SEARCHES_PER_TICK} searches and
 * about {@link #MAX_EXPANSIONS_PER_TICK} expanded tiles per tick
 * (DESIGN.md §8); the rest wait for later ticks, so a burst of requests never stalls a tick.
 */
public final class PathfindingSystem implements GameSystem {

    public static final int MAX_SEARCHES_PER_TICK = 40;
    /** Stop serving requests in a tick once this many tiles were expanded (long searches are expensive). */
    public static final int MAX_EXPANSIONS_PER_TICK = 20_000;

    private final Pathfinder pathfinder;
    private final PathQueue queue;
    private int searchesLastTick;
    private int expansionsLastTick;

    public PathfindingSystem(Pathfinder pathfinder, PathQueue queue) {
        this.pathfinder = pathfinder;
        this.queue = queue;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        int searches = 0;
        int expansions = 0;
        while (searches < MAX_SEARCHES_PER_TICK && expansions < MAX_EXPANSIONS_PER_TICK && queue.size() > 0) {
            int entity = queue.poll();
            if (!world.isAlive(entity)) {
                continue;
            }
            AiState ai = world.get(entity, AiState.class);
            Transform transform = world.get(entity, Transform.class);
            if (ai == null || transform == null || ai.pathStatus != AiState.PathStatus.PENDING) {
                continue; // cancelled meanwhile
            }
            Path path = pathfinder.find(transform.position.x, transform.position.z, ai.targetX, ai.targetZ);
            expansions += pathfinder.lastExpansions();
            searches++;
            ai.path = path;
            ai.pathStatus = path != null ? AiState.PathStatus.FOLLOWING : AiState.PathStatus.FAILED;
        }
        searchesLastTick = searches;
        expansionsLastTick = expansions;
    }

    public int searchesLastTick() {
        return searchesLastTick;
    }

    public int expansionsLastTick() {
        return expansionsLastTick;
    }
}

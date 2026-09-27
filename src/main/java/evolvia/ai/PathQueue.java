package evolvia.ai;

import java.util.ArrayDeque;

/**
 * FIFO of entities waiting for a path. Actions enqueue, the pathfinding system serves a limited
 * number per tick (DESIGN.md §8), so many requests never stall a tick.
 */
public final class PathQueue {

    private final ArrayDeque<Integer> entities = new ArrayDeque<>();

    public void add(int entity) {
        entities.addLast(entity);
    }

    /** Next waiting entity, or -1 if the queue is empty. */
    public int poll() {
        Integer entity = entities.pollFirst();
        return entity != null ? entity : -1;
    }

    /** Number of waiting requests (may include cancelled ones, which are skipped when served). */
    /** Waiting entities, first to be served first (for save games). */
    public int[] toArray() {
        return entities.stream().mapToInt(Integer::intValue).toArray();
    }

    public int size() {
        return entities.size();
    }
}

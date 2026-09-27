package evolvia.ecs;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entities and their component stores.
 * <p>
 * An entity is an {@code int} ID. IDs of destroyed entities are reused. Destruction is deferred:
 * {@link #destroyEntity(int)} only queues the entity, {@link #flushDestroyed()} (called once at the
 * end of every tick) removes its components and frees the ID. This keeps iteration in systems safe.
 */
public final class EcsWorld {

    private final Map<Class<?>, ComponentStore<?>> stores = new LinkedHashMap<>();
    private boolean[] alive = new boolean[64];
    private int nextId;
    private int aliveCount;
    private int[] freeIds = new int[16];
    private int freeCount;
    private int[] pendingDestroy = new int[16];
    private int pendingCount;

    /** Creates a new entity (reusing a freed ID if there is one). */
    public int createEntity() {
        int id = freeCount > 0 ? freeIds[--freeCount] : nextId++;
        if (id >= alive.length) {
            alive = Arrays.copyOf(alive, Math.max(id + 1, alive.length * 2));
        }
        alive[id] = true;
        aliveCount++;
        return id;
    }

    /** Queues {@code entity} for destruction at the end of the tick. The entity stays usable until then. */
    public void destroyEntity(int entity) {
        if (!isAlive(entity)) {
            return;
        }
        if (pendingCount == pendingDestroy.length) {
            pendingDestroy = Arrays.copyOf(pendingDestroy, pendingCount * 2);
        }
        pendingDestroy[pendingCount++] = entity;
    }

    /** Destroys all queued entities: removes their components and frees their IDs. */
    public void flushDestroyed() {
        for (int i = 0; i < pendingCount; i++) {
            int entity = pendingDestroy[i];
            if (!alive[entity]) {
                continue; // queued twice
            }
            for (ComponentStore<?> store : stores.values()) {
                store.remove(entity);
            }
            alive[entity] = false;
            aliveCount--;
            if (freeCount == freeIds.length) {
                freeIds = Arrays.copyOf(freeIds, freeCount * 2);
            }
            freeIds[freeCount++] = entity;
        }
        pendingCount = 0;
    }

    /** Entity ID allocation state, for save games. */
    public record IdState(int nextId, int[] alive, int[] free) {
    }

    /** Current ID allocation: next new ID, live entities (ascending) and freed IDs (in reuse order). Call between ticks. */
    public IdState idState() {
        int[] live = new int[aliveCount];
        int n = 0;
        for (int id = 0; id < nextId; id++) {
            if (alive[id]) {
                live[n++] = id;
            }
        }
        return new IdState(nextId, live, Arrays.copyOf(freeIds, freeCount));
    }

    /**
     * Restores the ID allocation of a saved world into this empty world; components are added afterwards.
     *
     * @throws IllegalStateException if the world already has entities
     */
    public void restoreIds(IdState state) {
        if (aliveCount > 0 || nextId > 0) {
            throw new IllegalStateException("restoreIds needs an empty world");
        }
        nextId = state.nextId();
        alive = new boolean[Math.max(64, nextId)];
        for (int id : state.alive()) {
            if (id < 0 || id >= nextId) {
                throw new IllegalArgumentException("Entity " + id + " outside 0.." + (nextId - 1));
            }
            alive[id] = true;
        }
        aliveCount = state.alive().length;
        freeIds = Arrays.copyOf(state.free(), Math.max(16, state.free().length));
        freeCount = state.free().length;
    }

    /** Component types that have a store (some may be empty). */
    public Set<Class<?>> componentTypes() {
        return Collections.unmodifiableSet(stores.keySet());
    }

    public boolean isAlive(int entity) {
        return entity >= 0 && entity < alive.length && alive[entity];
    }

    /** Number of live entities (including ones queued for destruction). */
    public int entityCount() {
        return aliveCount;
    }

    /** Store for a component type; created empty on first use. */
    @SuppressWarnings("unchecked")
    public <T> ComponentStore<T> store(Class<T> type) {
        return (ComponentStore<T>) stores.computeIfAbsent(type, t -> new SparseSetStore<>());
    }

    /** Adds (or replaces) a component; its runtime class selects the store. */
    @SuppressWarnings("unchecked")
    public <T> T add(int entity, T component) {
        if (!isAlive(entity)) {
            throw new IllegalArgumentException("Entity " + entity + " is not alive");
        }
        store((Class<T>) component.getClass()).put(entity, component);
        return component;
    }

    /** Component of {@code entity}, or null. */
    public <T> T get(int entity, Class<T> type) {
        return store(type).get(entity);
    }
}

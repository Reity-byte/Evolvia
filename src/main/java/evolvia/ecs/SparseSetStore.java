package evolvia.ecs;

import java.util.Arrays;

/**
 * {@link ComponentStore} backed by a sparse set: a sparse array maps entity ID to a dense index,
 * dense arrays hold entities and components contiguously. O(1) put/get/remove, no boxing.
 * Removal moves the last component into the freed slot.
 */
public final class SparseSetStore<T> implements ComponentStore<T> {

    private static final int ABSENT = -1;

    private int[] sparse = new int[64];
    private int[] entities = new int[64];
    private Object[] components = new Object[64];
    private int size;

    public SparseSetStore() {
        Arrays.fill(sparse, ABSENT);
    }

    @Override
    public void put(int entity, T component) {
        if (entity < 0) {
            throw new IllegalArgumentException("Invalid entity id " + entity);
        }
        if (component == null) {
            throw new IllegalArgumentException("Component must not be null");
        }
        if (entity >= sparse.length) {
            int oldLength = sparse.length;
            sparse = Arrays.copyOf(sparse, Math.max(entity + 1, oldLength * 2));
            Arrays.fill(sparse, oldLength, sparse.length, ABSENT);
        }
        int index = sparse[entity];
        if (index != ABSENT) {
            components[index] = component;
            return;
        }
        if (size == entities.length) {
            entities = Arrays.copyOf(entities, size * 2);
            components = Arrays.copyOf(components, size * 2);
        }
        entities[size] = entity;
        components[size] = component;
        sparse[entity] = size;
        size++;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get(int entity) {
        if (entity < 0 || entity >= sparse.length) {
            return null;
        }
        int index = sparse[entity];
        return index == ABSENT ? null : (T) components[index];
    }

    @Override
    public boolean has(int entity) {
        return entity >= 0 && entity < sparse.length && sparse[entity] != ABSENT;
    }

    @Override
    public boolean remove(int entity) {
        if (!has(entity)) {
            return false;
        }
        int index = sparse[entity];
        int last = size - 1;
        entities[index] = entities[last];
        components[index] = components[last];
        sparse[entities[index]] = index;
        components[last] = null;
        sparse[entity] = ABSENT;
        size--;
        return true;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public int entityAt(int index) {
        return entities[index];
    }

    @Override
    @SuppressWarnings("unchecked")
    public T componentAt(int index) {
        return (T) components[index];
    }
}

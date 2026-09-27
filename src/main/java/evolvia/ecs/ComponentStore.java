package evolvia.ecs;

/**
 * Storage for all components of one type, keyed by entity ID.
 * <p>
 * Besides lookup by entity it offers dense iteration: indices {@code 0 .. size()-1} with
 * {@link #entityAt(int)} / {@link #componentAt(int)}. Iteration order is unspecified and changes
 * when components are removed, so do not add or remove components of this type while iterating it
 * (entity destruction is deferred to the end of the tick for this reason).
 * Systems only use this interface, so the implementation can be swapped for a faster one.
 */
public interface ComponentStore<T> {

    /** Adds or replaces the component of {@code entity}. */
    void put(int entity, T component);

    /** Component of {@code entity}, or null. */
    T get(int entity);

    boolean has(int entity);

    /** Removes the component of {@code entity}; returns false if it had none. */
    boolean remove(int entity);

    /** Number of components stored. */
    int size();

    /** Entity at dense index {@code index} (0 .. size()-1). */
    int entityAt(int index);

    /** Component at dense index {@code index} (0 .. size()-1). */
    T componentAt(int index);
}

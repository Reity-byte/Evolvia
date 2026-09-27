package evolvia.ecs;

/**
 * Simulation logic run once per tick. Systems run in a fixed order defined in one place
 * ({@code World}); they must not depend on frame time, only on ticks.
 */
public interface GameSystem {

    /**
     * Advances this system by one tick.
     *
     * @param world entities and components
     * @param tick  index of the tick being simulated
     */
    void update(EcsWorld world, int tick);
}

package evolvia.world;

import evolvia.components.PrevTransform;
import evolvia.components.Transform;
import evolvia.components.Velocity;
import evolvia.components.WanderState;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.SpeciesDefinition;
import evolvia.systems.MovementSystem;
import evolvia.systems.PrevTransformSystem;
import evolvia.systems.WanderSystem;

import java.util.List;
import java.util.Random;

/**
 * The simulated world: terrain, entities and the systems that advance them.
 * <p>
 * Everything random comes from one {@link Random} seeded with the world seed (terrain generation
 * first, then spawning, then the systems), so the same seed replays the same world.
 * The system order is defined here and nowhere else.
 */
public final class World {

    private static final float TWO_PI = (float) (Math.PI * 2);
    private static final int SPAWN_ATTEMPTS = 10_000;

    private final long seed;
    private final Terrain terrain;
    private final SpeciesDefinition species;
    private final EcsWorld ecs = new EcsWorld();
    private final List<GameSystem> systems;

    private World(long seed, Terrain terrain, SpeciesDefinition species, Random random) {
        this.seed = seed;
        this.terrain = terrain;
        this.species = species;
        // Fixed system order (DESIGN.md §5). Cleanup (deferred destruction) runs after all systems.
        this.systems = List.of(
                new PrevTransformSystem(),
                new WanderSystem(terrain, random, species),
                new MovementSystem(terrain));
    }

    /** Generates the terrain and spawns the starting population. */
    public static World create(WorldConfig config, BiomeTable biomes, SpeciesDefinition species, long seed) {
        Random random = new Random(seed);
        Terrain terrain = TerrainGenerator.generate(config, biomes, seed, random);
        World world = new World(seed, terrain, species, random);
        world.spawnPopulation(random);
        return world;
    }

    /** Advances the simulation by one tick. */
    public void tick(int tick) {
        for (GameSystem system : systems) {
            system.update(ecs, tick);
        }
        ecs.flushDestroyed();
    }

    private void spawnPopulation(Random random) {
        int pauseMaxTicks = SpeciesDefinition.secondsToTicks(species.wanderPauseMaxSeconds());
        for (int n = 0; n < species.startingPopulation(); n++) {
            int tx = -1;
            int tz = -1;
            for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
                int x = random.nextInt(terrain.width());
                int z = random.nextInt(terrain.depth());
                if (terrain.isPassable(x, z)) {
                    tx = x;
                    tz = z;
                    break;
                }
            }
            if (tx < 0) {
                throw new IllegalStateException("World seed " + seed + " has (almost) no land to spawn creatures on");
            }
            float x = tx + random.nextFloat();
            float z = tz + random.nextFloat();

            int entity = ecs.createEntity();
            Transform transform = ecs.add(entity, new Transform());
            transform.position.set(x, terrain.heightAt(x, z), z);
            transform.yaw = random.nextFloat() * TWO_PI;
            PrevTransform prev = ecs.add(entity, new PrevTransform());
            prev.position.set(transform.position);
            prev.yaw = transform.yaw;
            ecs.add(entity, new Velocity());
            WanderState wander = ecs.add(entity, new WanderState());
            wander.waitTicks = random.nextInt(pauseMaxTicks + 1); // don't start all at once
        }
    }

    public long seed() {
        return seed;
    }

    public Terrain terrain() {
        return terrain;
    }

    public SpeciesDefinition species() {
        return species;
    }

    public EcsWorld ecs() {
        return ecs;
    }
}

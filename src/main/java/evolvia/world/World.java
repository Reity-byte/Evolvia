package evolvia.world;

import evolvia.ai.ActionContext;
import evolvia.ai.PathQueue;
import evolvia.ai.Pathfinder;
import evolvia.components.Age;
import evolvia.components.AiState;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.components.PrevTransform;
import evolvia.components.ResourceNode;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.components.Velocity;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.SpeciesDefinition;
import evolvia.systems.AgingSystem;
import evolvia.systems.AiSystem;
import evolvia.systems.MovementSystem;
import evolvia.systems.NeedsSystem;
import evolvia.systems.PathFollowingSystem;
import evolvia.systems.PathfindingSystem;
import evolvia.systems.PrevTransformSystem;
import evolvia.systems.ResourceRegrowthSystem;

import java.util.List;
import java.util.Random;

/**
 * The simulated world: terrain, resources, creatures and the systems that advance them.
 * <p>
 * Everything random comes from one {@link Random} seeded with the world seed (terrain generation,
 * then resources, then creatures, then the systems), so the same seed replays the same world.
 * The system order is defined here and nowhere else.
 */
public final class World {

    /** Cell size of the spatial grids (DESIGN.md §6). */
    public static final int GRID_CELL_SIZE = 16;

    private static final float TWO_PI = (float) (Math.PI * 2);
    private static final int SPAWN_ATTEMPTS = 10_000;
    private static final int[] DX4 = {1, -1, 0, 0};
    private static final int[] DZ4 = {0, 0, 1, -1};

    private final long seed;
    private final Terrain terrain;
    private final SpeciesDefinition species;
    private final ResourceTable resourceTable;
    private final EcsWorld ecs = new EcsWorld();
    private final SpatialGrid foodGrid;
    private final SpatialGrid waterGrid;
    private final Pathfinder pathfinder;
    private final PathQueue pathQueue = new PathQueue();
    private final DeathStats deaths = new DeathStats();
    private final PathfindingSystem pathfindingSystem;
    private final List<GameSystem> systems;
    /** Duration of each system in the last tick (for profiling / debug overlay). */
    private final long[] systemNanos;

    private World(long seed, Terrain terrain, SpeciesDefinition species, ResourceTable resourceTable, Random random) {
        this.seed = seed;
        this.terrain = terrain;
        this.species = species;
        this.resourceTable = resourceTable;
        this.foodGrid = new SpatialGrid(terrain.width(), terrain.depth(), GRID_CELL_SIZE);
        this.waterGrid = new SpatialGrid(terrain.width(), terrain.depth(), GRID_CELL_SIZE);
        this.pathfinder = new Pathfinder(terrain);
        this.pathfindingSystem = new PathfindingSystem(pathfinder, pathQueue);
        ActionContext actionContext = new ActionContext(terrain, pathfinder, pathQueue, foodGrid, waterGrid, random);
        // Fixed system order (DESIGN.md §5). Cleanup (deferred destruction) runs after all systems.
        this.systems = List.of(
                new PrevTransformSystem(),
                new NeedsSystem(),
                new AiSystem(actionContext),
                pathfindingSystem,
                new PathFollowingSystem(pathQueue),
                new MovementSystem(terrain),
                new ResourceRegrowthSystem(),
                new AgingSystem(deaths));
        this.systemNanos = new long[systems.size()];
    }

    /** Generates the terrain, places resources and spawns the starting population. */
    public static World create(WorldConfig config, BiomeTable biomes, SpeciesDefinition species,
                               ResourceTable resources, long seed) {
        Random random = new Random(seed);
        Terrain terrain = TerrainGenerator.generate(config, biomes, seed, random);
        World world = new World(seed, terrain, species, resources, random);
        world.spawnResources(random);
        world.spawnPopulation(random);
        return world;
    }

    /** Advances the simulation by one tick. */
    public void tick(int tick) {
        for (int i = 0; i < systems.size(); i++) {
            long start = System.nanoTime();
            systems.get(i).update(ecs, tick);
            systemNanos[i] = System.nanoTime() - start;
        }
        ecs.flushDestroyed();
    }

    /** Names of the systems in execution order. */
    public List<String> systemNames() {
        return systems.stream().map(s -> s.getClass().getSimpleName()).toList();
    }

    /** Time the system at {@code index} took in the last tick, in nanoseconds. */
    public long systemNanos(int index) {
        return systemNanos[index];
    }

    // ---------------------------------------------------------------- spawning

    private void spawnResources(Random random) {
        // Water: one unlimited node on every land tile next to a water tile (no randomness).
        for (int tz = 0; tz < terrain.depth(); tz++) {
            for (int tx = 0; tx < terrain.width(); tx++) {
                if (terrain.isPassable(tx, tz) && isShore(tx, tz)) {
                    addResource(resourceTable.water(), tx + 0.5f, tz + 0.5f, 0f, 0f);
                }
            }
        }
        // Food: chance per land tile scaled by fertility; regrowth scaled by fertility too.
        for (int tz = 0; tz < terrain.depth(); tz++) {
            for (int tx = 0; tx < terrain.width(); tx++) {
                if (!terrain.isPassable(tx, tz)) {
                    continue;
                }
                float fertility = terrain.fertility(tx, tz);
                for (ResourceDefinition food : resourceTable.food()) {
                    if (random.nextFloat() < food.spawnDensity() * fertility) {
                        float x = tx + 0.2f + 0.6f * random.nextFloat();
                        float z = tz + 0.2f + 0.6f * random.nextFloat();
                        float amount = food.capacity() * (0.5f + 0.5f * random.nextFloat());
                        addResource(food, x, z, amount, SpeciesDefinition.perTick(food.regrowPerSecond()) * fertility);
                    }
                }
            }
        }
    }

    private boolean isShore(int tx, int tz) {
        for (int d = 0; d < 4; d++) {
            int nx = tx + DX4[d];
            int nz = tz + DZ4[d];
            if (terrain.inBounds(nx, nz) && terrain.biome(nx, nz).water()) {
                return true;
            }
        }
        return false;
    }

    private void addResource(ResourceDefinition type, float x, float z, float amount, float regrowPerTick) {
        int entity = ecs.createEntity();
        Transform transform = ecs.add(entity, new Transform());
        transform.position.set(x, terrain.heightAt(x, z), z);
        ecs.add(entity, new ResourceNode(type, amount, regrowPerTick));
        resourceGrid(type.kind()).insert(entity, x, z);
    }

    private void spawnPopulation(Random random) {
        int lifespanMin = SpeciesDefinition.secondsToTicks(species.lifespanMinSeconds());
        int lifespanMax = SpeciesDefinition.secondsToTicks(species.lifespanMaxSeconds());
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
            ecs.add(entity, new SpeciesRef(species));

            // Start with varied needs and ages so the population does not act (or die) in lockstep.
            Needs needs = ecs.add(entity, new Needs());
            needs.hunger = 0.3f * random.nextFloat();
            needs.thirst = 0.3f * random.nextFloat();
            needs.energy = 0.7f + 0.3f * random.nextFloat();
            ecs.add(entity, new Health(species.maxHealth()));
            Age age = ecs.add(entity, new Age());
            age.maxAgeTicks = lifespanMin + (lifespanMax > lifespanMin ? random.nextInt(lifespanMax - lifespanMin + 1) : 0);
            age.ageTicks = random.nextInt(age.maxAgeTicks / 2 + 1);
            ecs.add(entity, new AiState());
        }
    }

    // ---------------------------------------------------------------- queries and debug

    /** Creature closest to (x, z) within {@code maxDistance}, or -1. For selecting with the mouse. */
    public int nearestCreature(float x, float z, float maxDistance) {
        ComponentStore<SpeciesRef> creatures = ecs.store(SpeciesRef.class);
        ComponentStore<Transform> transforms = ecs.store(Transform.class);
        int best = -1;
        float bestSq = maxDistance * maxDistance;
        for (int i = 0; i < creatures.size(); i++) {
            Transform t = transforms.get(creatures.entityAt(i));
            float dx = t.position.x - x;
            float dz = t.position.z - z;
            float dSq = dx * dx + dz * dz;
            if (dSq <= bestSq) {
                bestSq = dSq;
                best = creatures.entityAt(i);
            }
        }
        return best;
    }

    /** Debug: empties every food node (it regrows normally afterwards). */
    public void emptyAllFood() {
        ComponentStore<ResourceNode> nodes = ecs.store(ResourceNode.class);
        for (int i = 0; i < nodes.size(); i++) {
            ResourceNode node = nodes.componentAt(i);
            if (node.type.kind() == ResourceKind.FOOD) {
                node.amount = 0f;
            }
        }
    }

    /** Total food units currently in the world. */
    public float totalFood() {
        ComponentStore<ResourceNode> nodes = ecs.store(ResourceNode.class);
        float total = 0f;
        for (int i = 0; i < nodes.size(); i++) {
            ResourceNode node = nodes.componentAt(i);
            if (node.type.kind() == ResourceKind.FOOD) {
                total += node.amount;
            }
        }
        return total;
    }

    public int creatureCount() {
        return ecs.store(SpeciesRef.class).size();
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

    public ResourceTable resourceTable() {
        return resourceTable;
    }

    public EcsWorld ecs() {
        return ecs;
    }

    /** Spatial index of the resource nodes of one kind (separate grids keep food searches from scanning water). */
    public SpatialGrid resourceGrid(ResourceKind kind) {
        return kind == ResourceKind.FOOD ? foodGrid : waterGrid;
    }

    public int resourceNodeCount() {
        return foodGrid.size() + waterGrid.size();
    }

    public Pathfinder pathfinder() {
        return pathfinder;
    }

    public PathQueue pathQueue() {
        return pathQueue;
    }

    public PathfindingSystem pathfindingSystem() {
        return pathfindingSystem;
    }

    public DeathStats deaths() {
        return deaths;
    }
}

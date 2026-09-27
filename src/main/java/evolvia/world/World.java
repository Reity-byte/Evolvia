package evolvia.world;

import evolvia.ai.ActionContext;
import evolvia.ai.Navigation;
import evolvia.ai.PathQueue;
import evolvia.ai.Pathfinder;
import evolvia.components.AiState;
import evolvia.components.Believer;
import evolvia.components.Fear;
import evolvia.components.GroupMember;
import evolvia.components.PrevTransform;
import evolvia.components.Velocity;
import evolvia.data.DataLoader;
import evolvia.god.GodConfig;
import evolvia.god.GodPowers;
import evolvia.systems.FaithSystem;
import evolvia.systems.GodPowerSystem;
import evolvia.systems.GroupSystem;
import evolvia.components.ResourceNode;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.EvolutionConditions;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.systems.AgingSystem;
import evolvia.systems.AiSystem;
import evolvia.systems.EvolutionSystem;
import evolvia.systems.MovementSystem;
import evolvia.systems.NeedsSystem;
import evolvia.systems.PathFollowingSystem;
import evolvia.systems.PathfindingSystem;
import evolvia.systems.PrevTransformSystem;
import evolvia.systems.ReproductionSystem;
import evolvia.systems.ResourceRegrowthSystem;
import evolvia.systems.SpatialIndexSystem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * The simulated world: terrain, resources, the player's species and its creatures, and the systems
 * that advance them.
 * <p>
 * Everything random comes from one {@link SimRandom} seeded with the world seed (terrain generation,
 * then resources, then creatures, then the systems), so the same seed replays the same world.
 * The system order is defined here and nowhere else.
 */
public final class World implements EvolutionConditions {

    /** Cell size of the spatial grids (DESIGN.md §6). */
    public static final int GRID_CELL_SIZE = 16;

    private static final float TWO_PI = (float) (Math.PI * 2);
    private static final int SPAWN_ATTEMPTS = 10_000;
    private static final int[] DX4 = {1, -1, 0, 0};
    private static final int[] DZ4 = {0, 0, 1, -1};

    private final long seed;
    private final Terrain terrain;
    private final Species species;
    private final ResourceTable resourceTable;
    private final EcsWorld ecs = new EcsWorld();
    private final SpatialGrid foodGrid;
    private final SpatialGrid waterGrid;
    private final SpatialGrid creatureGrid;
    private final Navigation navigation;
    private final PathQueue pathQueue = new PathQueue();
    private final DeathStats deaths = new DeathStats();
    private final Births births = new Births();
    private final CreatureFactory creatureFactory;
    private final PopulationHistory history = new PopulationHistory();
    private final PathfindingSystem pathfindingSystem;
    private final ReproductionSystem reproductionSystem;
    private final EvolutionSystem evolutionSystem;
    private final AgingSystem agingSystem;
    private final GodPowerSystem godPowerSystem;
    private final GodPowers godPowers;
    private final Groups groups = new Groups();
    private final SimRandom random;
    private final List<GameSystem> systems;
    /** Duration of each system in the last tick (for profiling / debug overlay). */
    private final long[] systemNanos;

    private World(long seed, Terrain terrain, Species species, ResourceTable resourceTable,
                  float shallowDepth, GodConfig godConfig, SimRandom random) {
        this.seed = seed;
        this.random = random;
        this.godPowers = new GodPowers(godConfig);
        this.terrain = terrain;
        this.species = species;
        this.resourceTable = resourceTable;
        this.foodGrid = new SpatialGrid(terrain.width(), terrain.depth(), GRID_CELL_SIZE);
        this.waterGrid = new SpatialGrid(terrain.width(), terrain.depth(), GRID_CELL_SIZE);
        this.creatureGrid = new SpatialGrid(terrain.width(), terrain.depth(), GRID_CELL_SIZE);
        this.creatureFactory = new CreatureFactory(ecs, terrain, creatureGrid, random);
        this.navigation = new Navigation(terrain, shallowDepth);
        this.pathfindingSystem = new PathfindingSystem(navigation, pathQueue);
        this.reproductionSystem = new ReproductionSystem(births, creatureFactory, terrain);
        this.evolutionSystem = new EvolutionSystem(species, reproductionSystem::maxGeneration);
        this.agingSystem = new AgingSystem(deaths, creatureGrid, this::creatureDied);
        this.godPowerSystem = new GodPowerSystem(this, godPowers);
        ActionContext actionContext = new ActionContext(terrain, navigation, pathQueue, foodGrid, waterGrid,
                creatureGrid, births, random, groups);
        // Fixed system order (DESIGN.md §5). Cleanup (deferred destruction) runs after all systems.
        this.systems = List.of(
                new PrevTransformSystem(),
                godPowerSystem,
                new NeedsSystem(terrain),
                new AiSystem(actionContext),
                pathfindingSystem,
                new PathFollowingSystem(pathQueue),
                new MovementSystem(navigation),
                new SpatialIndexSystem(creatureGrid),
                new ResourceRegrowthSystem(foodGrid),
                reproductionSystem,
                agingSystem,
                new GroupSystem(groups, creatureGrid),
                evolutionSystem,
                new FaithSystem(godPowers.faith(), godConfig.faith()));
        this.systemNanos = new long[systems.size()];
    }

    /** Generates the terrain, places resources and spawns the starting population. */
    public static World create(WorldConfig config, BiomeTable biomes, SpeciesDefinition species,
                               EvolutionTree tree, ResourceTable resources, GodConfig god, long seed) {
        SimRandom random = new SimRandom(seed);
        Terrain terrain = TerrainGenerator.generate(config, biomes, seed, random);
        World world = new World(seed, terrain, new Species(species, tree), resources, config.water().shallowDepth(), god, random);
        world.spawnResources(random);
        world.spawnPopulation(random);
        world.history.record(world.creatureCount(), world.totalFood());
        return world;
    }

    /**
     * World for a save game: the given terrain, species and random state, no entities yet. The caller adds
     * the saved entities and then calls {@link #rebuildSpatialIndex()}.
     */
    public static World restore(long seed, Terrain terrain, Species species, ResourceTable resources,
                                float shallowDepth, GodConfig god, SimRandom random) {
        return new World(seed, terrain, species, resources, shallowDepth, god, random);
    }

    /** Puts all creatures and resource nodes into the spatial grids (after loading). */
    public void rebuildSpatialIndex() {
        ComponentStore<Transform> transforms = ecs.store(Transform.class);
        ComponentStore<ResourceNode> nodes = ecs.store(ResourceNode.class);
        for (int i = 0; i < nodes.size(); i++) {
            Transform t = transforms.get(nodes.entityAt(i));
            resourceGrid(nodes.componentAt(i).type.kind()).insert(nodes.entityAt(i), t.position.x, t.position.z);
        }
        ComponentStore<SpeciesRef> creatures = ecs.store(SpeciesRef.class);
        for (int i = 0; i < creatures.size(); i++) {
            Transform t = transforms.get(creatures.entityAt(i));
            creatureGrid.insert(creatures.entityAt(i), t.position.x, t.position.z);
        }
    }

    /** State of the world's random generator (save games). */
    public SimRandom.State randomState() {
        return random.state();
    }

    public ReproductionSystem reproductionSystem() {
        return reproductionSystem;
    }

    /** Like the full {@code create} with the god powers from {@code data/powers.json}. */
    public static World create(WorldConfig config, BiomeTable biomes, SpeciesDefinition species,
                               EvolutionTree tree, ResourceTable resources, long seed) {
        return create(config, biomes, species, tree, resources, DataLoader.loadGodConfig(), seed);
    }

    /** Same as above with an empty evolution tree. */
    public static World create(WorldConfig config, BiomeTable biomes, SpeciesDefinition species,
                               ResourceTable resources, long seed) {
        return create(config, biomes, species, new EvolutionTree(List.of(), "none"), resources, seed);
    }

    /** Advances the simulation by one tick. */
    public void tick(int tick) {
        for (int i = 0; i < systems.size(); i++) {
            long start = System.nanoTime();
            systems.get(i).update(ecs, tick);
            systemNanos[i] = System.nanoTime() - start;
        }
        ecs.flushDestroyed();
        if ((tick + 1) % PopulationHistory.SAMPLE_INTERVAL_TICKS == 0) {
            history.record(creatureCount(), totalFood());
        }
    }

    /** Names of the systems in execution order. */
    public List<String> systemNames() {
        return systems.stream().map(s -> s.getClass().getSimpleName()).toList();
    }

    /** Time the system at {@code index} took in the last tick, in nanoseconds. */
    public long systemNanos(int index) {
        return systemNanos[index];
    }

    // ---------------------------------------------------------------- evolution

    /**
     * Unlocks an evolution node for the species (player action).
     *
     * @throws IllegalStateException if the node cannot be unlocked now
     */
    public void unlock(String nodeId) {
        species.unlock(nodeId, this);
    }

    @Override
    public int population() {
        return creatureCount();
    }

    @Override
    public float biomeRatio(String biomeId) {
        ComponentStore<SpeciesRef> creatures = ecs.store(SpeciesRef.class);
        if (creatures.size() == 0) {
            return 0f;
        }
        ComponentStore<Transform> transforms = ecs.store(Transform.class);
        int inBiome = 0;
        for (int i = 0; i < creatures.size(); i++) {
            Transform t = transforms.get(creatures.entityAt(i));
            int tx = Math.clamp((int) Math.floor(t.position.x), 0, terrain.width() - 1);
            int tz = Math.clamp((int) Math.floor(t.position.z), 0, terrain.depth() - 1);
            if (terrain.biome(tx, tz).id().equals(biomeId)) {
                inBiome++;
            }
        }
        return inBiome / (float) creatures.size();
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
        List<ResourceDefinition> spawning = resourceTable.food().stream().filter(f -> f.spawnDensity() > 0).toList();
        for (int tz = 0; tz < terrain.depth(); tz++) {
            for (int tx = 0; tx < terrain.width(); tx++) {
                if (!terrain.isPassable(tx, tz)) {
                    continue;
                }
                float fertility = terrain.fertility(tx, tz);
                for (ResourceDefinition food : spawning) {
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

    private int addResource(ResourceDefinition type, float x, float z, float amount, float regrowPerTick) {
        int entity = ecs.createEntity();
        Transform transform = ecs.add(entity, new Transform());
        transform.position.set(x, Navigation.groundHeight(terrain, x, z), z);
        ecs.add(entity, new ResourceNode(type, amount, regrowPerTick));
        resourceGrid(type.kind()).insert(entity, x, z);
        return entity;
    }

    /** A creature died: its herd may need a new leader, and it leaves a carcass. */
    private void creatureDied(int entity, float x, float z) {
        GroupMember member = ecs.get(entity, GroupMember.class);
        if (member != null) {
            groups.died(entity, member.group);
        }
        leaveCarcass(entity, x, z);
    }

    /** A dead creature leaves a carcass (if resources.json defines one), which then decays. */
    private void leaveCarcass(int entity, float x, float z) {
        ResourceDefinition carcass = resourceTable.onDeath();
        if (carcass != null) {
            addResource(carcass, x, z, carcass.capacity(), 0f);
        }
    }

    /**
     * Starting population: with a spawn radius, a group around one random land point that has food and
     * water nearby (DESIGN.md §2: a small population of one species); with radius 0, spread over all land.
     * Ages, needs and reproduction cooldowns are varied so the population does not act in lockstep.
     */
    private void spawnPopulation(Random random) {
        SpeciesDefinition stats = species.stats();
        SpeciesDefinition.Population population = stats.population();
        float radius = population.spawnRadius();
        float centerX = 0f;
        float centerZ = 0f;
        int region = -1;
        if (radius > 0f) {
            for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
                float x = random.nextInt(terrain.width()) + 0.5f;
                float z = random.nextInt(terrain.depth()) + 0.5f;
                if (navigation.land().regionAt(x, z) < 0) {
                    continue;
                }
                centerX = x;
                centerZ = z;
                region = navigation.land().regionAt(x, z);
                if (foodGrid.nearest(x, z, radius, e -> true) >= 0 && waterGrid.nearest(x, z, radius, e -> true) >= 0) {
                    break; // good spot: food and water within the group's area
                }
            }
            if (region < 0) {
                throw new IllegalStateException("World seed " + seed + " has (almost) no land to spawn creatures on");
            }
        }

        int youngestLifespan = SpeciesDefinition.secondsToTicks(stats.lifespanMinSeconds());
        int cooldown = SpeciesDefinition.secondsToTicks(stats.reproduction().cooldownSeconds());
        for (int n = 0; n < population.starting(); n++) {
            float x = -1f;
            float z = -1f;
            for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
                float cx;
                float cz;
                if (radius > 0f) {
                    float angle = random.nextFloat() * TWO_PI;
                    float distance = radius * (float) Math.sqrt(random.nextFloat());
                    cx = centerX + (float) Math.sin(angle) * distance;
                    cz = centerZ + (float) Math.cos(angle) * distance;
                } else {
                    cx = random.nextInt(terrain.width()) + random.nextFloat();
                    cz = random.nextInt(terrain.depth()) + random.nextFloat();
                }
                int tileRegion = navigation.land().regionAt(cx, cz);
                if (tileRegion >= 0 && (region < 0 || tileRegion == region)) {
                    x = cx;
                    z = cz;
                    break;
                }
            }
            if (x < 0f) {
                x = centerX;
                z = centerZ;
            }
            creatureFactory.spawn(species, creatureFactory.randomGenome(stats), x, z,
                    random.nextInt(youngestLifespan / 2 + 1),
                    0.3f * random.nextFloat(), 0.3f * random.nextFloat(), 0.7f + 0.3f * random.nextFloat(),
                    random.nextInt(cooldown + 1));
        }
    }

    // ---------------------------------------------------------------- god powers (applied by GodPowerSystem)

    /** Fills the food nodes of the given type within the radius and adds new ones; all become divine. */
    public void abundance(float x, float z, float radius, String resourceId, int newNodes) {
        ResourceDefinition type = resourceTable.byId(resourceId);
        if (type == null || type.kind() != ResourceKind.FOOD) {
            throw new IllegalArgumentException("Abundance needs a food resource, got '" + resourceId + "'");
        }
        ComponentStore<ResourceNode> nodes = ecs.store(ResourceNode.class);
        foodGrid.forEachWithin(x, z, radius, entity -> {
            ResourceNode node = nodes.get(entity);
            if (node != null && node.type == type) {
                node.amount = type.capacity();
                node.divine = true;
            }
        });
        int placed = 0;
        for (int attempt = 0; attempt < newNodes * 10 && placed < newNodes; attempt++) {
            float angle = random.nextFloat() * TWO_PI;
            float distance = radius * (float) Math.sqrt(random.nextFloat());
            float nx = x + (float) Math.sin(angle) * distance;
            float nz = z + (float) Math.cos(angle) * distance;
            if (terrain.isPassable((int) Math.floor(nx), (int) Math.floor(nz))) {
                nodes.get(placeResource(resourceId, nx, nz)).divine = true;
                placed++;
            }
        }
    }

    /**
     * Lightning strike: kills up to {@code maxKills} creatures within {@code killRadius} (nearest first);
     * the survivors within {@code scareRadius} flee and start believing, out of fear.
     *
     * @return number of creatures killed
     */
    public int lightning(float x, float z, float killRadius, int maxKills, float scareRadius, int scareTicks,
                         float fleeDistance, int tick) {
        List<Integer> near = new ArrayList<>();
        creatureGrid.forEachWithin(x, z, scareRadius, near::add);
        ComponentStore<Transform> transforms = ecs.store(Transform.class);
        near.sort(Comparator.<Integer>comparingDouble(e -> distanceSquared(transforms.get(e), x, z))
                .thenComparingInt(e -> e));
        int killed = 0;
        for (int entity : near) {
            Transform t = transforms.get(entity);
            if (killed < maxKills && distanceSquared(t, x, z) <= killRadius * killRadius) {
                agingSystem.die(ecs, entity, DeathStats.Cause.LIGHTNING);
                killed++;
                continue;
            }
            Fear fear = ecs.get(entity, Fear.class);
            if (fear == null) {
                fear = ecs.add(entity, new Fear());
            }
            fear.fromX = x;
            fear.fromZ = z;
            fear.distance = fleeDistance;
            fear.untilTick = tick + scareTicks;
            if (ecs.get(entity, Believer.class) == null) {
                ecs.add(entity, new Believer());
            }
        }
        return killed;
    }

    private static double distanceSquared(Transform t, float x, float z) {
        double dx = t.position.x - x;
        double dz = t.position.z - z;
        return dx * dx + dz * dz;
    }

    /**
     * Raises ({@code delta} > 0) or lowers the ground in a circle, then brings the world in line: walkable
     * regions, water sources on the shore, food under water disappears, creatures in water move to land.
     */
    public void changeTerrain(float x, float z, float radius, float delta) {
        int[] tiles = terrain.adjustHeight(x, z, radius, delta);
        if (tiles == null) {
            return;
        }
        navigation.refresh();
        int x0 = Math.max(0, tiles[0] - 1);
        int z0 = Math.max(0, tiles[1] - 1);
        int x1 = Math.min(terrain.width(), tiles[2] + 1);
        int z1 = Math.min(terrain.depth(), tiles[3] + 1);
        float centerX = (x0 + x1) * 0.5f;
        float centerZ = (z0 + z1) * 0.5f;
        float reach = (float) Math.hypot(x1 - x0, z1 - z0) * 0.5f + 1f;
        ComponentStore<Transform> transforms = ecs.store(Transform.class);

        // Water sources: exactly one on every shore tile.
        List<Integer> water = new ArrayList<>();
        waterGrid.forEachWithin(centerX, centerZ, reach, water::add);
        water.sort(null); // by ID: independent of the grid's internal order (save games)
        boolean[] hasWater = new boolean[(x1 - x0) * (z1 - z0)];
        for (int entity : water) {
            Transform t = transforms.get(entity);
            int tx = (int) Math.floor(t.position.x);
            int tz = (int) Math.floor(t.position.z);
            if (tx < x0 || tz < z0 || tx >= x1 || tz >= z1) {
                continue;
            }
            if (terrain.isPassable(tx, tz) && isShore(tx, tz)) {
                hasWater[(tz - z0) * (x1 - x0) + (tx - x0)] = true;
                t.position.y = Navigation.groundHeight(terrain, t.position.x, t.position.z);
            } else {
                waterGrid.remove(entity, t.position.x, t.position.z);
                ecs.destroyEntity(entity);
            }
        }
        for (int tz = z0; tz < z1; tz++) {
            for (int tx = x0; tx < x1; tx++) {
                if (!hasWater[(tz - z0) * (x1 - x0) + (tx - x0)] && terrain.isPassable(tx, tz) && isShore(tx, tz)) {
                    addResource(resourceTable.water(), tx + 0.5f, tz + 0.5f, 0f, 0f);
                }
            }
        }

        // Food: gone under water, otherwise follows the ground.
        List<Integer> food = new ArrayList<>();
        foodGrid.forEachWithin(centerX, centerZ, reach, food::add);
        food.sort(null);
        for (int entity : food) {
            Transform t = transforms.get(entity);
            if (terrain.isPassable((int) Math.floor(t.position.x), (int) Math.floor(t.position.z))) {
                t.position.y = Navigation.groundHeight(terrain, t.position.x, t.position.z);
            } else {
                foodGrid.remove(entity, t.position.x, t.position.z);
                ecs.destroyEntity(entity);
            }
        }

        // Creatures follow the ground; whoever is now where it cannot be climbs out to the nearest walkable tile.
        List<Integer> creatures = new ArrayList<>();
        creatureGrid.forEachWithin(centerX, centerZ, reach, creatures::add);
        creatures.sort(null);
        for (int entity : creatures) {
            Transform t = transforms.get(entity);
            SpeciesRef ref = ecs.get(entity, SpeciesRef.class);
            Pathfinder space = ref != null ? navigation.forSpecies(ref.species) : navigation.land();
            if (!space.isWalkable((int) Math.floor(t.position.x), (int) Math.floor(t.position.z))) {
                int[] tile = nearestWalkable(space, (int) Math.floor(t.position.x), (int) Math.floor(t.position.z));
                if (tile != null) {
                    // Moved at once (also in the grid and without interpolation), so it works while paused too.
                    creatureGrid.move(entity, t.position.x, t.position.z, tile[0] + 0.5f, tile[1] + 0.5f);
                    t.position.x = tile[0] + 0.5f;
                    t.position.z = tile[1] + 0.5f;
                    PrevTransform prev = ecs.get(entity, PrevTransform.class);
                    if (prev != null) {
                        prev.position.set(t.position.x, navigation.groundHeight(t.position.x, t.position.z), t.position.z);
                    }
                }
                AiState ai = ecs.get(entity, AiState.class);
                if (ai != null && ai.pathStatus != AiState.PathStatus.NONE) {
                    ai.path = null;
                    ai.pathStatus = AiState.PathStatus.FAILED; // the current action re-plans
                }
                Velocity velocity = ecs.get(entity, Velocity.class);
                if (velocity != null) {
                    velocity.speed = 0f;
                }
            }
            t.position.y = navigation.groundHeight(t.position.x, t.position.z);
        }
    }

    /** Nearest tile the pathfinder can walk on, searching in growing squares, or null. */
    private static int[] nearestWalkable(Pathfinder space, int tx, int tz) {
        for (int r = 1; r <= 32; r++) {
            int[] best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r || !space.isWalkable(tx + dx, tz + dz)) {
                        continue;
                    }
                    int d = dx * dx + dz * dz;
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = new int[]{tx + dx, tz + dz};
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /** Applies queued god powers without advancing the simulation (while paused). */
    public void applyGodPowersNow(int nextTick) {
        godPowerSystem.applyQueued(nextTick);
        ecs.flushDestroyed();
    }

    /** The herds (phase 9a). */
    public Groups groups() {
        return groups;
    }

    public GodPowers godPowers() {
        return godPowers;
    }

    /** Number of believing creatures. */
    public int believers() {
        return ecs.store(Believer.class).size();
    }

    // ---------------------------------------------------------------- queries and debug

    /** Creature closest to (x, z) within {@code maxDistance}, or -1. For selecting with the mouse. */
    public int nearestCreature(float x, float z, float maxDistance) {
        return creatureGrid.nearest(x, z, maxDistance, e -> true);
    }

    /**
     * Places a full resource node of the given type at (x, z), e.g. for the "Abundance" god power or tests.
     *
     * @return the new node's entity
     * @throws IllegalArgumentException for an unknown resource id
     */
    public int placeResource(String resourceId, float x, float z) {
        ResourceDefinition type = resourceTable.byId(resourceId);
        if (type == null) {
            throw new IllegalArgumentException("Unknown resource '" + resourceId + "'");
        }
        int tx = Math.clamp((int) Math.floor(x), 0, terrain.width() - 1);
        int tz = Math.clamp((int) Math.floor(z), 0, terrain.depth() - 1);
        float regrow = type.decays() ? 0f : SpeciesDefinition.perTick(type.regrowPerSecond()) * terrain.fertility(tx, tz);
        return addResource(type, x, z, type.capacity(), regrow);
    }

    /** Debug: empties every food node (plants regrow normally afterwards). */
    public void emptyAllFood() {
        ComponentStore<ResourceNode> nodes = ecs.store(ResourceNode.class);
        for (int i = 0; i < nodes.size(); i++) {
            ResourceNode node = nodes.componentAt(i);
            if (node.type.kind() == ResourceKind.FOOD && !node.type.decays()) {
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

    /** The player's species (shared evolution state and current stats). */
    public Species species() {
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

    public Navigation navigation() {
        return navigation;
    }

    public PathQueue pathQueue() {
        return pathQueue;
    }

    public PathfindingSystem pathfindingSystem() {
        return pathfindingSystem;
    }

    public EvolutionSystem evolutionSystem() {
        return evolutionSystem;
    }

    public DeathStats deaths() {
        return deaths;
    }

    public Births births() {
        return births;
    }

    public PopulationHistory history() {
        return history;
    }

    /** Highest generation born so far (0 = only the starting population). */
    public int maxGeneration() {
        return reproductionSystem.maxGeneration();
    }

    public SpatialGrid creatureGrid() {
        return creatureGrid;
    }
}

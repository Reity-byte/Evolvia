package evolvia.world;

import evolvia.systems.TribeSystem;
import evolvia.ai.ActionContext;
import evolvia.ai.Navigation;
import evolvia.ai.PathQueue;
import evolvia.ai.Pathfinder;
import evolvia.components.Age;
import evolvia.components.AiState;
import evolvia.components.Believer;
import evolvia.components.Fear;
import evolvia.components.GroupMember;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.components.Reproduction;
import evolvia.components.PrevTransform;
import evolvia.components.Velocity;
import evolvia.data.DataLoader;
import evolvia.god.GodConfig;
import evolvia.god.GodPowers;
import evolvia.systems.FaithSystem;
import evolvia.systems.GodPowerSystem;
import evolvia.systems.GroupSystem;
import evolvia.systems.MilestoneSystem;
import evolvia.components.ResourceNode;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.components.Sick;
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
import evolvia.systems.NatureSystem;

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
    /** Trees and rocks (phase 9g). */
    private final SpatialGrid materialGrid;
    /** Rules of the tribe's work (phase 9g). */
    private final Tribe.Config tribe = DataLoader.loadTribe();
    /** The tribe's buildings and mood (phase 9h). */
    private final Settlement settlement;
    private final TribeSystem tribeSystem;
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
    private final Milestones milestones = new Milestones(DataLoader.loadMilestones());
    private final Refuges refuges = new Refuges(DataLoader.loadRefuges());
    /** Wild game and the rules of hunting (phase 9f). */
    private final Wildlife.Config wildlife = DataLoader.loadAnimals();
    private final WorldClock clock;
    private final Nature nature;
    /** Tick being simulated (or last simulated), for the clock and milestones. */
    private int currentTick;
    private final SimRandom random;
    private final List<GameSystem> systems;
    /** Duration of each system in the last tick (for profiling / debug overlay). */
    private final long[] systemNanos;

    private World(long seed, Terrain terrain, Species species, ResourceTable resourceTable,
                  float shallowDepth, WorldConfig.TimeSettings time, GodConfig godConfig, SimRandom random) {
        this.seed = seed;
        this.clock = new WorldClock(time);
        this.nature = new Nature(DataLoader.loadNature(), clock, terrain);
        this.random = random;
        this.godPowers = new GodPowers(godConfig);
        this.settlement = new Settlement(tribe, godPowers.faith());
        this.terrain = terrain;
        this.species = species;
        this.resourceTable = resourceTable;
        this.foodGrid = new SpatialGrid(terrain.width(), terrain.depth(), GRID_CELL_SIZE);
        this.waterGrid = new SpatialGrid(terrain.width(), terrain.depth(), GRID_CELL_SIZE);
        this.materialGrid = new SpatialGrid(terrain.width(), terrain.depth(), GRID_CELL_SIZE);
        this.creatureGrid = new SpatialGrid(terrain.width(), terrain.depth(), GRID_CELL_SIZE);
        this.creatureFactory = new CreatureFactory(ecs, terrain, creatureGrid, random);
        this.navigation = new Navigation(terrain, shallowDepth);
        this.pathfindingSystem = new PathfindingSystem(navigation, pathQueue);
        this.reproductionSystem = new ReproductionSystem(births, creatureFactory, terrain);
        this.evolutionSystem = new EvolutionSystem(species, reproductionSystem::maxGeneration);
        this.agingSystem = new AgingSystem(deaths, creatureGrid, this::creatureDied);
        agingSystem.setNature(nature);
        this.godPowerSystem = new GodPowerSystem(this, godPowers);
        ActionContext actionContext = new ActionContext(terrain, navigation, pathQueue, foodGrid, waterGrid,
                creatureGrid, births, random, groups, clock, refuges, nature, wildlife.hunting(), materialGrid, tribe.gathering(),
                materials(), settlement);
        this.tribeSystem = new TribeSystem(groups, species, settlement, refuges, terrain, random);
        NeedsSystem needsSystem = new NeedsSystem(terrain, clock, refuges, nature);
        needsSystem.setSettlement(settlement);
        // Fixed system order (DESIGN.md §5). Cleanup (deferred destruction) runs after all systems.
        this.systems = List.of(
                new PrevTransformSystem(),
                godPowerSystem,
                new NatureSystem(this, nature, random),
                needsSystem,
                new AiSystem(actionContext),
                pathfindingSystem,
                new PathFollowingSystem(pathQueue),
                new MovementSystem(navigation),
                new SpatialIndexSystem(creatureGrid),
                new ResourceRegrowthSystem(foodGrid, nature),
                reproductionSystem,
                agingSystem,
                new GroupSystem(groups, creatureGrid, clock, refuges, species),
                evolutionSystem,
                new FaithSystem(godPowers.faith(), godConfig.faith(), this::sacredSleepers,
                        godConfig.sanctify().faithPerSleeperPerMinute(), this::shrineFaithPerMinute),
                tribeSystem,
                new MilestoneSystem(this));
        this.systemNanos = new long[systems.size()];
    }

    /** Generates the terrain, places resources and spawns the starting population. */
    public static World create(WorldConfig config, BiomeTable biomes, SpeciesDefinition species,
                               EvolutionTree tree, ResourceTable resources, GodConfig god, long seed) {
        SimRandom random = new SimRandom(seed);
        Terrain terrain = TerrainGenerator.generate(config, biomes, seed, random);
        World world = new World(seed, terrain, new Species(species, tree), resources, config.water().shallowDepth(),
                config.time(), god, random);
        world.spawnResources(random);
        world.placeRefuges(refugeRandom(seed));
        world.spawnMaterials(materialRandom(seed));
        world.spawnPopulation(random);
        world.spawnAnimals(animalRandom(seed));
        world.history.record(world.creatureCount(), world.totalFood());
        return world;
    }

    /**
     * World for a save game: the given terrain, species and random state, no entities yet. The caller adds
     * the saved entities and then calls {@link #rebuildSpatialIndex()}.
     */
    public static World restore(long seed, Terrain terrain, Species species, ResourceTable resources,
                                float shallowDepth, WorldConfig.TimeSettings time, GodConfig god, SimRandom random) {
        return new World(seed, terrain, species, resources, shallowDepth, time, god, random);
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
        currentTick = tick;
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

    /** The player's people (believers): evolution conditions and milestones count these. */
    @Override
    public int population() {
        return believers();
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

    /** Places refuges in a world loaded from a save made before refuges existed: the same as in a new world. */
    public void placeRefugesAfterLoad(long seed) {
        placeRefuges(refugeRandom(seed));
    }

    /** Places wild game in a world loaded from a save made before there was any: the same as in a new world. */
    public void placeAnimalsAfterLoad(long seed) {
        spawnAnimals(animalRandom(seed));
    }

    /** Places trees and rocks in a world loaded from a save made before there were any. */
    public void placeMaterialsAfterLoad(long seed) {
        spawnMaterials(materialRandom(seed));
    }

    private static Random materialRandom(long seed) {
        return new Random(seed ^ 0x5707eL);
    }

    /** Trees and rocks (phase 9g): a chance per land tile by biome, with their own generator. */
    private void spawnMaterials(Random random) {
        List<ResourceDefinition> materials = resourceTable.materials();
        if (materials.isEmpty()) {
            return;
        }
        for (int tz = 0; tz < terrain.depth(); tz++) {
            for (int tx = 0; tx < terrain.width(); tx++) {
                if (!terrain.isPassable(tx, tz)) {
                    continue;
                }
                String biome = terrain.biome(tx, tz).id();
                for (ResourceDefinition material : materials) {
                    if (random.nextFloat() < material.biomeDensity().getOrDefault(biome, 0f)) {
                        float x = tx + 0.2f + 0.6f * random.nextFloat();
                        float z = tz + 0.2f + 0.6f * random.nextFloat();
                        addResource(material, x, z, material.capacity(), SpeciesDefinition.perTick(material.regrowPerSecond()));
                        break; // one per tile
                    }
                }
            }
        }
    }

    /** Rules of the tribe's work (phase 9g). */
    public Tribe.Config tribe() {
        return tribe;
    }

    /** The tribe's buildings and mood (phase 9h). */
    public Settlement settlement() {
        return settlement;
    }

    /** The tribe's herd, or null before the Tribe node. */
    public Groups.Group tribeGroup() {
        return tribeSystem.tribe();
    }

    public TribeSystem tribeSystem() {
        return tribeSystem;
    }

    /** Materials that can be gathered, sorted by id ("stone", "wood"). */
    public List<String> materials() {
        return resourceTable.materials().stream().map(ResourceDefinition::material).distinct().sorted().toList();
    }

    /**
     * The camp the bottom bar shows (phase 9i): the tribe's, else the camp of the player's herd with the biggest
     * stock, or null while the people have no camp.
     */
    public Groups.Group playerCamp() {
        Groups.Group tribeHerd = tribeSystem.tribe();
        if (tribeHerd != null && tribeHerd.hasCamp) {
            return tribeHerd;
        }
        Groups.Group best = null;
        for (Groups.Group group : groups.all()) {
            if (group.player && group.hasCamp && (best == null || group.stockTotal() > best.stockTotal())) {
                best = group;
            }
        }
        return best;
    }

    /** How much of each material a herd's camp stores (the tribe's store raises it). */
    public float stockCap(Groups.Group group) {
        return tribe.gathering().stockCap() * (group.tribe ? settlement.storageFactor() : 1f);
    }

    /** Extra faith from the shrine: a share of what the tribe's believers give. */
    private double shrineFaithPerMinute() {
        Groups.Group group = tribeSystem.tribe();
        float bonus = settlement.faithBonus();
        if (group == null || bonus <= 0f) {
            return 0;
        }
        return bonus * godPowers.config().faith().perBelieverPerMinute() * group.size;
    }

    /**
     * The god's building plan (phase 9h): a site of the given type at (x, z), near the tribe's camp; the
     * tribe builds it first once it has the materials.
     *
     * @return false if there is no tribe, the type is unknown or the spot is not free land near the camp
     */
    public boolean planBuilding(String typeId, float x, float z) {
        Groups.Group group = tribeSystem.tribe();
        Tribe.BuildingType type = tribe.building(typeId);
        if (group == null || type == null || !tribeSystem.free(x, z)
                || Math.hypot(x - group.campX, z - group.campZ) > tribe.tribe().siteRadius()[1] * 2f) {
            return false;
        }
        settlement.add(type, x, z, true);
        return true;
    }

    private static Random animalRandom(long seed) {
        return new Random(seed ^ 0xa11a1L);
    }

    /**
     * Wild game (phase 9f): the herds of every animal species in its biomes, away from the player's people
     * and from each other.
     */
    private void spawnAnimals(Random random) {
        List<float[]> homes = new ArrayList<>();
        for (Groups.Group group : groups.all()) {
            homes.add(new float[]{group.homeX, group.homeZ});
        }
        for (Species kind : wildlife.species()) {
            evolvia.evolution.Animal animal = kind.animal();
            float spacing = kind.stats().population().herdSpacing();
            for (int h = 0; h < animal.herds(); h++) {
                float[] home = null;
                for (int attempt = 0; attempt < SPAWN_ATTEMPTS && home == null; attempt++) {
                    int tx = random.nextInt(terrain.width());
                    int tz = random.nextInt(terrain.depth());
                    if (!terrain.isPassable(tx, tz) || navigation.land().regionAt(tx + 0.5f, tz + 0.5f) < 0
                            || (!animal.biomes().isEmpty() && !animal.biomes().contains(terrain.biome(tx, tz).id()))) {
                        continue;
                    }
                    boolean spaced = true;
                    for (float[] other : homes) {
                        spaced &= Math.hypot(other[0] - tx, other[1] - tz) >= spacing;
                    }
                    if (spaced) {
                        home = new float[]{tx + 0.5f, tz + 0.5f};
                    }
                }
                if (home == null) {
                    break; // no room (small map)
                }
                homes.add(home);
                int size = animal.herdSize()[0] + random.nextInt(animal.herdSize()[1] - animal.herdSize()[0] + 1);
                spawnAnimalHerd(kind, random, home, size);
            }
        }
    }

    private void spawnAnimalHerd(Species kind, Random random, float[] home, int count) {
        Groups.Group group = groups.create();
        group.species = kind;
        group.homeX = home[0];
        group.homeZ = home[1];
        int region = navigation.land().regionAt(home[0], home[1]);
        float radius = Math.max(2f, kind.stats().population().spawnRadius());
        int oldest = -1;
        for (int n = 0; n < count; n++) {
            float x = home[0];
            float z = home[1];
            for (int attempt = 0; attempt < 200; attempt++) {
                float angle = random.nextFloat() * TWO_PI;
                float distance = radius * (float) Math.sqrt(random.nextFloat());
                float cx = home[0] + (float) Math.sin(angle) * distance;
                float cz = home[1] + (float) Math.cos(angle) * distance;
                if (navigation.land().regionAt(cx, cz) == region) {
                    x = cx;
                    z = cz;
                    break;
                }
            }
            int creature = spawnCreature(kind, random, x, z);
            ecs.add(creature, new GroupMember(group.id));
            if (oldest < 0 || ecs.get(creature, Age.class).ageTicks > ecs.get(oldest, Age.class).ageTicks) {
                oldest = creature;
            }
        }
        group.leader = oldest;
        group.size = count;
    }

    /** Refuges have their own generator (from the seed), so they do not change the rest of the world. */
    private static Random refugeRandom(long seed) {
        return new Random(seed ^ 0x5eedL);
    }

    /** Places the refuges of {@code data/refuges.json} on suitable land, apart from each other (phase 9d). */
    private void placeRefuges(Random random) {
        Refuges.Config config = refuges.config();
        for (Refuges.Type type : config.types()) {
            int placed = 0;
            for (int attempt = 0; attempt < type.count() * 300 && placed < type.count(); attempt++) {
                int tx = random.nextInt(terrain.width());
                int tz = random.nextInt(terrain.depth());
                if (!terrain.isPassable(tx, tz)) {
                    continue;
                }
                float altitude = (terrain.tileHeight(tx, tz) - terrain.seaLevel()) / (terrain.maxHeight() - terrain.seaLevel());
                if (altitude < type.minAltitude()
                        || (!type.biomes().isEmpty() && !type.biomes().contains(terrain.biome(tx, tz).id()))) {
                    continue;
                }
                float x = tx + 0.5f;
                float z = tz + 0.5f;
                boolean spaced = true;
                for (Refuges.Refuge other : refuges.all()) {
                    spaced &= Math.hypot(other.x - x, other.z - z) >= config.minSpacing();
                }
                if (spaced) {
                    refuges.add(type, x, z);
                    placed++;
                }
            }
        }
    }

    /** A dead creature leaves a carcass (if resources.json defines one), which then decays. */
    private void leaveCarcass(int entity, float x, float z) {
        ResourceDefinition carcass = resourceTable.onDeath();
        if (carcass != null) {
            addResource(carcass, x, z, carcass.capacity(), 0f);
        }
    }

    /**
     * Starting population (DESIGN.md §11, 9c): the player's herd of believers around a land point with food
     * and water nearby, plus {@code wildHerds} wild herds at least {@code herdSpacing} away (on the same
     * land if possible, so they meet). With spawn radius 0 (tests) everyone is spread over all land as the
     * player's people and herds form on their own. Ages, needs and reproduction cooldowns are varied so the
     * population does not act in lockstep.
     */
    private void spawnPopulation(Random random) {
        SpeciesDefinition.Population population = species.stats().population();
        float radius = population.spawnRadius();
        if (radius <= 0f) {
            for (int n = 0; n < population.starting(); n++) {
                for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
                    float x = random.nextInt(terrain.width()) + random.nextFloat();
                    float z = random.nextInt(terrain.depth()) + random.nextFloat();
                    if (navigation.land().regionAt(x, z) >= 0) {
                        ecs.add(spawnCreature(random, x, z), new Believer());
                        break;
                    }
                }
            }
            return;
        }
        List<float[]> homes = new ArrayList<>();
        float[] home = herdSpot(random, radius, homes, 0f, null);
        if (home == null) {
            throw new IllegalStateException("World seed " + seed + " has (almost) no land to spawn creatures on");
        }
        homes.add(home);
        spawnHerd(random, home, population.starting(), radius, true);
        for (int w = 0; w < population.wildHerds(); w++) {
            float[] wild = herdSpot(random, radius, homes, population.herdSpacing(), home);
            if (wild == null) {
                break; // no room for more herds (small map)
            }
            homes.add(wild);
            spawnHerd(random, wild, population.wildHerdSize(), radius, false);
        }
    }

    /**
     * A land point for a herd at least {@code spacing} from the other homes, preferably with food and water
     * within the herd's radius; with {@code near} (the player's home) within 1.6 x spacing of it and on the
     * same land, so neighbours meet early. Null if there is no such land.
     */
    private float[] herdSpot(Random random, float radius, List<float[]> homes, float spacing, float[] near) {
        float[] fallback = null;
        float need = Math.max(radius, 10f);
        int region = near != null ? navigation.land().regionAt(near[0], near[1]) : -1;
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            float x;
            float z;
            boolean close = near != null && attempt < SPAWN_ATTEMPTS / 2;
            if (close) {
                float angle = random.nextFloat() * TWO_PI;
                float distance = spacing * (1f + 0.6f * random.nextFloat());
                x = near[0] + (float) Math.sin(angle) * distance;
                z = near[1] + (float) Math.cos(angle) * distance;
            } else {
                x = random.nextInt(terrain.width()) + 0.5f;
                z = random.nextInt(terrain.depth()) + 0.5f;
            }
            int tileRegion = navigation.land().regionAt(x, z);
            if (tileRegion < 0 || (close && tileRegion != region)) {
                continue;
            }
            boolean spaced = true;
            for (float[] other : homes) {
                spaced &= Math.hypot(other[0] - x, other[1] - z) >= spacing;
            }
            if (!spaced) {
                continue;
            }
            if (fallback == null) {
                fallback = new float[]{x, z};
            }
            if (foodGrid.nearest(x, z, need, e -> true) >= 0 && waterGrid.nearest(x, z, need, e -> true) >= 0) {
                return new float[]{x, z}; // good spot: food and water within the herd's area
            }
        }
        return fallback;
    }

    /** A herd of {@code count} creatures around {@code home}, led by its oldest member. */
    private void spawnHerd(Random random, float[] home, int count, float radius, boolean player) {
        Groups.Group group = groups.create();
        group.player = player;
        group.homeX = home[0];
        group.homeZ = home[1];
        int region = navigation.land().regionAt(home[0], home[1]);
        int oldest = -1;
        for (int n = 0; n < count; n++) {
            float x = home[0];
            float z = home[1];
            for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
                float angle = random.nextFloat() * TWO_PI;
                float distance = radius * (float) Math.sqrt(random.nextFloat());
                float cx = home[0] + (float) Math.sin(angle) * distance;
                float cz = home[1] + (float) Math.cos(angle) * distance;
                if (navigation.land().regionAt(cx, cz) == region) {
                    x = cx;
                    z = cz;
                    break;
                }
            }
            int creature = spawnCreature(random, x, z);
            ecs.add(creature, new GroupMember(group.id));
            if (player) {
                ecs.add(creature, new Believer());
            }
            if (oldest < 0 || ecs.get(creature, Age.class).ageTicks > ecs.get(oldest, Age.class).ageTicks) {
                oldest = creature;
            }
        }
        group.leader = oldest;
        group.size = count;
    }

    private int spawnCreature(Random random, float x, float z) {
        return spawnCreature(species, random, x, z);
    }

    private int spawnCreature(Species kind, Random random, float x, float z) {
        SpeciesDefinition stats = kind.stats();
        int youngestLifespan = SpeciesDefinition.secondsToTicks(stats.lifespanMinSeconds());
        int cooldown = SpeciesDefinition.secondsToTicks(stats.reproduction().cooldownSeconds());
        return creatureFactory.spawn(kind, kind.latestStage().index(), creatureFactory.randomGenome(stats), x, z,
                random.nextInt(youngestLifespan / 2 + 1),
                0.3f * random.nextFloat(), 0.3f * random.nextFloat(), 0.7f + 0.3f * random.nextFloat(),
                random.nextInt(cooldown + 1));
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
            if (!ecs.get(entity, SpeciesRef.class).species.isAnimal() && ecs.get(entity, Believer.class) == null) {
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

        // Food, trees and rocks: gone under water, otherwise follow the ground.
        for (SpatialGrid grid : List.of(foodGrid, materialGrid)) {
            List<Integer> nodes = new ArrayList<>();
            grid.forEachWithin(centerX, centerZ, reach, nodes::add);
            nodes.sort(null);
            for (int entity : nodes) {
                Transform t = transforms.get(entity);
                if (terrain.isPassable((int) Math.floor(t.position.x), (int) Math.floor(t.position.z))) {
                    t.position.y = Navigation.groundHeight(terrain, t.position.x, t.position.z);
                } else {
                    grid.remove(entity, t.position.x, t.position.z);
                    ecs.destroyEntity(entity);
                }
            }
        }

        // Creatures follow the ground; whoever is now where it cannot be climbs out to the nearest walkable tile.
        List<Integer> creatures = new ArrayList<>();
        creatureGrid.forEachWithin(centerX, centerZ, reach, creatures::add);
        creatures.sort(null);
        for (int entity : creatures) {
            Transform t = transforms.get(entity);
            SpeciesRef ref = ecs.get(entity, SpeciesRef.class);
            Pathfinder space = ref != null ? navigation.forCreature(ref) : navigation.land();
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

    /**
     * The god's hand on a creature (DESIGN.md §11, 9c). Allowed: own creatures can be moved, healed and
     * blessed; the own herd leader can also order an attack on another herd or settle the herd; wild
     * creatures can only be blessed (they start believing).
     *
     * @return false if the action is not allowed now (the creature died, is not the player's, not a leader...)
     */
    public boolean applyHand(GodPowers.HandCommand command, int tick) {
        int entity = command.entity();
        if (ecs.get(entity, SpeciesRef.class) == null) {
            return false;
        }
        boolean own = ecs.get(entity, Believer.class) != null;
        boolean animal = ecs.get(entity, SpeciesRef.class).species.isAnimal();
        GroupMember member = ecs.get(entity, GroupMember.class);
        Groups.Group group = member != null ? groups.get(member.group) : null;
        boolean leader = group != null && group.player && group.leader == entity;
        Transform t = ecs.get(entity, Transform.class);
        switch (command.action()) {
            case MOVE -> {
                if (!own) {
                    return false;
                }
                moveCreature(entity, command.x(), command.z());
            }
            case ATTACK -> {
                GroupMember theirs = ecs.get(command.target(), GroupMember.class);
                Groups.Group target = theirs != null ? groups.get(theirs.group) : null;
                if (!leader || !Groups.canAttack(group, target)) {
                    return false;
                }
                group.attackGroup = target.id;
                group.attackUntilTick = tick + SpeciesDefinition.secondsToTicks(godPowers.config().hand().attackSeconds());
            }
            case SETTLE -> {
                if (!leader) {
                    return false;
                }
                group.settled = true;
                group.homeX = t.position.x;
                group.homeZ = t.position.z;
                if (group.hasCamp) { // the camp (and its stock) moves with the herd
                    group.campX = t.position.x;
                    group.campZ = t.position.z;
                }
            }
            case HEAL -> {
                if (!own) {
                    return false;
                }
                Health health = ecs.get(entity, Health.class);
                health.hp = health.maxHp;
                Sick sick = ecs.get(entity, Sick.class);
                if (sick != null) {
                    sick.untilTick = Math.min(sick.untilTick, currentTick); // cured (and immune for a while)
                }
            }
            case BLESS -> {
                if (own) {
                    ecs.get(entity, Reproduction.class).readyAtTick = tick;
                    Needs needs = ecs.get(entity, Needs.class);
                    needs.hunger = Math.max(0f, needs.hunger - 0.3f);
                    needs.thirst = Math.max(0f, needs.thirst - 0.3f);
                } else if (animal) {
                    return false; // wild game does not believe
                } else {
                    ecs.add(entity, new Believer());
                }
            }
        }
        godPowers.recordHand(new GodPowers.HandEffect(command.action(), t.position.x, t.position.z, tick));
        return true;
    }

    /** Carries a creature to the nearest place it can stand at (x, z); it stops what it was walking to. */
    public void moveCreature(int entity, float x, float z) {
        Transform t = ecs.get(entity, Transform.class);
        SpeciesRef ref = ecs.get(entity, SpeciesRef.class);
        Pathfinder space = navigation.forCreature(ref);
        int tx = Math.clamp((int) Math.floor(x), 0, terrain.width() - 1);
        int tz = Math.clamp((int) Math.floor(z), 0, terrain.depth() - 1);
        if (!space.isWalkable(tx, tz)) {
            int[] tile = nearestWalkable(space, tx, tz);
            if (tile == null) {
                return;
            }
            x = tile[0] + 0.5f;
            z = tile[1] + 0.5f;
        }
        creatureGrid.move(entity, t.position.x, t.position.z, x, z);
        t.position.set(x, navigation.groundHeight(x, z), z);
        PrevTransform prev = ecs.get(entity, PrevTransform.class);
        if (prev != null) {
            prev.position.set(t.position);
        }
        AiState ai = ecs.get(entity, AiState.class);
        if (ai != null) {
            ai.path = null;
            ai.pathStatus = ai.pathStatus == AiState.PathStatus.NONE ? AiState.PathStatus.NONE : AiState.PathStatus.FAILED;
        }
        Velocity velocity = ecs.get(entity, Velocity.class);
        if (velocity != null) {
            velocity.speed = 0f;
        }
    }

    /** True once the player's people died out (DESIGN.md §11, 9c: game over). */
    public boolean playerDefeated() {
        return believers() == 0;
    }

    /** Applies queued god powers without advancing the simulation (while paused). */
    public void applyGodPowersNow(int nextTick) {
        godPowerSystem.applyQueued(nextTick);
        ecs.flushDestroyed();
    }

    /** Debug / tests: every creature gets the latest evolutionary stage at once (no waiting for generations). */
    public void evolveEveryone() {
        ComponentStore<SpeciesRef> creatures = ecs.store(SpeciesRef.class);
        for (int i = 0; i < creatures.size(); i++) {
            creatures.componentAt(i).stage = creatures.componentAt(i).species.latestStage().index();
        }
    }

    /** Creatures of the player's species per evolutionary stage (index = stage). */
    public int[] stageCounts() {
        int[] counts = new int[species.latestStage().index() + 1];
        ComponentStore<SpeciesRef> creatures = ecs.store(SpeciesRef.class);
        for (int i = 0; i < creatures.size(); i++) {
            if (creatures.componentAt(i).species == species) { // the player's species; wild game does not evolve
                counts[Math.clamp(creatures.componentAt(i).stage, 0, counts.length - 1)]++;
            }
        }
        return counts;
    }

    /** Seasons, weather, disease and natural disasters (phase 9e). */
    public Nature nature() {
        return nature;
    }

    /** Kills a creature now (nature: lightning in a storm). */
    public void killCreature(int entity, DeathStats.Cause cause) {
        agingSystem.die(ecs, entity, cause);
    }

    /** The tick being (or last) simulated. */
    public int tick() {
        return currentTick;
    }

    /** Restores the tick of a save game. */
    public void restoreTick(int tick) {
        currentTick = tick;
    }

    /** Day and night (phase 9d). */
    public WorldClock clock() {
        return clock;
    }

    /** Caves, groves and overhangs (phase 9d). */
    public Refuges refuges() {
        return refuges;
    }

    /**
     * Makes the refuge within {@code radius} of (x, z) a sacred place; the nearest herd of the player's people
     * settles there (phase 9d).
     *
     * @return false if there is no refuge there
     */
    public boolean sanctify(float x, float z, float radius) {
        Refuges.Refuge refuge = refuges.nearest(x, z, radius, false);
        if (refuge == null) {
            return false;
        }
        refuge.sacred = true;
        Groups.Group nearest = null;
        double best = Double.MAX_VALUE;
        for (Groups.Group group : groups.all()) {
            double d = Math.hypot(group.homeX - refuge.x, group.homeZ - refuge.z);
            if (group.player && d < best) {
                best = d;
                nearest = group;
            }
        }
        if (nearest != null) {
            nearest.settled = true;
            nearest.homeX = refuge.x;
            nearest.homeZ = refuge.z;
            if (nearest.hasCamp) {
                nearest.campX = refuge.x;
                nearest.campZ = refuge.z;
            }
        }
        return true;
    }

    /** Believers asleep in a refuge (sheltered), and of them those in a sacred place. */
    public int shelteredSleepers(boolean sacredOnly) {
        ComponentStore<Believer> store = ecs.store(Believer.class);
        int count = 0;
        for (int i = 0; i < store.size(); i++) {
            int entity = store.entityAt(i);
            Needs needs = ecs.get(entity, Needs.class);
            Transform t = ecs.get(entity, Transform.class);
            if (needs == null || t == null || !needs.sleeping) {
                continue;
            }
            Refuges.Refuge refuge = refuges.at(t.position.x, t.position.z);
            if (refuge != null && (!sacredOnly || refuge.sacred)) {
                count++;
            }
        }
        return count;
    }

    private int sacredSleepers() {
        return shelteredSleepers(true);
    }

    /** Early game goals (phase 9c). */
    public Milestones milestones() {
        return milestones;
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

    /** Creatures of the player's species (the people and wild ones); wild game is {@link #animalCount()}. */
    public int creatureCount() {
        ComponentStore<SpeciesRef> creatures = ecs.store(SpeciesRef.class);
        int count = 0;
        for (int i = 0; i < creatures.size(); i++) {
            if (creatures.componentAt(i).species == species) {
                count++;
            }
        }
        return count;
    }

    /** Wild game (phase 9f). */
    public int animalCount() {
        return ecs.store(SpeciesRef.class).size() - creatureCount();
    }

    /** Creatures of one animal species. */
    public int animalCount(String speciesId) {
        ComponentStore<SpeciesRef> creatures = ecs.store(SpeciesRef.class);
        int count = 0;
        for (int i = 0; i < creatures.size(); i++) {
            if (creatures.componentAt(i).species.id().equals(speciesId) && creatures.componentAt(i).species != species) {
                count++;
            }
        }
        return count;
    }

    /** Wild game species and the rules of hunting (phase 9f). */
    public Wildlife.Config wildlife() {
        return wildlife;
    }

    /** The player's species or a wild game species by id, or null. */
    public Species speciesById(String id) {
        return species.id().equals(id) ? species : wildlife.byId(id);
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
        return switch (kind) {
            case FOOD -> foodGrid;
            case WATER -> waterGrid;
            case MATERIAL -> materialGrid;
        };
    }

    public int resourceNodeCount() {
        return foodGrid.size() + waterGrid.size() + materialGrid.size();
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

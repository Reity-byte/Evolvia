package evolvia.save;

import evolvia.ai.ActionType;
import evolvia.ai.Path;
import evolvia.components.Age;
import evolvia.components.AiState;
import evolvia.components.Believer;
import evolvia.components.Fear;
import evolvia.components.Genome;
import evolvia.components.GroupMember;
import evolvia.components.Health;
import evolvia.components.Memory;
import evolvia.components.Needs;
import evolvia.components.PrevTransform;
import evolvia.components.Reproduction;
import evolvia.components.ResourceNode;
import evolvia.components.Sick;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.components.UnderAttack;
import evolvia.components.Velocity;
import evolvia.core.Time;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.Faith;
import evolvia.god.GodConfig;
import evolvia.god.GodPowers;
import evolvia.save.SaveData.*;
import evolvia.world.BiomeTable;
import evolvia.world.DeathStats;
import evolvia.world.Groups;
import evolvia.world.Refuges;
import evolvia.world.WorldConfig;
import evolvia.world.PopulationHistory;
import evolvia.world.ResourceDefinition;
import evolvia.world.ResourceTable;
import evolvia.world.SimRandom;
import evolvia.world.Terrain;
import evolvia.world.World;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a {@link World} to {@link SaveData} and back (DESIGN.md §11, phase 8). Everything that
 * influences how the simulation continues is saved, so a loaded world replays exactly like the original.
 * No file handling here (see {@link SaveManager}).
 */
public final class WorldCodec {

    /**
     * 2: herds (phase 9a); 3: evolutionary stage per creature (generational evolution); 4: herd owner, home,
     * attack orders, fights, milestones (phase 9c); 5: refuges (phase 9d). Older saves load without herds (they
     * form again; wild or not follows the believers), with every creature at the latest stage, no milestones
     * reached and freshly placed refuges; 6: weather, disasters, disease, carcass age (phase 9e), older saves start
     * in clear weather without disasters; 7: the species of every creature and herd (wild game, phase 9f), older
     * saves get the game of a new world with the same seed.
     */
    public static final int SAVE_VERSION = 7;

    /** Component types this codec saves; any other non-empty store is an error (would be lost silently). */
    private static final Set<Class<?>> SAVED = Set.of(Transform.class, PrevTransform.class, Velocity.class,
            SpeciesRef.class, Genome.class, Needs.class, Health.class, Age.class, Reproduction.class, AiState.class,
            Memory.class, ResourceNode.class, Believer.class, Fear.class, GroupMember.class, UnderAttack.class, Sick.class);

    /** Game data a save is loaded against (the current definitions). */
    public record GameData(float shallowDepth, WorldConfig.TimeSettings time, BiomeTable biomes, SpeciesDefinition species,
                           EvolutionTree tree, ResourceTable resources, GodConfig god) {
    }

    /** A loaded world plus the non-simulation state stored with it. */
    public record Loaded(World world, long tick, Time.Speed speed, View view, List<String> skippedNodes) {
    }

    private WorldCodec() {
    }

    // ---------------------------------------------------------------- save

    /**
     * Snapshot of the world between two ticks.
     *
     * @param tick number of the next tick to run
     */
    public static SaveData snapshot(World world, String name, long tick, Time.Speed speed, View view) {
        EcsWorld ecs = world.ecs();
        for (Class<?> type : ecs.componentTypes()) {
            if (!SAVED.contains(type) && ecs.store(type).size() > 0) {
                throw new IllegalStateException("Component " + type.getSimpleName() + " is not saved (add it to WorldCodec)");
            }
        }
        Species species = world.species();
        Meta meta = new Meta(name, OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                species.stats().name(), world.population(), world.maxGeneration(), tick);

        Faith faith = world.godPowers().faith();
        GodData god = new GodData(
                new FaithData(faith.points(), faith.earned(), faith.perMinute(), faith.believers(), faith.alignment(),
                        faith.kindActs(), faith.cruelActs()),
                new ArrayList<>(world.godPowers().rains()), world.godPowers().recentStrikes(),
                new ArrayList<>(world.godPowers().queued()), new ArrayList<>(world.godPowers().queuedHand()));

        Map<String, Integer> deaths = new LinkedHashMap<>();
        for (DeathStats.Cause cause : DeathStats.Cause.values()) {
            deaths.put(cause.name(), world.deaths().count(cause));
        }
        PopulationHistory history = world.history();
        int[] historyPopulation = new int[history.size()];
        float[] historyFood = new float[history.size()];
        for (int i = 0; i < history.size(); i++) {
            historyPopulation[i] = history.population(i);
            historyFood[i] = history.food(i);
        }
        StatsData stats = new StatsData(deaths, world.births().total(), world.maxGeneration(),
                world.evolutionSystem().lastGeneration(), world.evolutionSystem().pointsPerMinute(),
                historyPopulation, historyFood);

        return new SaveData(SAVE_VERSION, meta, world.seed(), tick, speed.name(), view, world.randomState(),
                terrain(world.terrain().snapshot()),
                new SpeciesData(species.base().id(), species.points(), species.pointsEarned(), List.copyOf(species.unlockedNodes())),
                god, stats, ecs(ecs), world.pathQueue().toArray(), groups(world.groups()),
                List.copyOf(world.milestones().completed()),
                world.refuges().all().stream().map(r -> new RefugeData(r.type.id(), r.x, r.z, r.sacred)).toList(),
                world.nature().state((int) tick));
    }

    private static TerrainData terrain(Terrain.Snapshot t) {
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new LinkedHashMap<>();
        byte[] indices = new byte[t.biomes().length];
        for (int i = 0; i < indices.length; i++) {
            Integer id = index.get(t.biomes()[i]);
            if (id == null) {
                id = palette.size();
                index.put(t.biomes()[i], id);
                palette.add(t.biomes()[i]);
            }
            indices[i] = (byte) (int) id;
        }
        if (palette.size() > 256) {
            throw new IllegalStateException("Too many biomes for a save (" + palette.size() + ")");
        }
        return new TerrainData(t.seed(), t.width(), t.depth(), t.seaLevel(), t.maxHeight(), t.altitudeCooling(),
                encode(t.cornerHeights()), encode(t.temperature()), encode(t.baseTemperature()), encode(t.moisture()),
                palette, Base64.getEncoder().encodeToString(indices));
    }

    private static EcsData ecs(EcsWorld ecs) {
        EcsWorld.IdState ids = ecs.idState();
        return new EcsData(ids.nextId(), ids.alive(), ids.free(),
                list(ecs.store(Transform.class), (e, t) -> new TransformData(e, t.position.x, t.position.y, t.position.z, t.yaw)),
                list(ecs.store(PrevTransform.class), (e, t) -> new TransformData(e, t.position.x, t.position.y, t.position.z, t.yaw)),
                list(ecs.store(Velocity.class), (e, v) -> new VelocityData(e, v.dirX, v.dirZ, v.speed, v.blocked)),
                entities(ecs.store(SpeciesRef.class)),
                list(ecs.store(Genome.class), (e, g) -> new GenomeData(e, g.size, g.speed, g.tint, g.generation)),
                list(ecs.store(Needs.class), (e, n) -> new NeedsData(e, n.hunger, n.thirst, n.energy, n.exposure, n.sleeping)),
                list(ecs.store(Health.class), (e, h) -> new HealthData(e, h.hp, h.maxHp)),
                list(ecs.store(Age.class), (e, a) -> new AgeData(e, a.ageTicks, a.maxAgeTicks)),
                list(ecs.store(Reproduction.class), (e, r) -> new ReproductionData(e, r.readyAtTick, r.offspring)),
                list(ecs.store(AiState.class), WorldCodec::ai),
                list(ecs.store(Memory.class), (e, m) -> new MemoryData(e, m.knowsWater, m.waterX, m.waterZ, m.knowsFood, m.foodX, m.foodZ)),
                list(ecs.store(ResourceNode.class), (e, r) -> new ResourceData(e, r.type.id(), r.amount, r.regrowPerTick, r.divine, r.ageTicks)),
                entities(ecs.store(Believer.class)),
                list(ecs.store(Fear.class), (e, f) -> new FearData(e, f.fromX, f.fromZ, f.distance, f.untilTick)),
                list(ecs.store(GroupMember.class), (e, m) -> new GroupMemberData(e, m.group, m.farTicks)),
                stages(ecs.store(SpeciesRef.class)),
                list(ecs.store(UnderAttack.class), (e, a) -> new UnderAttackData(e, a.attacker, a.untilTick)),
                list(ecs.store(Sick.class), (e, s) -> new SickData(e, s.untilTick, s.immuneUntilTick)),
                speciesIds(ecs.store(SpeciesRef.class)));
    }

    private static List<String> speciesIds(ComponentStore<SpeciesRef> creatures) {
        List<String> ids = new ArrayList<>(creatures.size());
        for (int i = 0; i < creatures.size(); i++) {
            ids.add(creatures.componentAt(i).species.id());
        }
        return ids;
    }

    private static int[] stages(ComponentStore<SpeciesRef> creatures) {
        int[] stages = new int[creatures.size()];
        for (int i = 0; i < stages.length; i++) {
            stages[i] = creatures.componentAt(i).stage;
        }
        return stages;
    }

    private static GroupsData groups(Groups groups) {
        List<GroupData> list = new ArrayList<>();
        for (Groups.Group g : groups.all()) {
            list.add(new GroupData(g.id, g.leader, g.size, g.player, g.homeX, g.homeZ, g.settled, g.hunger, g.attackGroup,
                    g.attackUntilTick, g.shelter, g.knowsWater, g.waterX, g.waterZ, g.knowsFood, g.foodX, g.foodZ,
                    g.species != null ? g.species.id() : null));
        }
        return new GroupsData(groups.nextId(), list, groups.playerVictories());
    }

    private static AiData ai(int e, AiState ai) {
        PathData path = null;
        if (ai.path != null) {
            float[] xs = new float[ai.path.length()];
            float[] zs = new float[ai.path.length()];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = ai.path.x(i);
                zs[i] = ai.path.z(i);
            }
            path = new PathData(xs, zs, ai.path.nextIndex());
        }
        Map<String, Integer> cooldowns = new LinkedHashMap<>();
        for (ActionType type : ActionType.values()) {
            if (ai.cooldownUntilTick[type.ordinal()] != 0) {
                cooldowns.put(type.name(), ai.cooldownUntilTick[type.ordinal()]);
            }
        }
        return new AiData(e, ai.action != null ? ai.action.name() : null, ai.score, ai.actionTicks, ai.targetEntity,
                ai.targetX, ai.targetZ, ai.waitTicks, ai.pathStatus.name(), path, ai.pathRetries, cooldowns);
    }

    private interface Converter<C, D> {
        D convert(int entity, C component);
    }

    private static <C, D> List<D> list(ComponentStore<C> store, Converter<C, D> converter) {
        List<D> list = new ArrayList<>(store.size());
        for (int i = 0; i < store.size(); i++) {
            list.add(converter.convert(store.entityAt(i), store.componentAt(i)));
        }
        return list;
    }

    private static int[] entities(ComponentStore<?> store) {
        int[] ids = new int[store.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = store.entityAt(i);
        }
        return ids;
    }

    // ---------------------------------------------------------------- load

    /**
     * Rebuilds a world from a save against the current game data.
     *
     * @throws SaveException if the save is from a newer version or does not fit the game data
     */
    public static Loaded restore(SaveData save, GameData data) {
        if (save.saveVersion() > SAVE_VERSION) {
            throw new SaveException("Save je z novější verze hry (verze savu " + save.saveVersion()
                    + ", tahle hra umí nejvýš " + SAVE_VERSION + ")");
        }
        if (save.terrain() == null || save.species() == null || save.ecs() == null || save.random() == null) {
            throw new SaveException("Save je neúplný");
        }
        try {
            Terrain terrain = Terrain.restore(terrain(save.terrain()), data.biomes());
            Species species = new Species(data.species(), data.tree());
            List<String> skipped = species.restore(save.species().points(), save.species().pointsEarned(), save.species().unlocked());
            World world = World.restore(save.seed(), terrain, species, data.resources(), data.shallowDepth(), data.time(),
                    data.god(), SimRandom.restore(save.random()));
            world.restoreTick((int) save.tick());
            if (save.nature() != null) {
                world.nature().restore(save.nature());
            }
            if (save.refuges() != null) {
                world.refuges().clear();
                for (RefugeData d : save.refuges()) {
                    Refuges.Type type = world.refuges().type(d.type());
                    if (type != null) {
                        world.refuges().add(type, d.x(), d.z()).sacred = d.sacred();
                    }
                }
            } else {
                world.placeRefugesAfterLoad(save.seed());
            }
            restoreEcs(world, save.ecs(), species, data.resources());
            world.rebuildSpatialIndex();
            for (int entity : save.pathQueue()) {
                world.pathQueue().add(entity);
            }
            restoreStats(world, save.stats());
            restoreGod(world.godPowers(), save.god());
            restoreGroups(world, save.groups());
            if (save.ecs().creatureSpecies() == null) {
                world.placeAnimalsAfterLoad(save.seed()); // a save from before wild game
            }
            if (save.milestones() != null) {
                world.milestones().restore(save.milestones());
            }
            Time.Speed speed = save.speed() != null ? Time.Speed.valueOf(save.speed()) : Time.Speed.NORMAL;
            return new Loaded(world, save.tick(), speed, save.view(), skipped);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new SaveException("Save je poškozený nebo nepasuje k této verzi hry: " + e.getMessage(), e);
        }
    }

    private static Terrain.Snapshot terrain(TerrainData t) {
        byte[] indices = Base64.getDecoder().decode(t.biomeIndices());
        String[] biomes = new String[indices.length];
        for (int i = 0; i < indices.length; i++) {
            biomes[i] = t.biomePalette().get(indices[i] & 0xFF);
        }
        return new Terrain.Snapshot(t.seed(), t.width(), t.depth(), t.seaLevel(), t.maxHeight(), t.altitudeCooling(),
                decode(t.cornerHeights()), decode(t.temperature()), decode(t.baseTemperature()), decode(t.moisture()), biomes);
    }

    private static void restoreEcs(World world, EcsData data, Species species, ResourceTable resources) {
        EcsWorld ecs = world.ecs();
        ecs.restoreIds(new EcsWorld.IdState(data.nextId(), data.alive(), data.free()));
        for (TransformData d : data.transforms()) {
            Transform t = ecs.add(d.e(), new Transform());
            t.position.set(d.x(), d.y(), d.z());
            t.yaw = d.yaw();
        }
        for (TransformData d : data.prevTransforms()) {
            PrevTransform t = ecs.add(d.e(), new PrevTransform());
            t.position.set(d.x(), d.y(), d.z());
            t.yaw = d.yaw();
        }
        for (VelocityData d : data.velocities()) {
            Velocity v = ecs.add(d.e(), new Velocity());
            v.dirX = d.dirX();
            v.dirZ = d.dirZ();
            v.speed = d.speed();
            v.blocked = d.blocked();
        }
        for (int i = 0; i < data.creatures().length; i++) {
            Species kind = species;
            if (data.creatureSpecies() != null) {
                Species saved = world.speciesById(data.creatureSpecies().get(i));
                if (saved == null) {
                    throw new IllegalArgumentException("Unknown species '" + data.creatureSpecies().get(i) + "'");
                }
                kind = saved;
            }
            int stage = data.creatureStages() != null ? data.creatureStages()[i] : kind.latestStage().index();
            ecs.add(data.creatures()[i], new SpeciesRef(kind, Math.min(stage, kind.latestStage().index())));
        }
        for (GenomeData d : data.genomes()) {
            Genome g = ecs.add(d.e(), new Genome());
            g.size = d.size();
            g.speed = d.speed();
            g.tint = d.tint();
            g.generation = d.generation();
        }
        for (NeedsData d : data.needs()) {
            Needs n = ecs.add(d.e(), new Needs());
            n.hunger = d.hunger();
            n.thirst = d.thirst();
            n.energy = d.energy();
            n.exposure = d.exposure();
            n.sleeping = d.sleeping();
        }
        for (HealthData d : data.healths()) {
            Health h = ecs.add(d.e(), new Health(d.maxHp()));
            h.hp = d.hp();
        }
        for (AgeData d : data.ages()) {
            Age a = ecs.add(d.e(), new Age());
            a.ageTicks = d.ageTicks();
            a.maxAgeTicks = d.maxAgeTicks();
        }
        for (ReproductionData d : data.reproductions()) {
            Reproduction r = ecs.add(d.e(), new Reproduction());
            r.readyAtTick = d.readyAtTick();
            r.offspring = d.offspring();
        }
        for (AiData d : data.ai()) {
            ecs.add(d.e(), ai(d));
        }
        for (MemoryData d : data.memories()) {
            Memory m = ecs.add(d.e(), new Memory());
            m.knowsWater = d.knowsWater();
            m.waterX = d.waterX();
            m.waterZ = d.waterZ();
            m.knowsFood = d.knowsFood();
            m.foodX = d.foodX();
            m.foodZ = d.foodZ();
        }
        for (ResourceData d : data.resources()) {
            ResourceDefinition type = resources.byId(d.type());
            if (type == null) {
                throw new IllegalArgumentException("Unknown resource '" + d.type() + "'");
            }
            ResourceNode node = ecs.add(d.e(), new ResourceNode(type, d.amount(), d.regrowPerTick()));
            node.divine = d.divine();
            node.ageTicks = d.age();
        }
        for (int e : data.believers()) {
            ecs.add(e, new Believer());
        }
        if (data.groupMembers() != null) {
            for (GroupMemberData d : data.groupMembers()) {
                GroupMember m = ecs.add(d.e(), new GroupMember(d.group()));
                m.farTicks = d.farTicks();
            }
        }
        if (data.underAttacks() != null) {
            for (UnderAttackData d : data.underAttacks()) {
                UnderAttack a = ecs.add(d.e(), new UnderAttack());
                a.attacker = d.attacker();
                a.untilTick = d.untilTick();
            }
        }
        if (data.sick() != null) {
            for (SickData d : data.sick()) {
                Sick s = ecs.add(d.e(), new Sick());
                s.untilTick = d.untilTick();
                s.immuneUntilTick = d.immuneUntilTick();
            }
        }
        for (FearData d : data.fears()) {
            Fear f = ecs.add(d.e(), new Fear());
            f.fromX = d.fromX();
            f.fromZ = d.fromZ();
            f.distance = d.distance();
            f.untilTick = d.untilTick();
        }
    }

    private static AiState ai(AiData d) {
        AiState ai = new AiState();
        ai.action = d.action() != null ? ActionType.valueOf(d.action()) : null;
        ai.score = d.score();
        ai.actionTicks = d.actionTicks();
        ai.targetEntity = d.targetEntity();
        ai.targetX = d.targetX();
        ai.targetZ = d.targetZ();
        ai.waitTicks = d.waitTicks();
        ai.pathStatus = AiState.PathStatus.valueOf(d.pathStatus());
        ai.path = d.path() != null ? new Path(d.path().xs(), d.path().zs(), d.path().next()) : null;
        ai.pathRetries = d.pathRetries();
        if (d.cooldownUntilTick() != null) {
            for (Map.Entry<String, Integer> entry : d.cooldownUntilTick().entrySet()) {
                ai.cooldownUntilTick[ActionType.valueOf(entry.getKey()).ordinal()] = entry.getValue();
            }
        }
        return ai;
    }

    private static void restoreStats(World world, StatsData stats) {
        if (stats == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : stats.deaths().entrySet()) {
            world.deaths().restore(DeathStats.Cause.valueOf(entry.getKey()), entry.getValue());
        }
        world.births().restoreTotal(stats.births());
        world.reproductionSystem().restoreMaxGeneration(stats.maxGeneration());
        world.evolutionSystem().restore(stats.lastGeneration(), stats.pointsPerMinute());
        world.history().clear();
        for (int i = 0; i < stats.historyPopulation().length; i++) {
            world.history().record(stats.historyPopulation()[i], stats.historyFood()[i]);
        }
    }

    private static void restoreGroups(World world, GroupsData data) {
        Groups groups = world.groups();
        if (data == null) {
            return; // save version 1: herds form again
        }
        for (GroupData d : data.groups()) {
            Groups.Group g = new Groups.Group(d.id());
            g.player = d.player();
            g.species = d.species() != null ? world.speciesById(d.species()) : null;
            if (g.species == world.species()) {
                g.species = null; // the player's species
            }
            g.homeX = d.homeX();
            g.homeZ = d.homeZ();
            g.settled = d.settled();
            g.hunger = d.hunger();
            g.attackGroup = d.attackGroup();
            g.attackUntilTick = d.attackUntilTick();
            g.shelter = d.shelter();
            g.leader = d.leader();
            g.size = d.size();
            g.knowsWater = d.knowsWater();
            g.waterX = d.waterX();
            g.waterZ = d.waterZ();
            g.knowsFood = d.knowsFood();
            g.foodX = d.foodX();
            g.foodZ = d.foodZ();
            groups.restore(g, data.nextId());
        }
        groups.restoreVictories(data.playerVictories());
    }

    private static void restoreGod(GodPowers powers, GodData god) {
        if (god == null) {
            return;
        }
        FaithData f = god.faith();
        powers.faith().restore(f.points(), f.earned(), f.perMinute(), f.believers(), f.alignment(), f.kindActs(), f.cruelActs());
        powers.rains().addAll(god.rains());
        god.strikes().forEach(powers::recordStrike);
        god.queue().forEach(powers::restoreQueued);
        if (god.handQueue() != null) {
            god.handQueue().forEach(powers::request);
        }
    }

    // ---------------------------------------------------------------- arrays

    static String encode(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(values);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    static float[] decode(String base64) {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[buffer.remaining() / Float.BYTES];
        buffer.asFloatBuffer().get(values);
        return values;
    }
}

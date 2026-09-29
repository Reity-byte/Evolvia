package evolvia.save;

import evolvia.god.GodPowers;
import evolvia.world.Nature;
import evolvia.world.SimRandom;

import java.util.List;
import java.util.Map;

/**
 * Save game content as written to JSON (DESIGN.md §11, phase 8). {@code saveVersion} and {@code meta}
 * come first, so the save list can read them without parsing the whole file.
 */
public record SaveData(
        int saveVersion,
        Meta meta,
        long seed,
        long tick,
        String speed,
        View view,
        SimRandom.State random,
        TerrainData terrain,
        SpeciesData species,
        GodData god,
        StatsData stats,
        EcsData ecs,
        int[] pathQueue,
        GroupsData groups,
        List<String> milestones,
        List<RefugeData> refuges,
        Nature.State nature,
        List<BuildingData> buildings,
        evolvia.world.Science.State science,
        RivalData rival) {

    /** The rival people (phase 11): how far its evolution plan has come. */
    public record RivalData(int step, evolvia.systems.RaidSystem.State raids) {
    }

    /** A save without the rival (older versions and tests). */
    public SaveData(int saveVersion, Meta meta, long seed, long tick, String speed, View view, SimRandom.State random,
                    TerrainData terrain, SpeciesData species, GodData god, StatsData stats, EcsData ecs, int[] pathQueue,
                    GroupsData groups, List<String> milestones, List<RefugeData> refuges, Nature.State nature,
                    List<BuildingData> buildings, evolvia.world.Science.State science) {
        this(saveVersion, meta, seed, tick, speed, view, random, terrain, species, god, stats, ecs, pathQueue, groups,
                milestones, refuges, nature, buildings, science, null);
    }

    /** A save without science (older versions and tests). */
    public SaveData(int saveVersion, Meta meta, long seed, long tick, String speed, View view, SimRandom.State random,
                    TerrainData terrain, SpeciesData species, GodData god, StatsData stats, EcsData ecs, int[] pathQueue,
                    GroupsData groups, List<String> milestones, List<RefugeData> refuges, Nature.State nature,
                    List<BuildingData> buildings) {
        this(saveVersion, meta, seed, tick, speed, view, random, terrain, species, god, stats, ecs, pathQueue, groups,
                milestones, refuges, nature, buildings, null, null);
    }

    /** Shown in the save list. */
    public record Meta(String name, String savedAt, String speciesName, int population, int generation, long tick) {
    }

    /** Camera (not simulation state, but players expect to come back to the same view). */
    public record View(float focusX, float focusZ, float yaw, float pitch, float distance) {
    }

    /** Float arrays and biome indices as base64 (little-endian), biomes by id through a palette. */
    public record TerrainData(long seed, int width, int depth, float seaLevel, float maxHeight, float altitudeCooling,
                              String cornerHeights, String temperature, String baseTemperature, String moisture,
                              List<String> biomePalette, String biomeIndices) {
    }

    public record SpeciesData(String id, float points, float pointsEarned, List<String> unlocked) {
    }

    public record FaithData(float points, float earned, float perMinute, int believers, float alignment,
                            int kindActs, int cruelActs) {
    }

    public record GodData(FaithData faith, List<GodPowers.RainArea> rains, List<GodPowers.Strike> strikes,
                          List<GodPowers.Command> queue, List<GodPowers.HandCommand> handQueue,
                          List<GodPowers.PlanCommand> planQueue) {
    }

    public record StatsData(Map<String, Integer> deaths, int births, int maxGeneration, int lastGeneration,
                            float pointsPerMinute, int[] historyPopulation, float[] historyFood) {
    }

    /**
     * Entities: ID allocation plus one list per component type, each in the store's order (the order
     * systems iterate in, which matters for identical replays).
     */
    public record EcsData(int nextId, int[] alive, int[] free,
                          List<TransformData> transforms, List<TransformData> prevTransforms,
                          List<VelocityData> velocities, int[] creatures, List<GenomeData> genomes,
                          List<NeedsData> needs, List<HealthData> healths, List<AgeData> ages,
                          List<ReproductionData> reproductions, List<AiData> ai, List<MemoryData> memories,
                          List<ResourceData> resources, int[] believers, List<FearData> fears,
                          List<GroupMemberData> groupMembers, int[] creatureStages, List<UnderAttackData> underAttacks,
                          List<SickData> sick, List<String> creatureSpecies, List<CarryingData> carrying, List<RoleData> roles) {
    }

    public record TransformData(int e, float x, float y, float z, float yaw) {
    }

    public record VelocityData(int e, float dirX, float dirZ, float speed, boolean blocked) {
    }

    public record GenomeData(int e, float size, float speed, float tint, int generation) {
    }

    public record NeedsData(int e, float hunger, float thirst, float energy, float exposure, boolean sleeping) {
    }

    public record HealthData(int e, float hp, float maxHp) {
    }

    public record AgeData(int e, int ageTicks, int maxAgeTicks) {
    }

    public record ReproductionData(int e, int readyAtTick, int offspring) {
    }

    public record PathData(float[] xs, float[] zs, int next) {
    }

    public record AiData(int e, String action, float score, int actionTicks, int targetEntity, float targetX,
                         float targetZ, int waitTicks, String pathStatus, PathData path, int pathRetries,
                         Map<String, Integer> cooldownUntilTick) {
    }

    public record MemoryData(int e, boolean knowsWater, float waterX, float waterZ, boolean knowsFood,
                             float foodX, float foodZ) {
    }

    public record ResourceData(int e, String type, float amount, float regrowPerTick, boolean divine, int age) {
    }

    public record FearData(int e, float fromX, float fromZ, float distance, int untilTick) {
    }

    public record GroupMemberData(int e, int group, int farTicks) {
    }

    /** Herds (save version 2+; null in older saves). */
    public record UnderAttackData(int e, int attacker, int untilTick) {
    }

    /** @param owner species id of the people whose hut it is (phase 11b), null = anyone's */
    public record RefugeData(String type, float x, float z, boolean sacred, String owner) {
    }

    public record SickData(int e, int untilTick, int immuneUntilTick) {
    }

    public record CarryingData(int e, String material, float amount) {
    }

    public record RoleData(int e, boolean builder) {
    }

    /** @param rival a building of the rival's tribe (phase 11b) */
    public record BuildingData(int id, String type, float x, float z, float progress, boolean paid, boolean planned, int refuge,
                               boolean rival) {
    }

    public record GroupsData(int nextId, List<GroupData> groups, int playerVictories) {
    }

    public record GroupData(int id, int leader, int size, boolean player, float homeX, float homeZ, boolean settled,
                            float hunger, int attackGroup, int attackUntilTick, int shelter, boolean knowsWater, float waterX, float waterZ,
                            boolean knowsFood, float foodX, float foodZ, String species, boolean hasCamp, float campX,
                            float campZ, Map<String, Float> stock, boolean tribe, boolean raid) {
    }
}

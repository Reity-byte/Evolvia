package evolvia.data;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Biome;
import evolvia.world.Biome.Range;
import evolvia.world.BiomeTable;
import evolvia.world.ResourceDefinition;
import evolvia.world.ResourceKind;
import evolvia.world.ResourceTable;
import evolvia.world.WorldConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Loads game data definitions from JSON files on the classpath ({@code src/main/resources/data}).
 * Invalid data fails fast with a message naming the file and the problem.
 */
public final class DataLoader {

    public static final String WORLD_CONFIG = "data/world.json";
    public static final String BIOMES = "data/biomes.json";
    public static final String SPECIES = "data/species.json";
    public static final String RESOURCES = "data/resources.json";

    private static final Gson GSON = new Gson();

    private DataLoader() {
    }

    /** Loads and validates {@code data/world.json}. */
    public static WorldConfig loadWorldConfig() {
        return parseWorldConfig(readResource(WORLD_CONFIG), WORLD_CONFIG);
    }

    /** Loads and validates {@code data/biomes.json}. */
    public static BiomeTable loadBiomes() {
        return parseBiomes(readResource(BIOMES), BIOMES);
    }

    public static WorldConfig parseWorldConfig(String json, String source) {
        WorldConfig config = fromJson(json, WorldConfig.class, source);
        config.validate(source);
        Colors.parseHex(config.water().color(), source + ": water.color");
        return config;
    }

    /** Loads and validates {@code data/species.json}. */
    public static SpeciesDefinition loadSpecies() {
        return parseSpecies(readResource(SPECIES), SPECIES);
    }

    public static SpeciesDefinition parseSpecies(String json, String source) {
        SpeciesJson s = fromJson(json, SpeciesJson.class, source);
        require(s.id() != null && !s.id().isBlank(), source, "missing \"id\"");
        require(positive(s.bodySize()), source, "bodySize must be positive");
        require(positive(s.speed()), source, "speed must be positive");
        require(positive(s.maxHealth()), source, "maxHealth must be positive");
        require(positive(s.senseRadius()), source, "senseRadius must be positive");
        float[] lifespan = s.lifespanSeconds();
        require(lifespan != null && lifespan.length == 2 && lifespan[0] > 0 && lifespan[0] <= lifespan[1], source,
                "lifespanSeconds must be [min, max] with 0 < min <= max");

        SpeciesDefinition.NeedRates needs = s.needs();
        require(needs != null, source, "missing \"needs\"");
        require(needs.hungerPerSecond() > 0 && needs.thirstPerSecond() > 0 && needs.energyDrainPerSecond() > 0
                        && needs.energyRecoverPerSecond() > 0 && needs.damagePerSecond() > 0,
                source, "needs: hungerPerSecond, thirstPerSecond, energyDrainPerSecond, energyRecoverPerSecond and damagePerSecond must be positive");
        require(needs.sleepingNeedFactor() >= 0 && needs.healthRegenPerSecond() >= 0, source,
                "needs: sleepingNeedFactor and healthRegenPerSecond must not be negative");

        SpeciesDefinition.Eating eating = s.eating();
        require(eating != null && eating.hungerPerUnit() > 0 && eating.secondsPerUnit() > 0 && eating.thirstReliefPerSecond() > 0,
                source, "eating: hungerPerUnit, secondsPerUnit and thirstReliefPerSecond must be positive");

        SpeciesDefinition.AiTuning ai = s.ai();
        require(ai != null && ai.evaluateEverySeconds() > 0, source, "ai.evaluateEverySeconds must be positive");
        require(inUnitRange(ai.needThreshold()) && inUnitRange(ai.sleepThreshold()) && inUnitRange(ai.wanderScore())
                        && ai.switchMargin() >= 0 && ai.exploreRadiusFactor() >= 1, source,
                "ai: needThreshold, sleepThreshold and wanderScore must be in [0, 1), switchMargin must not be negative, exploreRadiusFactor must be at least 1");

        require(s.wander() != null && positive(s.wander().radius()), source, "wander.radius must be positive");
        float[] pause = s.wander().pauseSeconds();
        require(pause != null && pause.length == 2 && pause[0] >= 0 && pause[0] <= pause[1], source,
                "wander.pauseSeconds must be [min, max] with 0 <= min <= max");
        SpeciesDefinition.Reproduction reproduction = s.reproduction();
        require(reproduction != null, source, "missing \"reproduction\"");
        require(reproduction.adultAgeSeconds() > 0 && reproduction.adultAgeSeconds() < lifespan[0], source,
                "reproduction.adultAgeSeconds must be positive and below the shortest lifespan");
        require(reproduction.cooldownSeconds() > 0 && reproduction.litterSize() >= 1 && reproduction.hungerCost() >= 0,
                source, "reproduction: cooldownSeconds must be positive, litterSize at least 1, hungerCost not negative");
        require(inUnitRange(reproduction.maxNeed()) && inUnitRange(reproduction.minHealth())
                        && inUnitRange(reproduction.mateScore()), source,
                "reproduction: maxNeed, minHealth and mateScore must be in [0, 1)");

        SpeciesDefinition.GenomeTuning genome = s.genome();
        require(genome != null && genome.variation() >= 0 && genome.variation() < 0.5f && genome.mutation() >= 0,
                source, "genome: variation must be in [0, 0.5), mutation must not be negative");

        SpeciesDefinition.Population population = s.population();
        require(population != null && population.starting() >= 0 && population.max() >= population.starting()
                        && population.max() <= 100_000 && population.spawnRadius() >= 0, source,
                "population: 0 <= starting <= max <= 100000, spawnRadius must not be negative");

        return new SpeciesDefinition(
                s.id(),
                s.name() != null ? s.name() : s.id(),
                Colors.parseHex(s.color(), source + ": color"),
                s.bodySize(),
                s.speed(),
                s.maxHealth(),
                lifespan[0],
                lifespan[1],
                s.senseRadius(),
                needs,
                eating,
                ai,
                new SpeciesDefinition.Wander(s.wander().radius(), pause[0], pause[1]),
                reproduction,
                genome,
                population);
    }

    /** Loads and validates {@code data/resources.json}. */
    public static ResourceTable loadResources() {
        return parseResources(readResource(RESOURCES), RESOURCES);
    }

    public static ResourceTable parseResources(String json, String source) {
        ResourceFile file = fromJson(json, ResourceFile.class, source);
        require(file.resources() != null, source, "missing \"resources\" array");
        List<ResourceDefinition> resources = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ResourceJson r : file.resources()) {
            String where = source + ": resource #" + resources.size() + (r.id() != null ? " ('" + r.id() + "')" : "");
            require(r.id() != null && !r.id().isBlank(), where, "missing \"id\"");
            require(ids.add(r.id()), where, "duplicate id");
            ResourceKind kind;
            try {
                kind = ResourceKind.valueOf(String.valueOf(r.kind()).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(where + ": kind must be \"food\" or \"water\", got: " + r.kind());
            }
            if (kind == ResourceKind.FOOD) {
                require(positive(r.capacity()) && positive(r.size()), where, "food needs a positive capacity and size");
                require(r.regrowPerSecond() != null && r.regrowPerSecond() >= 0, where, "regrowPerSecond must not be negative");
                require(r.spawnDensity() != null && r.spawnDensity() >= 0 && r.spawnDensity() <= 1, where,
                        "spawnDensity must be in [0, 1]");
            }
            resources.add(new ResourceDefinition(
                    resources.size(),
                    r.id(),
                    r.name() != null ? r.name() : r.id(),
                    kind,
                    r.capacity() != null ? r.capacity() : 0f,
                    r.regrowPerSecond() != null ? r.regrowPerSecond() : 0f,
                    r.spawnDensity() != null ? r.spawnDensity() : 0f,
                    r.size() != null ? r.size() : 0f,
                    r.color() != null ? Colors.parseHex(r.color(), where + ": color") : 0,
                    r.emptyColor() != null ? Colors.parseHex(r.emptyColor(), where + ": emptyColor") : 0));
        }
        return new ResourceTable(resources, source);
    }

    private static boolean positive(Float value) {
        return value != null && value > 0;
    }

    private static boolean inUnitRange(float value) {
        return value >= 0 && value < 1;
    }

    private static void require(boolean condition, String source, String message) {
        if (!condition) {
            throw new IllegalStateException(source + ": " + message);
        }
    }

    public static BiomeTable parseBiomes(String json, String source) {
        BiomeFile file = fromJson(json, BiomeFile.class, source);
        if (file.biomes() == null) {
            throw new IllegalStateException(source + ": missing \"biomes\" array");
        }
        List<Biome> biomes = new ArrayList<>();
        for (BiomeJson b : file.biomes()) {
            String where = source + ": biome #" + biomes.size() + (b.id() != null ? " ('" + b.id() + "')" : "");
            if (b.id() == null || b.id().isBlank()) {
                throw new IllegalStateException(where + ": missing \"id\"");
            }
            if (b.color() == null) {
                throw new IllegalStateException(where + ": missing \"color\"");
            }
            boolean water = Boolean.TRUE.equals(b.water());
            float fertility = b.fertility() != null ? b.fertility() : 0f;
            if (fertility < 0 || fertility > 1) {
                throw new IllegalStateException(where + ": fertility must be in [0, 1]");
            }
            biomes.add(new Biome(
                    biomes.size(),
                    b.id(),
                    b.name() != null ? b.name() : b.id(),
                    Colors.parseHex(b.color(), where + ": color"),
                    water,
                    b.passable() != null ? b.passable() : !water,
                    fertility,
                    range(b.temperature(), where + ": temperature"),
                    range(b.moisture(), where + ": moisture"),
                    range(b.altitude(), where + ": altitude")));
        }
        return new BiomeTable(biomes, source);
    }

    private static Range range(float[] values, String where) {
        if (values == null) {
            return Range.ANY;
        }
        if (values.length != 2 || values[0] > values[1]) {
            throw new IllegalStateException(where + " must be [min, max] with min <= max");
        }
        return new Range(values[0], values[1]);
    }

    private static <T> T fromJson(String json, Class<T> type, String source) {
        try (Reader reader = new StringReader(json)) {
            T value = GSON.fromJson(reader, type);
            if (value == null) {
                throw new IllegalStateException(source + ": file is empty");
            }
            return value;
        } catch (JsonParseException e) {
            throw new IllegalStateException(source + ": invalid JSON: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readResource(String path) {
        try (InputStream in = DataLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Data file not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read data file: " + path, e);
        }
    }

    /** JSON shape of {@code species.json}; boxed types so a missing value is null. */
    private record SpeciesJson(String id, String name, String color, Float bodySize, Float speed, Float maxHealth,
                               float[] lifespanSeconds, Float senseRadius, SpeciesDefinition.NeedRates needs,
                               SpeciesDefinition.Eating eating, SpeciesDefinition.AiTuning ai,
                               WanderJson wander, SpeciesDefinition.Reproduction reproduction,
                               SpeciesDefinition.GenomeTuning genome, SpeciesDefinition.Population population) {
    }

    /** JSON shape of {@code resources.json}. */
    private record ResourceFile(List<ResourceJson> resources) {
    }

    private record ResourceJson(String id, String name, String kind, Float capacity, Float regrowPerSecond,
                                Float spawnDensity, Float size, String color, String emptyColor) {
    }

    private record WanderJson(Float radius, float[] pauseSeconds) {
    }

    /** JSON shape of {@code biomes.json}. */
    private record BiomeFile(List<BiomeJson> biomes) {
    }

    /** JSON shape of one biome; optional fields are boxed so a missing value is null. */
    private record BiomeJson(String id, String name, String color, Boolean water, Boolean passable,
                             Float fertility, float[] temperature, float[] moisture, float[] altitude) {
    }
}

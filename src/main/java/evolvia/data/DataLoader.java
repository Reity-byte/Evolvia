package evolvia.data;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Biome;
import evolvia.world.Biome.Range;
import evolvia.world.BiomeTable;
import evolvia.world.WorldConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads game data definitions from JSON files on the classpath ({@code src/main/resources/data}).
 * Invalid data fails fast with a message naming the file and the problem.
 */
public final class DataLoader {

    public static final String WORLD_CONFIG = "data/world.json";
    public static final String BIOMES = "data/biomes.json";
    public static final String SPECIES = "data/species.json";

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
        require(s.bodySize() != null && s.bodySize() > 0, source, "bodySize must be positive");
        require(s.speed() != null && s.speed() > 0, source, "speed must be positive");
        require(s.wander() != null, source, "missing \"wander\"");
        require(s.wander().radius() != null && s.wander().radius() > 0, source, "wander.radius must be positive");
        float[] pause = s.wander().pauseSeconds();
        require(pause != null && pause.length == 2 && pause[0] >= 0 && pause[0] <= pause[1], source,
                "wander.pauseSeconds must be [min, max] with 0 <= min <= max");
        require(s.startingPopulation() != null && s.startingPopulation() >= 0 && s.startingPopulation() <= 100_000, source,
                "startingPopulation must be between 0 and 100000");
        return new SpeciesDefinition(
                s.id(),
                s.name() != null ? s.name() : s.id(),
                Colors.parseHex(s.color(), source + ": color"),
                s.bodySize(),
                s.speed(),
                s.wander().radius(),
                pause[0],
                pause[1],
                s.startingPopulation());
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
    private record SpeciesJson(String id, String name, String color, Float bodySize, Float speed,
                               WanderJson wander, Integer startingPopulation) {
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

package evolvia.world;

import evolvia.world.WorldConfig.NoiseSettings;

import java.util.Random;

/**
 * Generates a {@link Terrain} from a seed: heightmap from multi-octave simplex noise and
 * biomes from two more noise channels (temperature, moisture). Same seed + config = same world.
 */
public final class TerrainGenerator {

    /** Shape of the map border falloff: 2 = circle, higher = closer to a square. */
    private static final double EDGE_SHAPE_EXPONENT = 4.0;
    private static final long RELIEF_SALT = 0x4e11ef5L;

    private TerrainGenerator() {
    }

    /** Generates a terrain with a fresh {@code Random(seed)}. */
    public static Terrain generate(WorldConfig config, BiomeTable biomes, long seed) {
        return generate(config, biomes, seed, new Random(seed));
    }

    /**
     * Generates a terrain using the world's {@link Random}, which the simulation then continues to use
     * (one Random per world). Noise channels are created from it in a fixed order.
     */
    public static Terrain generate(WorldConfig config, BiomeTable biomes, long seed, Random random) {
        SimplexNoise heightNoise = new SimplexNoise(random);
        SimplexNoise temperatureNoise = new SimplexNoise(random);
        SimplexNoise moistureNoise = new SimplexNoise(random);

        int width = config.width();
        int depth = config.depth();
        float seaLevel = config.seaLevel() * config.heightScale();

        float[] heights = generateHeights(config, heightNoise);
        if (config.relief() != null) {
            // Its own random (from the seed), so the world's random and everything after it stay as they were.
            shapeRelief(config, heights, new SimplexNoise(new Random(seed ^ RELIEF_SALT)));
        }

        int tiles = width * depth;
        float[] temperature = new float[tiles];
        float[] moisture = new float[tiles];
        for (int tz = 0; tz < depth; tz++) {
            for (int tx = 0; tx < width; tx++) {
                // Sample climate at tile centers.
                temperature[tz * width + tx] = (float) sample(temperatureNoise, config.temperature(), tx + 0.5, tz + 0.5);
                moisture[tz * width + tx] = (float) sample(moistureNoise, config.moisture(), tx + 0.5, tz + 0.5);
            }
        }
        normalize(temperature);
        normalize(moisture);
        if (config.climate() != null) {
            shapeClimate(config.climate(), temperature, width, depth);
        }
        float[] baseTemperature = temperature.clone();

        Biome[] tileBiomes = new Biome[tiles];
        for (int tz = 0; tz < depth; tz++) {
            for (int tx = 0; tx < width; tx++) {
                int i = tz * width + tx;
                float tileHeight = (heights[tz * (width + 1) + tx] + heights[tz * (width + 1) + tx + 1]
                        + heights[(tz + 1) * (width + 1) + tx] + heights[(tz + 1) * (width + 1) + tx + 1]) * 0.25f;
                if (tileHeight < seaLevel) {
                    tileBiomes[i] = biomes.water();
                    continue;
                }
                float altitude = (tileHeight - seaLevel) / (config.heightScale() - seaLevel);
                temperature[i] = clamp01(temperature[i] - config.altitudeCooling() * altitude);
                tileBiomes[i] = biomes.classifyLand(temperature[i], moisture[i], altitude);
            }
        }

        return new Terrain(seed, biomes, width, depth, seaLevel, config.heightScale(),
                heights, tileBiomes, temperature, moisture, baseTemperature, config.altitudeCooling());
    }

    /** Corner heights in world units: noise, normalized to 0..1, shaped by exponent and edge falloff. */
    private static float[] generateHeights(WorldConfig config, SimplexNoise noise) {
        int width = config.width();
        int depth = config.depth();
        int columns = width + 1;
        float[] heights = new float[columns * (depth + 1)];
        for (int z = 0; z <= depth; z++) {
            for (int x = 0; x <= width; x++) {
                heights[z * columns + x] = (float) sample(noise, config.height(), x, z);
            }
        }
        normalize(heights);

        WorldConfig.EdgeFalloff falloff = config.edgeFalloff();
        for (int z = 0; z <= depth; z++) {
            for (int x = 0; x <= width; x++) {
                int i = z * columns + x;
                float h = (float) Math.pow(heights[i], config.heightExponent());
                if (falloff.width() > 0) {
                    float t = smoothstep(clamp01(edgeDistance(x, z, width, depth) / falloff.width()));
                    h *= falloff.minFactor() + (1 - falloff.minFactor()) * t;
                }
                heights[i] = h * config.heightScale();
            }
        }
        return heights;
    }

    /**
     * Reshapes the land into plains with hills here and there (in place). Water stays where the height noise put it,
     * so coasts, lakes and islands keep their shape; above the sea the land rises over {@code coastWidth} to the
     * plain, the plain undulates a little, and where the hill noise is above the threshold a hill rises (higher
     * deeper inland).
     */
    private static void shapeRelief(WorldConfig config, float[] heights, SimplexNoise noise) {
        WorldConfig.Relief relief = config.relief();
        int columns = config.width() + 1;
        int rows = config.depth() + 1;
        float scale = config.heightScale();
        float sea = config.seaLevel();
        float[] hills = new float[heights.length];
        for (int z = 0; z < rows; z++) {
            for (int x = 0; x < columns; x++) {
                hills[z * columns + x] = (float) sample(noise, relief.hills(), x, z);
            }
        }
        normalize(hills);
        for (int z = 0; z < rows; z++) {
            for (int x = 0; x < columns; x++) {
                int i = z * columns + x;
                float h = heights[i] / scale;
                if (h < sea) {
                    continue; // sea and lakes keep their depth
                }
                float land = (h - sea) / Math.max(1e-6f, 1f - sea); // 0 at the coast, 1 at the highest noise
                float coast = smoothstep(clamp01(land / relief.coastWidth()));
                float ripple = (float) noise.fbm(x / relief.rippleScale() + 517.3, z / relief.rippleScale() - 211.9, 2, 2.0, 0.5);
                float plain = sea + coast * (relief.plainHeight() + relief.ripple() * ripple);
                float hill = smoothstep(clamp01((hills[i] - relief.hillThreshold()) / relief.hillSoftness()));
                float inland = smoothstep(clamp01(land / (relief.coastWidth() * 3f)));
                // A mountain rises out of its hills all the way up to the top of the noise (no step).
                float peak = clamp01((hills[i] - relief.mountainThreshold()) / Math.max(1e-6f, 1f - relief.mountainThreshold()));
                float mountain = (float) Math.pow(smoothstep(peak), 0.75);
                float height = plain + (relief.hillHeight() * hill * (1f + land) + relief.mountainHeight() * mountain) * inland;
                heights[i] = softCap(Math.max(height, sea)) * scale;
            }
        }
        if (relief.talus() > 0f && relief.erosion() > 0) {
            erode(heights, columns, rows, sea * scale + 0.01f, relief.talus(), relief.erosion());
        }
    }

    /** Heights near the top bend under 1 instead of being cut flat there (no table mountains from the cap). */
    private static float softCap(float h) {
        float knee = 0.8f;
        if (h <= knee) {
            return h;
        }
        float over = h - knee;
        return knee + (1f - knee) * over / (over + (1f - knee));
    }

    /**
     * Thermal erosion (in place): wherever land is steeper than {@code talus} (world units per tile) towards a
     * neighbouring land corner, some of the difference slides down, for {@code iterations} rounds. Mountains get
     * slopes that fall into foothills instead of walls. Only land moves (the sea and the coasts keep their shape).
     */
    private static void erode(float[] heights, int columns, int rows, float sea, float talus, int iterations) {
        float[] delta = new float[heights.length];
        int[][] neighbours = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int round = 0; round < iterations; round++) {
            java.util.Arrays.fill(delta, 0f);
            for (int z = 1; z < rows - 1; z++) {
                for (int x = 1; x < columns - 1; x++) {
                    int i = z * columns + x;
                    float h = heights[i];
                    if (h <= sea) {
                        continue;
                    }
                    for (int[] n : neighbours) {
                        int j = (z + n[1]) * columns + x + n[0];
                        float drop = h - heights[j];
                        if (heights[j] > sea && drop > talus) {
                            float move = (drop - talus) * 0.2f; // a quarter-ish per neighbour keeps it stable
                            delta[i] -= move;
                            delta[j] += move;
                        }
                    }
                }
            }
            for (int i = 0; i < heights.length; i++) {
                heights[i] += delta[i];
            }
        }
    }

    /**
     * Large climate zones (in place): part of the temperature follows the map from the cold north to the warm south,
     * and the lowlands never get as cold as tundra; only altitude (mountains) brings snow.
     */
    private static void shapeClimate(WorldConfig.ClimateShape climate, float[] temperature, int width, int depth) {
        for (int tz = 0; tz < depth; tz++) {
            float latitude = (tz + 0.5f) / depth;
            for (int tx = 0; tx < width; tx++) {
                int i = tz * width + tx;
                float t = (1f - climate.latitudeWeight()) * temperature[i] + climate.latitudeWeight() * latitude;
                temperature[i] = t;
            }
        }
        normalize(temperature);
        for (int i = 0; i < temperature.length; i++) {
            float t = 0.5f + (temperature[i] - 0.5f) * climate.contrast();
            temperature[i] = climate.lowlandFloor() + (1f - climate.lowlandFloor()) * t;
        }
    }

    /**
     * Approximate distance (tiles) from a rounded-square border (superellipse). Unlike
     * min(x, z, ...) it has no crease along the diagonals and gives rounded island corners.
     * Negative outside the rounded shape (in the map corners).
     */
    private static float edgeDistance(int x, int z, int width, int depth) {
        double u = Math.abs(2.0 * x / width - 1.0);
        double v = Math.abs(2.0 * z / depth - 1.0);
        double norm = Math.pow(Math.pow(u, EDGE_SHAPE_EXPONENT) + Math.pow(v, EDGE_SHAPE_EXPONENT), 1.0 / EDGE_SHAPE_EXPONENT);
        return (float) ((1.0 - norm) * Math.min(width, depth) / 2.0);
    }

    private static double sample(SimplexNoise noise, NoiseSettings settings, double x, double z) {
        return noise.fbm(x / settings.scale(), z / settings.scale(),
                settings.octaves(), settings.lacunarity(), settings.gain());
    }

    /** Rescales values in place to exactly [0, 1], so every map uses the full range. */
    private static void normalize(float[] values) {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (float v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        float range = max - min;
        for (int i = 0; i < values.length; i++) {
            values[i] = range > 0 ? (values[i] - min) / range : 0.5f;
        }
    }

    private static float smoothstep(float t) {
        return t * t * (3 - 2 * t);
    }

    private static float clamp01(float v) {
        return Math.clamp(v, 0f, 1f);
    }
}

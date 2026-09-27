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
                heights, tileBiomes, temperature, moisture);
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

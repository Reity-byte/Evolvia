package evolvia.world;

import evolvia.data.DataLoader;

/**
 * Small hand-made terrains for tests. Each character is one tile: {@code .} land, {@code ~} water.
 * Row 0 is z = 0, column 0 is x = 0. Land is flat at height 1, water at height 0 (sea level 0.5).
 */
public final class TestTerrains {

    private static final BiomeTable BIOMES = DataLoader.parseBiomes("""
            {"biomes": [
              {"id": "water", "color": "#0000ff", "water": true},
              {"id": "grass", "color": "#00ff00", "fertility": 1.0}
            ]}
            """, "test-biomes.json");

    private TestTerrains() {
    }

    public static Terrain fromAscii(String... rows) {
        int depth = rows.length;
        int width = rows[0].length();
        Biome[] biomes = new Biome[width * depth];
        for (int z = 0; z < depth; z++) {
            if (rows[z].length() != width) {
                throw new IllegalArgumentException("All rows must have the same length");
            }
            for (int x = 0; x < width; x++) {
                biomes[z * width + x] = rows[z].charAt(x) == '~' ? BIOMES.water() : BIOMES.byId("grass");
            }
        }
        // Corner heights: a corner is land (1) if any adjacent tile is land, so land tiles are flat.
        float[] corners = new float[(width + 1) * (depth + 1)];
        for (int cz = 0; cz <= depth; cz++) {
            for (int cx = 0; cx <= width; cx++) {
                boolean land = false;
                for (int dz = -1; dz <= 0; dz++) {
                    for (int dx = -1; dx <= 0; dx++) {
                        int tx = cx + dx;
                        int tz = cz + dz;
                        if (tx >= 0 && tz >= 0 && tx < width && tz < depth && !biomes[tz * width + tx].water()) {
                            land = true;
                        }
                    }
                }
                corners[cz * (width + 1) + cx] = land ? 1f : 0f;
            }
        }
        float[] half = new float[width * depth];
        java.util.Arrays.fill(half, 0.5f);
        return new Terrain(1L, BIOMES, width, depth, 0.5f, 1f, corners, biomes, half, half.clone());
    }
}

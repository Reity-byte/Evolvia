package evolvia.world;

/**
 * Heightmap world of {@code width x depth} tiles. 1 tile = 1 world unit; tile (tx, tz) spans
 * x in [tx, tx+1], z in [tz, tz+1]. Heights are stored at tile corners, so there are
 * {@code (width+1) x (depth+1)} height samples. Y is up.
 * <p>
 * Created by {@link TerrainGenerator}. Contains no rendering code.
 */
public final class Terrain {

    private final long seed;
    private final BiomeTable biomeTable;
    private final int width;
    private final int depth;
    private final float seaLevel;
    private final float maxHeight;
    private final float[] cornerHeights;
    private final Biome[] biomes;
    private final float[] temperature;
    private final float[] moisture;
    private final float[] fertility;

    Terrain(long seed, BiomeTable biomeTable, int width, int depth, float seaLevel, float maxHeight,
            float[] cornerHeights, Biome[] biomes, float[] temperature, float[] moisture) {
        this.seed = seed;
        this.biomeTable = biomeTable;
        this.width = width;
        this.depth = depth;
        this.seaLevel = seaLevel;
        this.maxHeight = maxHeight;
        this.cornerHeights = cornerHeights;
        this.biomes = biomes;
        this.temperature = temperature;
        this.moisture = moisture;
        this.fertility = new float[biomes.length];
        for (int i = 0; i < biomes.length; i++) {
            fertility[i] = biomes[i].fertility();
        }
    }

    public long seed() {
        return seed;
    }

    /** Biome definitions this terrain was generated with. */
    public BiomeTable biomeTable() {
        return biomeTable;
    }

    /** Number of tiles along X. */
    public int width() {
        return width;
    }

    /** Number of tiles along Z. */
    public int depth() {
        return depth;
    }

    /** Sea level height in world units. */
    public float seaLevel() {
        return seaLevel;
    }

    /** Maximum possible terrain height in world units. */
    public float maxHeight() {
        return maxHeight;
    }

    public boolean inBounds(int tx, int tz) {
        return tx >= 0 && tz >= 0 && tx < width && tz < depth;
    }

    /** Height at tile corner (cx, cz), cx in [0, width], cz in [0, depth]. Clamped to the map. */
    public float cornerHeight(int cx, int cz) {
        cx = Math.clamp(cx, 0, width);
        cz = Math.clamp(cz, 0, depth);
        return cornerHeights[cz * (width + 1) + cx];
    }

    /**
     * Terrain surface height at world position (x, z), clamped to the map. Interpolates on the
     * same two triangles per tile that the terrain mesh uses (diagonal from (tx, tz) to (tx+1, tz+1)).
     */
    public float heightAt(float x, float z) {
        x = Math.clamp(x, 0f, width);
        z = Math.clamp(z, 0f, depth);
        int tx = Math.min((int) x, width - 1);
        int tz = Math.min((int) z, depth - 1);
        float fx = x - tx;
        float fz = z - tz;

        float h00 = cornerHeight(tx, tz);
        float h10 = cornerHeight(tx + 1, tz);
        float h01 = cornerHeight(tx, tz + 1);
        float h11 = cornerHeight(tx + 1, tz + 1);
        if (fz > fx) {
            return h00 + fz * (h01 - h00) + fx * (h11 - h01);
        }
        return h00 + fx * (h10 - h00) + fz * (h11 - h10);
    }

    /** Average height of the tile's four corners. */
    public float tileHeight(int tx, int tz) {
        return (cornerHeight(tx, tz) + cornerHeight(tx + 1, tz)
                + cornerHeight(tx, tz + 1) + cornerHeight(tx + 1, tz + 1)) * 0.25f;
    }

    public Biome biome(int tx, int tz) {
        return biomes[index(tx, tz)];
    }

    public boolean isPassable(int tx, int tz) {
        return inBounds(tx, tz) && biome(tx, tz).passable();
    }

    /** Current fertility 0..1 (starts at the biome's base value). */
    public float fertility(int tx, int tz) {
        return fertility[index(tx, tz)];
    }

    /** Tile temperature 0..1 (includes altitude cooling). */
    public float temperature(int tx, int tz) {
        return temperature[index(tx, tz)];
    }

    /** Tile moisture 0..1. */
    public float moisture(int tx, int tz) {
        return moisture[index(tx, tz)];
    }

    private int index(int tx, int tz) {
        if (!inBounds(tx, tz)) {
            throw new IndexOutOfBoundsException("Tile (" + tx + ", " + tz + ") is outside the " + width + "x" + depth + " map");
        }
        return tz * width + tx;
    }
}

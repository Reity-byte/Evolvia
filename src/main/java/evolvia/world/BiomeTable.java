package evolvia.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * All biomes and the rule that assigns a biome to a tile.
 * <p>
 * Tiles below sea level get the water biome. Land tiles get the first land biome (in file order)
 * whose temperature / moisture / altitude ranges match; the last land biome must be a catch-all.
 */
public final class BiomeTable {

    private final List<Biome> all;
    private final List<Biome> land = new ArrayList<>();
    private final Biome water;

    /**
     * @param biomes biomes in priority order; indices must match their positions
     * @throws IllegalStateException if the definitions are inconsistent
     */
    public BiomeTable(List<Biome> biomes, String source) {
        if (biomes.isEmpty()) {
            throw new IllegalStateException(source + ": no biomes defined");
        }
        this.all = List.copyOf(biomes);

        Set<String> ids = new HashSet<>();
        Biome waterBiome = null;
        for (int i = 0; i < all.size(); i++) {
            Biome biome = all.get(i);
            if (biome.index() != i) {
                throw new IllegalStateException(source + ": biome '" + biome.id() + "' has index " + biome.index() + ", expected " + i);
            }
            if (!ids.add(biome.id())) {
                throw new IllegalStateException(source + ": duplicate biome id '" + biome.id() + "'");
            }
            if (biome.water()) {
                if (waterBiome != null) {
                    throw new IllegalStateException(source + ": more than one water biome ('"
                            + waterBiome.id() + "', '" + biome.id() + "')");
                }
                waterBiome = biome;
            } else {
                land.add(biome);
            }
        }
        if (waterBiome == null) {
            throw new IllegalStateException(source + ": no water biome (\"water\": true) defined");
        }
        if (land.isEmpty()) {
            throw new IllegalStateException(source + ": no land biome defined");
        }
        for (int i = 0; i < land.size() - 1; i++) {
            if (land.get(i).isCatchAll()) {
                throw new IllegalStateException(source + ": land biome '" + land.get(i).id()
                        + "' has no conditions, so the biomes after it can never appear; only the last land biome may be a catch-all");
            }
        }
        Biome last = land.get(land.size() - 1);
        if (!last.isCatchAll()) {
            throw new IllegalStateException(source + ": the last land biome ('" + last.id()
                    + "') must have no temperature/moisture/altitude limits, so every land tile gets a biome");
        }
        this.water = waterBiome;
    }

    /** Biome for a land tile with the given climate (each value 0..1). Never null. */
    public Biome classifyLand(float temperature, float moisture, float altitude) {
        for (Biome biome : land) {
            if (biome.matches(temperature, moisture, altitude)) {
                return biome;
            }
        }
        return land.get(land.size() - 1);
    }

    public Biome water() {
        return water;
    }

    public Biome get(int index) {
        return all.get(index);
    }

    public int size() {
        return all.size();
    }

    public List<Biome> all() {
        return all;
    }

    /** Biome with the given id, or null. */
    public Biome byId(String id) {
        for (Biome biome : all) {
            if (biome.id().equals(id)) {
                return biome;
            }
        }
        return null;
    }
}

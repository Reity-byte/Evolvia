package evolvia.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Refuges of a world (DESIGN.md §11, 9d): caves, groves and rock overhangs where herds spend the night.
 * Whoever sleeps within a refuge's radius is sheltered. A refuge the god sanctified is a sacred place.
 * Few of them (a dozen or two), so lookups just scan the list.
 */
public final class Refuges {

    /** Refuge types and shelter effects, from {@code data/refuges.json}. */
    public record Config(List<Type> types, float searchRadius, float minSpacing, float sleepEnergyFactor,
                         float sleepHealFactor) {
    }

    /**
     * @param id          stable identifier (saved)
     * @param name        display name (Czech)
     * @param count       how many a map gets (if there is room)
     * @param radius      sheltering radius in tiles
     * @param minAltitude only on tiles at least this high (0..1 above sea level); 0 = anywhere
     * @param biomes      only in these biomes; empty = any land
     */
    public record Type(String id, String name, int count, float radius, float minAltitude, List<String> biomes) {
    }

    public static final class Refuge {
        public final int id;
        public final Type type;
        public final float x;
        public final float z;
        public boolean sacred;
        /** Species id of the people who built it (a hut, phase 11b), or null: anyone may use it. */
        public String owner;

        public Refuge(int id, Type type, float x, float z) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.z = z;
        }

        public float radius() {
            return type.radius();
        }

        /** Whether creatures of {@code species} may pick it for the night. */
        public boolean usableBy(evolvia.evolution.Species species) {
            return owner == null || owner.equals(species.id());
        }

        public boolean contains(float px, float pz) {
            float dx = px - x;
            float dz = pz - z;
            return dx * dx + dz * dz <= type.radius() * type.radius();
        }
    }

    private final Config config;
    private final List<Refuge> refuges = new ArrayList<>();

    public Refuges(Config config) {
        this.config = config;
    }

    public Config config() {
        return config;
    }

    public Refuge add(Type type, float x, float z) {
        Refuge refuge = new Refuge(refuges.size() + 1, type, x, z);
        refuges.add(refuge);
        return refuge;
    }

    public List<Refuge> all() {
        return Collections.unmodifiableList(refuges);
    }

    public Refuge get(int id) {
        return id >= 1 && id <= refuges.size() ? refuges.get(id - 1) : null;
    }

    /** The refuge sheltering a point, or null. */
    public Refuge at(float x, float z) {
        for (Refuge refuge : refuges) {
            if (refuge.contains(x, z)) {
                return refuge;
            }
        }
        return null;
    }

    /** Nearest refuge within {@code maxDistance}; sacred ones first if {@code preferSacred}. Null if none. */
    public Refuge nearest(float x, float z, float maxDistance, boolean preferSacred) {
        return nearest(x, z, maxDistance, preferSacred, refuge -> true);
    }

    /** Like {@link #nearest(float, float, float, boolean)}, only among the refuges {@code allowed} accepts. */
    public Refuge nearest(float x, float z, float maxDistance, boolean preferSacred, Predicate<Refuge> allowed) {
        Refuge best = null;
        double bestScore = Double.MAX_VALUE;
        for (Refuge refuge : refuges) {
            double d = Math.hypot(refuge.x - x, refuge.z - z);
            if (d > maxDistance || !allowed.test(refuge)) {
                continue;
            }
            double score = preferSacred && refuge.sacred ? d - 10_000 : d;
            if (score < bestScore) {
                bestScore = score;
                best = refuge;
            }
        }
        return best;
    }

    public int sacredCount() {
        int count = 0;
        for (Refuge refuge : refuges) {
            if (refuge.sacred) {
                count++;
            }
        }
        return count;
    }

    /** Type by id (save games), or null. */
    public Type type(String id) {
        for (Type type : config.types()) {
            if (type.id().equals(id)) {
                return type;
            }
        }
        return null;
    }

    /** Removes all refuges (before restoring saved ones). */
    public void clear() {
        refuges.clear();
    }
}

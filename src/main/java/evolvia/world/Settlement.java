package evolvia.world;

import evolvia.god.Faith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The tribe's buildings (phase 9h) and what they do: warmth around fires, a bigger store, more faith. Also
 * the tribe's mood, which follows the god's morality: a good god makes a content tribe with more young, an
 * evil one a frightened tribe that works harder, has fewer young and sometimes runs away.
 */
public final class Settlement {

    /** A building site or a finished building. */
    public static final class Building {
        public final int id;
        public final Tribe.BuildingType type;
        public final float x;
        public final float z;
        /** 0..1; done at 1. */
        public float progress;
        /** Materials paid: builders may work. */
        public boolean paid;
        /** Placed by the god: built before the tribe's own choices. */
        public final boolean planned;
        /** Refuge made by a finished shelter, or 0. */
        public int refuge;

        public Building(int id, Tribe.BuildingType type, float x, float z, boolean planned) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.z = z;
            this.planned = planned;
        }

        public boolean done() {
            return progress >= 1f;
        }
    }

    public enum Mood {
        CONTENT("spokojený"), CALM("klidný"), AFRAID("ve strachu");

        public final String label;

        Mood(String label) {
            this.label = label;
        }
    }

    private final Tribe.Config config;
    private final Faith faith;
    private final List<Building> buildings = new ArrayList<>();
    private int nextId = 1;

    public Settlement(Tribe.Config config, Faith faith) {
        this.config = config;
        this.faith = faith;
    }

    public Tribe.Config config() {
        return config;
    }

    public List<Building> all() {
        return Collections.unmodifiableList(buildings);
    }

    public Building add(Tribe.BuildingType type, float x, float z, boolean planned) {
        Building building = new Building(nextId++, type, x, z, planned);
        buildings.add(building);
        return building;
    }

    /** Restores a building from a save game. */
    public Building restore(int id, Tribe.BuildingType type, float x, float z, boolean planned, float progress, boolean paid,
                            int refuge) {
        Building building = new Building(id, type, x, z, planned);
        building.progress = progress;
        building.paid = paid;
        building.refuge = refuge;
        buildings.add(building);
        nextId = Math.max(nextId, id + 1);
        return building;
    }

    public int nextId() {
        return nextId;
    }

    public Building get(int id) {
        for (Building building : buildings) {
            if (building.id == id) {
                return building;
            }
        }
        return null;
    }

    /** The site builders work on: god's plans first, then the oldest; only paid sites. Null if none. */
    public Building activeSite() {
        Building best = null;
        for (Building building : buildings) {
            if (building.done() || !building.paid) {
                continue;
            }
            if (best == null || (building.planned && !best.planned)) {
                best = building;
            }
        }
        return best;
    }

    /** Sites waiting for materials (plans first, then by age). */
    public List<Building> unpaid() {
        List<Building> list = new ArrayList<>();
        for (Building building : buildings) {
            if (!building.done() && !building.paid) {
                list.add(building);
            }
        }
        list.sort((a, b) -> a.planned != b.planned ? (a.planned ? -1 : 1) : Integer.compare(a.id, b.id));
        return list;
    }

    public int count(String typeId, boolean doneOnly) {
        int count = 0;
        for (Building building : buildings) {
            if (building.type.id().equals(typeId) && (!doneOnly || building.done())) {
                count++;
            }
        }
        return count;
    }

    /** Kinds of finished buildings. */
    public Set<String> doneTypes() {
        Set<String> types = new TreeSet<>();
        for (Building building : buildings) {
            if (building.done()) {
                types.add(building.type.id());
            }
        }
        return types;
    }

    // ---------------------------------------------------------------- effects

    /** Warmth added at a point by finished fires in reach (0 if none). */
    public float warmthAt(float x, float z) {
        float warmth = 0f;
        for (Building building : buildings) {
            if (building.done() && "warmth".equals(building.type.effect())
                    && Math.hypot(building.x - x, building.z - z) <= building.type.radius()) {
                warmth = Math.max(warmth, building.type.value());
            }
        }
        return warmth;
    }

    /** How much more a camp stores (each finished store multiplies; 1 without). */
    public float storageFactor() {
        float factor = 1f;
        for (Building building : buildings) {
            if (building.done() && "storage".equals(building.type.effect())) {
                factor *= building.type.value();
            }
        }
        return factor;
    }

    /** Extra faith from the tribe's believers (0 without a shrine). */
    public float faithBonus() {
        if (faith == null) {
            return 0f;
        }
        float bonus = 0f;
        for (Building building : buildings) {
            if (building.done() && "faith".equals(building.type.effect())) {
                bonus += building.type.value();
            }
        }
        return bonus;
    }

    // ---------------------------------------------------------------- morality

    public Mood mood() {
        if (faith == null) {
            return Mood.CALM; // a tribe without the god (the rival, phase 11)
        }
        float alignment = faith.alignment();
        float threshold = config.tribe().moralityThreshold();
        if (alignment > threshold) {
            return Mood.CONTENT;
        }
        return alignment < -threshold ? Mood.AFRAID : Mood.CALM;
    }

    /** Pause between young in the tribe, relative. */
    public float birthFactor() {
        return switch (mood()) {
            case CONTENT -> config.tribe().goodBirthFactor();
            case AFRAID -> config.tribe().evilBirthFactor();
            case CALM -> 1f;
        };
    }

    /** Speed of gathering and building, relative. */
    public float workFactor() {
        return mood() == Mood.AFRAID ? config.tribe().evilWorkFactor() : 1f;
    }

    /** Chance per member and minute to run away from the tribe (only under an evil god). */
    public float desertionPerMinute() {
        return mood() == Mood.AFRAID ? config.tribe().desertionPerMinute() * Math.min(1f, -faith.alignment()) : 0f;
    }

    /** Whether this is the player's tribe (the god's faith counts there). */
    public boolean hasFaith() {
        return faith != null;
    }

    /** Removes a building (destroyed by lightning, phase 11c). */
    public void remove(Building building) {
        buildings.remove(building);
    }

    public void clear() {
        buildings.clear();
        nextId = 1;
    }
}

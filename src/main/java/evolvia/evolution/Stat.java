package evolvia.evolution;

/**
 * Species statistics that evolution nodes can change ({@code "stat"} in {@code stat_add} / {@code stat_mul}).
 * Rate stats (hunger, thirst, energy, lifespan, cooldown) start at 1 and multiply the species' base values.
 */
public enum Stat {
    SIZE("size"),
    SPEED("speed"),
    SIGHT("sight"),
    MAX_HEALTH("maxHealth"),
    HUNGER_RATE("hungerRate"),
    THIRST_RATE("thirstRate"),
    ENERGY_DRAIN("energyDrain"),
    LIFESPAN("lifespan"),
    REPRODUCTION_COOLDOWN("reproductionCooldown"),
    LITTER_SIZE("litterSize"),
    COMFORT_MIN("comfortMin"),
    COMFORT_MAX("comfortMax"),
    PLANT_NUTRITION("plantNutrition"),
    MEAT_NUTRITION("meatNutrition");

    private final String key;

    Stat(String key) {
        this.key = key;
    }

    /** Name used in the JSON data files. */
    public String key() {
        return key;
    }

    /** Stat with the given JSON name, or null. */
    public static Stat byKey(String key) {
        for (Stat stat : values()) {
            if (stat.key.equals(key)) {
                return stat;
            }
        }
        return null;
    }
}

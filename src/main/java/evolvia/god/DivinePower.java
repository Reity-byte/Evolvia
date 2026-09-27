package evolvia.god;

/** The god powers of the first version (DESIGN.md §9). */
public enum DivinePower {
    RAIN("rain"),
    ABUNDANCE("abundance"),
    RAISE("raise"),
    LOWER("lower"),
    LIGHTNING("lightning");

    private final String key;

    DivinePower(String key) {
        this.key = key;
    }

    /** Name in {@code data/powers.json}. */
    public String key() {
        return key;
    }

    /** Terrain powers repeat while the mouse button is held. */
    public boolean repeats() {
        return this == RAISE || this == LOWER;
    }
}

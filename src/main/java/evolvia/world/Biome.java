package evolvia.world;

/**
 * Biome definition, loaded from {@code data/biomes.json}.
 *
 * @param index       position in the {@link BiomeTable}
 * @param id          stable identifier (used in data files and saves)
 * @param name        display name (Czech)
 * @param rgb         terrain color as 0xRRGGBB
 * @param water       true for the single biome used for tiles below sea level
 * @param passable    whether creatures can walk on it
 * @param fertility   base fertility 0..1 (food regrowth)
 * @param temperature temperature range 0..1 in which this biome can appear
 * @param moisture    moisture range 0..1 in which this biome can appear
 * @param altitude    altitude range 0..1 (0 = sea level, 1 = highest peak) in which this biome can appear
 */
public record Biome(
        int index,
        String id,
        String name,
        int rgb,
        boolean water,
        boolean passable,
        float fertility,
        Range temperature,
        Range moisture,
        Range altitude) {

    /** Inclusive value range. */
    public record Range(float min, float max) {
        public static final Range ANY = new Range(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);

        public boolean contains(float value) {
            return value >= min && value <= max;
        }
    }

    /** True if a land tile with the given climate belongs to this biome. */
    public boolean matches(float temperatureValue, float moistureValue, float altitudeValue) {
        return temperature.contains(temperatureValue)
                && moisture.contains(moistureValue)
                && altitude.contains(altitudeValue);
    }

    /** True if this biome has no climate conditions (matches every land tile). */
    public boolean isCatchAll() {
        return temperature.equals(Range.ANY) && moisture.equals(Range.ANY) && altitude.equals(Range.ANY);
    }

    public float red() {
        return ((rgb >> 16) & 0xFF) / 255f;
    }

    public float green() {
        return ((rgb >> 8) & 0xFF) / 255f;
    }

    public float blue() {
        return (rgb & 0xFF) / 255f;
    }
}

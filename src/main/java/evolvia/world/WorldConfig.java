package evolvia.world;

/**
 * World generation parameters, loaded from {@code data/world.json}.
 *
 * @param width           map width in tiles (X axis)
 * @param depth           map depth in tiles (Z axis)
 * @param heightScale     terrain height range in world units (1 unit = 1 tile)
 * @param seaLevel        sea level as a fraction of {@code heightScale}
 * @param height          height noise
 * @param heightExponent  exponent applied to normalized height (&gt; 1 = flatter lowlands, sharper peaks)
 * @param edgeFalloff     lowers the terrain towards the map border so the map is surrounded by sea
 * @param temperature     temperature noise
 * @param altitudeCooling how much temperature drops from sea level to the highest peak (0..1)
 * @param moisture        moisture noise
 * @param water           look of the water surface
 * @param time            day and night (phase 9d)
 */
public record WorldConfig(
        int width,
        int depth,
        float heightScale,
        float seaLevel,
        NoiseSettings height,
        float heightExponent,
        EdgeFalloff edgeFalloff,
        NoiseSettings temperature,
        float altitudeCooling,
        NoiseSettings moisture,
        WaterSettings water,
        TimeSettings time) {

    /**
     * @param scale      feature size in tiles of the first octave
     * @param octaves    number of noise layers
     * @param lacunarity frequency multiplier per octave
     * @param gain       amplitude multiplier per octave
     */
    public record NoiseSettings(float scale, int octaves, float lacunarity, float gain) {
    }

    /**
     * @param width     distance from the border (tiles) over which the terrain rises to full height
     * @param minFactor height multiplier right at the border
     */
    public record EdgeFalloff(float width, float minFactor) {
    }

    /**
     * @param color        hex color, e.g. {@code "#2f6fa8"}
     * @param alpha        opacity 0..1
     * @param shallowDepth water up to this depth below sea level (world units) counts as shallow: swimmers can cross it
     */
    public record WaterSettings(String color, float alpha, float shallowDepth) {
    }

    /**
     * @param dayLengthSeconds length of one day and night in game seconds
     * @param startTimeOfDay   time of day a new world starts at (0 = midnight, 0.5 = noon)
     * @param nightCooling     how much colder (0..1) the night is for creatures not in a refuge
     */
    public record TimeSettings(float dayLengthSeconds, float startTimeOfDay, float nightCooling) {
    }

    /** Throws {@link IllegalStateException} with a clear message if a value is missing or out of range. */
    public void validate(String source) {
        require(width > 0 && depth > 0, source, "width and depth must be positive");
        require(heightScale > 0, source, "heightScale must be positive");
        require(seaLevel >= 0 && seaLevel < 1, source, "seaLevel must be in [0, 1)");
        require(heightExponent > 0, source, "heightExponent must be positive");
        require(altitudeCooling >= 0 && altitudeCooling <= 1, source, "altitudeCooling must be in [0, 1]");
        validateNoise(height, "height", source);
        validateNoise(temperature, "temperature", source);
        validateNoise(moisture, "moisture", source);
        require(edgeFalloff != null, source, "edgeFalloff is missing");
        require(edgeFalloff.width() >= 0, source, "edgeFalloff.width must not be negative");
        require(water != null && water.color() != null, source, "water.color is missing");
        require(water.alpha() >= 0 && water.alpha() <= 1, source, "water.alpha must be in [0, 1]");
        require(water.shallowDepth() >= 0, source, "water.shallowDepth must not be negative");
        require(time != null && time.dayLengthSeconds() > 0 && time.startTimeOfDay() >= 0 && time.startTimeOfDay() < 1
                && time.nightCooling() >= 0, source, "time: dayLengthSeconds > 0, startTimeOfDay in [0, 1), nightCooling >= 0");
    }

    private static void validateNoise(NoiseSettings noise, String name, String source) {
        require(noise != null, source, name + " noise settings are missing");
        require(noise.scale() > 0, source, name + ".scale must be positive");
        require(noise.octaves() >= 1, source, name + ".octaves must be at least 1");
        require(noise.lacunarity() > 0, source, name + ".lacunarity must be positive");
        require(noise.gain() > 0, source, name + ".gain must be positive");
    }

    private static void require(boolean condition, String source, String message) {
        if (!condition) {
            throw new IllegalStateException(source + ": " + message);
        }
    }
}

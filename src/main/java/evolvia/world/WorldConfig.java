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
 * @param relief          plains with hills here and there (after phase 10); null = the height noise as it is
 * @param climate         large climate zones, snow only on mountains (after phase 10); null = the noise as it is
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
        TimeSettings time,
        Relief relief,
        ClimateShape climate) {

    /**
     * Large climate zones (after phase 10).
     *
     * @param latitudeWeight share of the temperature that follows the map from north (cold) to south (warm)
     * @param lowlandFloor   the coldest lowland is this warm (0..1)
     * @param contrast       how far the temperatures spread from the middle (1 = the whole 0..1 range); lower keeps
     *                       more of the land mild while the zones stay large
     */
    public record ClimateShape(float latitudeWeight, float lowlandFloor, float contrast) {
    }

    /**
     * The shape of the land (after phase 10): the height noise only decides where the land is; the land itself is a
     * plain that rises gently from the coast, with hills where a second noise is high. Heights are fractions of
     * {@code heightScale}.
     *
     * @param plainHeight    how high the plains are above the sea
     * @param coastWidth     over this share of the land noise the land rises from the sea to the plain (beaches)
     * @param ripple         undulation of the plains
     * @param rippleScale    size of the undulation in tiles
     * @param hills          noise that places the hills
     * @param hillThreshold  hills where the hill noise (0..1) is above this
     * @param hillSoftness   how gradually hills rise from the plain (noise range)
     * @param hillHeight     height of the hills; they grow up to twice as high deep inland
     * @param mountainThreshold where the hill noise is above this, the hill grows into a mountain
     * @param mountainHeight extra height of the mountains (their tops get snow through {@code altitudeCooling})
     */
    public record Relief(float plainHeight, float coastWidth, float ripple, float rippleScale, NoiseSettings hills,
                         float hillThreshold, float hillSoftness, float hillHeight, float mountainThreshold,
                         float mountainHeight) {
    }

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
        require(altitudeCooling >= 0 && altitudeCooling <= 2, source, "altitudeCooling must be in [0, 2]");
        validateNoise(height, "height", source);
        validateNoise(temperature, "temperature", source);
        validateNoise(moisture, "moisture", source);
        require(edgeFalloff != null, source, "edgeFalloff is missing");
        require(edgeFalloff.width() >= 0, source, "edgeFalloff.width must not be negative");
        require(water != null && water.color() != null, source, "water.color is missing");
        require(water.alpha() >= 0 && water.alpha() <= 1, source, "water.alpha must be in [0, 1]");
        require(water.shallowDepth() >= 0, source, "water.shallowDepth must not be negative");
        if (relief != null) {
            validateNoise(relief.hills(), "relief.hills", source);
            require(relief.plainHeight() >= 0 && relief.coastWidth() > 0 && relief.ripple() >= 0 && relief.rippleScale() > 0
                            && relief.hillThreshold() >= 0 && relief.hillThreshold() < 1 && relief.hillSoftness() > 0
                            && relief.hillHeight() >= 0 && seaLevel + relief.plainHeight() + 2 * relief.hillHeight() <= 1.5f, source,
                    "relief: non-negative heights, coastWidth, rippleScale and hillSoftness > 0, hillThreshold in [0, 1)");
            require(relief.mountainThreshold() > relief.hillThreshold() && relief.mountainThreshold() < 1
                    && relief.mountainHeight() >= 0, source, "relief: hillThreshold < mountainThreshold < 1, mountainHeight >= 0");
        }
        if (climate != null) {
            require(climate.latitudeWeight() >= 0 && climate.latitudeWeight() <= 1 && climate.lowlandFloor() >= 0
                    && climate.lowlandFloor() < 1 && climate.contrast() > 0 && climate.contrast() <= 1, source,
                    "climate: latitudeWeight in [0, 1], lowlandFloor in [0, 1), contrast in (0, 1]");
        }
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

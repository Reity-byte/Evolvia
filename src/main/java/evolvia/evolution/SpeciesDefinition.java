package evolvia.evolution;

import evolvia.core.Time;

/**
 * Base stats of the starting species, loaded from {@code data/species.json}.
 * The evolution tree (phase 5) will modify these.
 *
 * @param id                    stable identifier
 * @param name                  display name (Czech)
 * @param rgb                   body color as 0xRRGGBB
 * @param bodySize              size in tiles
 * @param speed                 walking speed in tiles per game second
 * @param wanderRadius          maximum distance of a wander target in tiles
 * @param wanderPauseMinSeconds shortest pause between wander walks
 * @param wanderPauseMaxSeconds longest pause between wander walks
 * @param startingPopulation    number of creatures at world start
 */
public record SpeciesDefinition(
        String id,
        String name,
        int rgb,
        float bodySize,
        float speed,
        float wanderRadius,
        float wanderPauseMinSeconds,
        float wanderPauseMaxSeconds,
        int startingPopulation) {

    /** Walking speed in tiles per tick. */
    public float speedPerTick() {
        return speed / Time.TICKS_PER_SECOND;
    }

    public static int secondsToTicks(float seconds) {
        return Math.round(seconds * Time.TICKS_PER_SECOND);
    }
}

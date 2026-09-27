package evolvia.evolution;

import evolvia.core.Time;

/**
 * Base stats of the starting species, loaded from {@code data/species.json}.
 * The evolution tree (phase 5) will modify these. Rates are per game second; convert with
 * {@link #perTick(float)} / {@link #secondsToTicks(float)}.
 *
 * @param id                 stable identifier
 * @param name               display name (Czech)
 * @param rgb                body color as 0xRRGGBB
 * @param bodySize           size in tiles
 * @param speed              walking speed in tiles per second
 * @param maxHealth          health of a healthy adult
 * @param lifespanMinSeconds shortest natural lifespan
 * @param lifespanMaxSeconds longest natural lifespan
 * @param senseRadius        how far (tiles) a creature looks for food and water
 * @param needs              how fast needs grow and what they do to health
 * @param eating             how eating and drinking satisfy needs
 * @param ai                 utility AI tuning
 * @param wander             wandering behaviour
 * @param startingPopulation number of creatures at world start
 */
public record SpeciesDefinition(
        String id,
        String name,
        int rgb,
        float bodySize,
        float speed,
        float maxHealth,
        float lifespanMinSeconds,
        float lifespanMaxSeconds,
        float senseRadius,
        NeedRates needs,
        Eating eating,
        AiTuning ai,
        Wander wander,
        int startingPopulation) {

    /**
     * @param hungerPerSecond        hunger increase (0 = full, 1 = starving)
     * @param thirstPerSecond        thirst increase (0 = quenched, 1 = dying of thirst)
     * @param energyDrainPerSecond   energy loss while awake (1 = rested, 0 = exhausted)
     * @param energyRecoverPerSecond energy gain while sleeping
     * @param sleepingNeedFactor     hunger/thirst rate multiplier while sleeping
     * @param damagePerSecond        health loss while starving or dying of thirst (need at 1)
     * @param healthRegenPerSecond   health gain while neither starving nor thirsty
     */
    public record NeedRates(float hungerPerSecond, float thirstPerSecond, float energyDrainPerSecond,
                            float energyRecoverPerSecond, float sleepingNeedFactor,
                            float damagePerSecond, float healthRegenPerSecond) {
    }

    /**
     * @param hungerPerUnit         hunger removed by one food unit
     * @param secondsPerUnit        time to eat one food unit
     * @param thirstReliefPerSecond thirst removed per second of drinking
     */
    public record Eating(float hungerPerUnit, float secondsPerUnit, float thirstReliefPerSecond) {
    }

    /**
     * @param evaluateEverySeconds how often each creature re-evaluates its action
     * @param switchMargin         a new action must score this much higher to interrupt the current one
     * @param needThreshold        hunger/thirst level at which a creature starts looking for food/water
     * @param sleepThreshold       tiredness (1 - energy) at which a creature wants to sleep
     * @param wanderScore          constant utility of wandering (the "nothing better to do" action)
     * @param exploreRadiusFactor wander radius multiplier while hungry or thirsty (searching for resources)
     */
    public record AiTuning(float evaluateEverySeconds, float switchMargin, float needThreshold,
                           float sleepThreshold, float wanderScore, float exploreRadiusFactor) {
    }

    /**
     * @param radius          maximum distance of a wander target in tiles
     * @param pauseMinSeconds shortest pause after a wander walk
     * @param pauseMaxSeconds longest pause after a wander walk
     */
    public record Wander(float radius, float pauseMinSeconds, float pauseMaxSeconds) {
    }

    /** Walking speed in tiles per tick. */
    public float speedPerTick() {
        return perTick(speed);
    }

    /** Converts a per-second rate to a per-tick rate. */
    public static float perTick(float perSecond) {
        return perSecond / Time.TICKS_PER_SECOND;
    }

    public static int secondsToTicks(float seconds) {
        return Math.round(seconds * Time.TICKS_PER_SECOND);
    }
}

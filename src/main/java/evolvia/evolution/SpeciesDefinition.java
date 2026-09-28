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
 * @param reproduction       when and how creatures reproduce
 * @param genome             individual variation and mutation
  * @param population         starting population and safety cap
 * @param diet               how much each food type nourishes (0 = not eaten)
 * @param climate            temperature comfort range and what happens outside it
 * @param evolution          how fast the species earns evolution points
 * @param groups             how herds form and hold together (with the {@code groups} ability)
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
        Reproduction reproduction,
        GenomeTuning genome,
        Population population,
        Diet diet,
        Climate climate,
        EvolutionRates evolution,
        Groups groups) {

    /**
     * Diet as nutrition per food type: 1 = normal, 0 = the species does not eat it. Herbivore, omnivore
     * and carnivore are just different values (changed by evolution nodes).
     */
    public record Diet(float plantNutrition, float meatNutrition) {

        /** Nutrition multiplier for a food type ({@code plant}, {@code meat}); 0 for unknown types. */
        public float nutrition(String foodType) {
            return switch (foodType) {
                case "plant" -> plantNutrition;
                case "meat" -> meatNutrition;
                default -> 0f;
            };
        }
    }

    /**
     * @param comfortMin        lowest comfortable tile temperature (0..1)
     * @param comfortMax        highest comfortable tile temperature (0..1)
     * @param needFactorPerUnit extra hunger (cold) or thirst (heat) per unit of temperature outside the range
     * @param damageBeyond      beyond this distance from the range the creature also loses health
     * @param damagePerSecond   health loss per second in that case
     */
    public record Climate(float comfortMin, float comfortMax, float needFactorPerUnit, float damageBeyond,
                          float damagePerSecond) {

        /** How far a temperature is outside the comfort range: negative = too cold, positive = too hot, 0 = fine. */
        public float exposure(float temperature) {
            if (temperature < comfortMin) {
                return temperature - comfortMin;
            }
            if (temperature > comfortMax) {
                return temperature - comfortMax;
            }
            return 0f;
        }
    }

    /**
     * @param populationPointsPerMinute   EP per minute times ln(1 + population)
     * @param pointsPerGeneration         EP for every new generation born
     * @param harshPointsPerCreatureMinute EP per minute for each creature living outside its comfort range
     * @param traitStepsPerBirth          a newborn is at most this many stages ahead of its more evolved parent
     */
    public record EvolutionRates(float populationPointsPerMinute, float pointsPerGeneration,
                                 float harshPointsPerCreatureMinute, int traitStepsPerBirth) {
    }

    /**
     * Herds (phase 9a, DESIGN.md §11).
     *
     * @param updateSeconds   how often herds are updated (joining, founding, splitting, leaving)
     * @param joinRadius      a free creature joins a herd whose leader is this close; founders gather within it
     * @param minFounders     free creatures needed nearby to found a new herd
     * @param minSize         smaller herds fall apart
     * @param maxSize         larger herds split in two
     * @param followDistance  members further than this from the leader walk to it
     * @param leaveDistance   members further than this for {@code leaveSeconds} leave the herd
     * @param leaveSeconds    see {@code leaveDistance}
     * @param followScore     utility of following at {@code followDistance} (rises by half towards {@code leaveDistance})
     * @param forageRadius    members look for food and water only this far around their leader...
     * @param urgentNeed      ...unless hunger / thirst is at least this high
     */
    public record Groups(float updateSeconds, float joinRadius, int minFounders, int minSize, int maxSize,
                         float followDistance, float leaveDistance, float leaveSeconds, float followScore,
                         float forageRadius, float urgentNeed) {
    }

    /**
     * @param adultAgeSeconds  age at which a creature is grown up and can reproduce
     * @param cooldownSeconds  minimum time between two reproductions of one creature
     * @param maxNeed          hunger and thirst must both be below this to reproduce
     * @param minHealth        health (fraction of max) needed to reproduce
     * @param hungerCost       hunger added to each parent (reproduction costs food)
     * @param litterSize       offspring per mating
     * @param mateScore        utility of looking for a mate when ready (below urgent needs)
     */
    public record Reproduction(float adultAgeSeconds, float cooldownSeconds, float maxNeed, float minHealth,
                               float hungerCost, int litterSize, float mateScore) {
    }

    /**
     * @param variation maximum deviation of a gene from the species value (0.1 = up to 10 % either way)
     * @param mutation  standard deviation of the random change of a gene at birth
     */
    public record GenomeTuning(float variation, float mutation) {
    }

    /**
     * @param starting    creatures at world start
     * @param spawnRadius starting creatures are placed within this radius (tiles) of one random land
     *                    point; 0 = anywhere on land
     * @param max         safety cap: no reproduction above this population (performance guard)
     */
    public record Population(int starting, float spawnRadius, int max) {
    }

    /** Copy with a different starting population (e.g. for tests). */
    /** Same species with other herd rules (tests). */
    public SpeciesDefinition withGroups(Groups newGroups) {
        return new SpeciesDefinition(id, name, rgb, bodySize, speed, maxHealth, lifespanMinSeconds, lifespanMaxSeconds,
                senseRadius, needs, eating, ai, wander, reproduction, genome, population, diet, climate, evolution, newGroups);
    }

    public SpeciesDefinition withPopulation(Population newPopulation) {
        return new SpeciesDefinition(id, name, rgb, bodySize, speed, maxHealth, lifespanMinSeconds, lifespanMaxSeconds,
                senseRadius, needs, eating, ai, wander, reproduction, genome, newPopulation, diet, climate, evolution, groups);
    }

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

package evolvia.evolution;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Computes a species' current stats from its base definition and its unlocked nodes:
 * base values, then all {@code stat_add} effects, then all {@code stat_mul} effects
 * (so the result does not depend on unlock order). The result is again a {@link SpeciesDefinition},
 * so the simulation reads evolved stats exactly like base stats.
 */
public final class SpeciesStats {

    private SpeciesStats() {
    }

    public static SpeciesDefinition compute(SpeciesDefinition base, List<EvolutionNode> unlocked) {
        Map<Stat, Float> values = baseValues(base);
        for (EvolutionNode node : unlocked) {
            for (Effect effect : node.effects()) {
                if (effect instanceof Effect.StatAdd add) {
                    values.merge(add.stat(), add.value(), Float::sum);
                }
            }
        }
        for (EvolutionNode node : unlocked) {
            for (Effect effect : node.effects()) {
                if (effect instanceof Effect.StatMul mul) {
                    values.merge(mul.stat(), mul.value(), (a, b) -> a * b);
                }
            }
        }
        return build(base, values);
    }

    /** The value of every stat for the unmodified species. */
    public static Map<Stat, Float> baseValues(SpeciesDefinition base) {
        Map<Stat, Float> values = new EnumMap<>(Stat.class);
        values.put(Stat.SIZE, base.bodySize());
        values.put(Stat.SPEED, base.speed());
        values.put(Stat.SIGHT, base.senseRadius());
        values.put(Stat.MAX_HEALTH, base.maxHealth());
        values.put(Stat.HUNGER_RATE, 1f);
        values.put(Stat.THIRST_RATE, 1f);
        values.put(Stat.ENERGY_DRAIN, 1f);
        values.put(Stat.LIFESPAN, 1f);
        values.put(Stat.REPRODUCTION_COOLDOWN, 1f);
        values.put(Stat.LITTER_SIZE, (float) base.reproduction().litterSize());
        values.put(Stat.COMFORT_MIN, base.climate().comfortMin());
        values.put(Stat.COMFORT_MAX, base.climate().comfortMax());
        values.put(Stat.PLANT_NUTRITION, base.diet().plantNutrition());
        values.put(Stat.MEAT_NUTRITION, base.diet().meatNutrition());
        return values;
    }

    private static SpeciesDefinition build(SpeciesDefinition base, Map<Stat, Float> v) {
        SpeciesDefinition.NeedRates needs = base.needs();
        SpeciesDefinition.NeedRates evolvedNeeds = new SpeciesDefinition.NeedRates(
                needs.hungerPerSecond() * positive(v.get(Stat.HUNGER_RATE)),
                needs.thirstPerSecond() * positive(v.get(Stat.THIRST_RATE)),
                needs.energyDrainPerSecond() * positive(v.get(Stat.ENERGY_DRAIN)),
                needs.energyRecoverPerSecond(),
                needs.sleepingNeedFactor(),
                needs.damagePerSecond(),
                needs.healthRegenPerSecond());
        SpeciesDefinition.Reproduction r = base.reproduction();
        SpeciesDefinition.Reproduction evolvedReproduction = new SpeciesDefinition.Reproduction(
                r.adultAgeSeconds(),
                r.cooldownSeconds() * positive(v.get(Stat.REPRODUCTION_COOLDOWN)),
                r.maxNeed(), r.minHealth(), r.hungerCost(),
                Math.max(1, Math.round(v.get(Stat.LITTER_SIZE))),
                r.mateScore());
        float comfortMin = v.get(Stat.COMFORT_MIN);
        float comfortMax = Math.max(comfortMin, v.get(Stat.COMFORT_MAX));
        SpeciesDefinition.Climate c = base.climate();
        float lifespan = positive(v.get(Stat.LIFESPAN));
        return new SpeciesDefinition(
                base.id(), base.name(), base.rgb(),
                positive(v.get(Stat.SIZE)),
                positive(v.get(Stat.SPEED)),
                positive(v.get(Stat.MAX_HEALTH)),
                base.lifespanMinSeconds() * lifespan,
                base.lifespanMaxSeconds() * lifespan,
                positive(v.get(Stat.SIGHT)),
                evolvedNeeds,
                base.eating(),
                base.ai(),
                base.wander(),
                evolvedReproduction,
                base.genome(),
                base.population(),
                new SpeciesDefinition.Diet(Math.max(0f, v.get(Stat.PLANT_NUTRITION)), Math.max(0f, v.get(Stat.MEAT_NUTRITION))),
                new SpeciesDefinition.Climate(comfortMin, comfortMax, c.needFactorPerUnit(), c.damageBeyond(), c.damagePerSecond()),
                base.evolution());
    }

    /** Keeps stats that must stay positive from reaching zero (a node could multiply by a tiny factor). */
    private static float positive(float value) {
        return Math.max(value, 0.01f);
    }
}

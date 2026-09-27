package evolvia.systems;

import evolvia.components.Needs;
import evolvia.components.SpeciesRef;
import evolvia.core.Time;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition.EvolutionRates;

import java.util.function.IntSupplier;

/**
 * Earns evolution points for a species once per game second (DESIGN.md §7.2): from the population
 * size (logarithmic, so huge populations do not snowball), for every new generation, and for each
 * creature living outside its comfort climate ("surviving harsh conditions").
 */
public final class EvolutionSystem implements GameSystem {

    private final Species species;
    private final IntSupplier maxGeneration;
    private int lastGeneration;
    private float pointsPerMinute;

    /**
     * @param maxGeneration highest generation born so far
     */
    public EvolutionSystem(Species species, IntSupplier maxGeneration) {
        this.species = species;
        this.maxGeneration = maxGeneration;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        if (tick % Time.TICKS_PER_SECOND != 0) {
            return;
        }
        ComponentStore<SpeciesRef> creatures = world.store(SpeciesRef.class);
        ComponentStore<Needs> needsStore = world.store(Needs.class);
        int population = 0;
        int harsh = 0;
        for (int i = 0; i < creatures.size(); i++) {
            if (creatures.componentAt(i).species != species) {
                continue;
            }
            population++;
            Needs needs = needsStore.get(creatures.entityAt(i));
            if (needs != null && needs.exposure != 0f) {
                harsh++;
            }
        }

        EvolutionRates rates = species.stats().evolution();
        float perSecond = rates.populationPointsPerMinute() / 60f * (float) Math.log1p(population)
                + rates.harshPointsPerCreatureMinute() / 60f * harsh;
        float points = perSecond;
        int generation = maxGeneration.getAsInt();
        if (generation > lastGeneration) {
            points += (generation - lastGeneration) * rates.pointsPerGeneration();
            lastGeneration = generation;
        }
        species.addPoints(points);
        pointsPerMinute = perSecond * 60f;
    }

    /** Current steady income (population + harsh conditions) in points per game minute, without generation bonuses. */
    public float pointsPerMinute() {
        return pointsPerMinute;
    }
}

package evolvia.systems;

import evolvia.components.Age;
import evolvia.components.Believer;
import evolvia.components.SpeciesRef;
import evolvia.core.Time;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.EvolutionConditions;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Science;

import java.util.function.BooleanSupplier;

/**
 * Knowledge from the people once per game second (phase 10b): adults of the player's people who can speak make
 * {@code basePerMinute × log(1 + speakers)} knowledge per minute, times their average learning (Wit) and the tribe
 * factor. Deliveries and finished buildings add more ({@link Science#rules()}, rewarded by the actions).
 */
public final class ScienceSystem implements GameSystem {

    private final Science science;
    private final Species species;
    private final EvolutionConditions conditions;
    private final BooleanSupplier hasTribe;

    public ScienceSystem(Science science, Species species, EvolutionConditions conditions, BooleanSupplier hasTribe) {
        this.science = science;
        this.species = species;
        this.conditions = conditions;
        this.hasTribe = hasTribe;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        if (tick % Time.TICKS_PER_SECOND != 0) {
            return;
        }
        if (!science.isActive()) {
            science.setPerMinute(0f);
            return;
        }
        Science.Rules rules = science.rules();
        ComponentStore<SpeciesRef> creatures = world.store(SpeciesRef.class);
        ComponentStore<Believer> believers = world.store(Believer.class);
        ComponentStore<Age> ages = world.store(Age.class);
        int speakers = 0;
        float learning = 0f;
        for (int i = 0; i < creatures.size(); i++) {
            SpeciesRef ref = creatures.componentAt(i);
            int entity = creatures.entityAt(i);
            if (ref.species != species || !believers.has(entity) || !ref.hasAbility(rules.ability())) {
                continue;
            }
            SpeciesDefinition stats = ref.stats();
            Age age = ages.get(entity);
            if (age == null || age.ageTicks < SpeciesDefinition.secondsToTicks(stats.reproduction().adultAgeSeconds())) {
                continue; // the young only listen
            }
            speakers++;
            learning += stats.skills().learning();
        }
        float perMinute = speakers == 0 ? 0f : rules.basePerMinute() * (float) Math.log1p(speakers) * (learning / speakers)
                * (hasTribe.getAsBoolean() ? rules.tribeFactor() : 1f);
        science.setPerMinute(perMinute);
        science.add(perMinute / 60f, conditions);
    }
}

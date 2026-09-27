package evolvia.systems;

import evolvia.components.Genome;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.components.SpeciesRef;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.SpeciesDefinition;
import evolvia.evolution.SpeciesDefinition.NeedRates;

/**
 * Grows hunger and thirst, drains energy while awake and restores it while sleeping.
 * A creature at full hunger or thirst loses health; otherwise health slowly regenerates.
 * Rates come from the creature's species.
 */
public final class NeedsSystem implements GameSystem {

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<Needs> needsStore = world.store(Needs.class);
        ComponentStore<SpeciesRef> species = world.store(SpeciesRef.class);
        ComponentStore<Health> healths = world.store(Health.class);
        ComponentStore<Genome> genomes = world.store(Genome.class);

        for (int i = 0; i < needsStore.size(); i++) {
            int entity = needsStore.entityAt(i);
            SpeciesRef ref = species.get(entity);
            if (ref == null) {
                continue;
            }
            Needs needs = needsStore.componentAt(i);
            NeedRates rates = ref.species.needs();

            float factor = needs.sleeping ? rates.sleepingNeedFactor() : 1f;
            Genome genome = genomes.get(entity);
            float metabolism = genome != null ? genome.size : 1f; // bigger bodies need more food
            needs.hunger = Math.min(1f, needs.hunger + SpeciesDefinition.perTick(rates.hungerPerSecond()) * factor * metabolism);
            needs.thirst = Math.min(1f, needs.thirst + SpeciesDefinition.perTick(rates.thirstPerSecond()) * factor);
            if (needs.sleeping) {
                needs.energy = Math.min(1f, needs.energy + SpeciesDefinition.perTick(rates.energyRecoverPerSecond()));
            } else {
                needs.energy = Math.max(0f, needs.energy - SpeciesDefinition.perTick(rates.energyDrainPerSecond()));
            }

            Health health = healths.get(entity);
            if (health == null) {
                continue;
            }
            if (needs.hunger >= 1f || needs.thirst >= 1f) {
                health.hp -= SpeciesDefinition.perTick(rates.damagePerSecond());
            } else if (health.hp < health.maxHp) {
                health.hp = Math.min(health.maxHp, health.hp + SpeciesDefinition.perTick(rates.healthRegenPerSecond()));
            }
        }
    }
}

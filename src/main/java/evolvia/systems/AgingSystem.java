package evolvia.systems;

import evolvia.components.Age;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.world.DeathStats;

/**
 * Aging and death: creatures age every tick and die of old age at their maximum age, or when
 * their health reaches zero (from starvation or thirst). Death is deferred to the end of the tick.
 */
public final class AgingSystem implements GameSystem {

    private final DeathStats deaths;

    public AgingSystem(DeathStats deaths) {
        this.deaths = deaths;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<Age> ages = world.store(Age.class);
        for (int i = 0; i < ages.size(); i++) {
            Age age = ages.componentAt(i);
            age.ageTicks++;
            if (age.ageTicks == age.maxAgeTicks) {
                world.destroyEntity(ages.entityAt(i));
                deaths.record(DeathStats.Cause.OLD_AGE);
            }
        }

        ComponentStore<Health> healths = world.store(Health.class);
        for (int i = 0; i < healths.size(); i++) {
            if (healths.componentAt(i).hp > 0f) {
                continue;
            }
            int entity = healths.entityAt(i);
            Age age = ages.get(entity);
            if (age != null && age.ageTicks >= age.maxAgeTicks) {
                continue; // already died of old age this tick
            }
            Needs needs = world.get(entity, Needs.class);
            boolean starving = needs == null || needs.hunger >= needs.thirst;
            world.destroyEntity(entity);
            deaths.record(starving ? DeathStats.Cause.STARVATION : DeathStats.Cause.THIRST);
        }
    }
}

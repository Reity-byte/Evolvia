package evolvia.systems;

import evolvia.components.Age;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.world.DeathStats;
import evolvia.world.SpatialGrid;

/**
 * Aging and death: creatures age every tick and die of old age at their maximum age, or when
 * their health reaches zero (from starvation or thirst). Death is deferred to the end of the tick.
 */
public final class AgingSystem implements GameSystem {

    private final DeathStats deaths;
    private final SpatialGrid creatureGrid;

    public AgingSystem(DeathStats deaths, SpatialGrid creatureGrid) {
        this.deaths = deaths;
        this.creatureGrid = creatureGrid;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<Age> ages = world.store(Age.class);
        for (int i = 0; i < ages.size(); i++) {
            Age age = ages.componentAt(i);
            age.ageTicks++;
            if (age.ageTicks == age.maxAgeTicks) {
                die(world, ages.entityAt(i), DeathStats.Cause.OLD_AGE);
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
            die(world, entity, starving ? DeathStats.Cause.STARVATION : DeathStats.Cause.THIRST);
        }
    }

    private void die(EcsWorld world, int entity, DeathStats.Cause cause) {
        Transform t = world.get(entity, Transform.class);
        if (t != null) {
            creatureGrid.remove(entity, t.position.x, t.position.z);
        }
        world.destroyEntity(entity);
        deaths.record(cause);
    }
}

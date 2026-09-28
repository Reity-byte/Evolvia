package evolvia.systems;

import evolvia.components.Age;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.components.UnderAttack;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.world.DeathStats;
import evolvia.world.SpatialGrid;

/**
 * Aging and death: creatures age every tick and die of old age at their maximum age, or when
  * their health reaches zero (starvation, thirst or a harsh climate). Death is deferred to the end of the tick;
 * the {@link DeathListener} is told where the creature died (e.g. to leave a carcass).
 */
public final class AgingSystem implements GameSystem {

    /** Told about every death, after the creature is scheduled for removal. */
    @FunctionalInterface
    public interface DeathListener {
        void died(int entity, float x, float z);
    }

    private final DeathStats deaths;
    private final SpatialGrid creatureGrid;
    private final DeathListener listener;

    public AgingSystem(DeathStats deaths, SpatialGrid creatureGrid, DeathListener listener) {
        this.deaths = deaths;
        this.creatureGrid = creatureGrid;
        this.listener = listener;
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
            UnderAttack attacked = world.get(entity, UnderAttack.class);
            DeathStats.Cause cause;
            if (attacked != null && attacked.isActive(tick)) {
                cause = DeathStats.Cause.FIGHT;
            } else if (needs == null || (needs.hunger >= 1f && needs.hunger >= needs.thirst)) {
                cause = DeathStats.Cause.STARVATION;
            } else if (needs.thirst >= 1f) {
                cause = DeathStats.Cause.THIRST;
            } else {
                cause = DeathStats.Cause.EXPOSURE; // health lost to a climate the species is not adapted to
            }
            die(world, entity, cause);
        }
    }

    /** Kills a creature now (removal deferred to the end of the tick), counting the cause and telling the listener. */
    public void die(EcsWorld world, int entity, DeathStats.Cause cause) {
        Transform t = world.get(entity, Transform.class);
        if (t != null) {
            creatureGrid.remove(entity, t.position.x, t.position.z);
        }
        world.destroyEntity(entity);
        deaths.record(cause);
        if (t != null) {
            listener.died(entity, t.position.x, t.position.z);
        }
    }
}

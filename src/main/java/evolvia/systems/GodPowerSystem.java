package evolvia.systems;

import evolvia.components.Believer;
import evolvia.components.Fear;
import evolvia.components.Needs;
import evolvia.components.ResourceNode;
import evolvia.components.SpeciesRef;
import evolvia.core.Time;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.DivinePower;
import evolvia.god.Faith;
import evolvia.god.GodConfig;
import evolvia.god.GodPowers;
import evolvia.world.ResourceKind;
import evolvia.world.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Applies the god powers the player used since the last tick (DESIGN.md §9), paying their faith and
 * moving the alignment, and runs the active rain clouds: food under a cloud regrows faster and becomes
 * divine, thirsty creatures under it drink the rain and start believing.
 */
public final class GodPowerSystem implements GameSystem {

    /** Creatures at least this thirsty feel the rain (and thank the god for it). */
    private static final float RAIN_THIRST = 0.05f;

    private final World world;
    private final GodPowers powers;
    private final List<Integer> rained = new ArrayList<>();

    public GodPowerSystem(World world, GodPowers powers) {
        this.world = world;
        this.powers = powers;
    }

    @Override
    public void update(EcsWorld ecs, int tick) {
        applyQueued(tick);
        updateRains(ecs, tick);
        if (tick % Time.TICKS_PER_SECOND == 0) {
            removeOldFears(ecs, tick);
        }
    }

    /**
     * Applies the queued powers now. Runs at the start of every tick; while the game is paused the
     * game calls it directly (with the number of the next tick), so powers work during a pause too.
     */
    public void applyQueued(int tick) {
        for (GodPowers.Command command : powers.takeQueued()) {
            apply(command, tick);
        }
        for (GodPowers.PlanCommand plan : powers.takeQueuedPlans()) {
            if (powers.faith().canAfford(plan.cost()) && world.planBuilding(plan.building(), plan.x(), plan.z())) {
                powers.faith().spend(plan.cost());
            }
        }
        for (GodPowers.HandCommand command : powers.takeQueuedHand()) {
            float cost = powers.config().hand().cost(command.action());
            if (powers.faith().canAfford(cost) && world.applyHand(command, tick)) {
                powers.faith().spend(cost);
                powers.faith().recordAct(powers.config().hand().alignment(command.action()));
            }
        }
    }

    private void apply(GodPowers.Command command, int tick) {
        GodConfig config = powers.config();
        GodConfig.Power power = config.of(command.power());
        Faith faith = powers.faith();
        if (command.power() == DivinePower.SANCTIFY
                && world.refuges().nearest(command.x(), command.z(), power.radius(), false) == null) {
            return; // nothing to sanctify there: nothing is paid
        }
        if (!faith.spend(power.cost())) {
            return; // cannot happen through the UI (it checks before queueing)
        }
        faith.recordAct(power.alignment());
        float x = command.x();
        float z = command.z();
        switch (command.power()) {
            case RAIN -> powers.addRain(new GodPowers.RainArea(x, z, power.radius(), tick,
                    tick + SpeciesDefinition.secondsToTicks(config.rain().durationSeconds())));
            case ABUNDANCE -> world.abundance(x, z, power.radius(), config.abundance().resource(), config.abundance().newNodes());
            case RAISE -> world.changeTerrain(x, z, power.radius(), config.raise().step());
            case LOWER -> world.changeTerrain(x, z, power.radius(), -config.lower().step());
            case SANCTIFY -> world.sanctify(x, z, power.radius());
            case LIGHTNING -> {
                GodConfig.Lightning l = config.lightning();
                world.lightning(x, z, l.radius(), l.maxKills(), l.scareRadius(),
                        SpeciesDefinition.secondsToTicks(l.scareSeconds()), l.fleeDistance(), tick);
            }
        }
        powers.recordStrike(new GodPowers.Strike(command.power(), x, z, power.radius(), tick));
    }

    private void updateRains(EcsWorld ecs, int tick) {
        GodConfig.Rain rain = powers.config().rain();
        ComponentStore<ResourceNode> nodes = ecs.store(ResourceNode.class);
        ComponentStore<Needs> needsStore = ecs.store(Needs.class);
        float relief = SpeciesDefinition.perTick(rain.thirstReliefPerSecond());
        for (Iterator<GodPowers.RainArea> it = powers.rains().iterator(); it.hasNext(); ) {
            GodPowers.RainArea area = it.next();
            if (tick >= area.endTick()) {
                it.remove();
                continue;
            }
            world.nature().extinguish(area.x(), area.z(), area.radius(), tick); // the god's rain puts out fires
            world.resourceGrid(ResourceKind.FOOD).forEachWithin(area.x(), area.z(), area.radius(), entity -> {
                ResourceNode node = nodes.get(entity);
                if (node == null || node.type.decays()) {
                    return;
                }
                node.divine = true;
                if (node.amount < node.type.capacity()) {
                    node.amount = Math.min(node.type.capacity(), node.amount + node.regrowPerTick * (rain.regrowMultiplier() - 1f));
                }
            });
            rained.clear();
            world.creatureGrid().forEachWithin(area.x(), area.z(), area.radius(), rained::add);
            rained.sort(null); // by ID: independent of the grid's internal order (save games)
            for (int entity : rained) {
                Needs needs = needsStore.get(entity);
                if (needs != null && needs.thirst >= RAIN_THIRST) {
                    needs.thirst = Math.max(0f, needs.thirst - relief);
                    SpeciesRef ref = ecs.get(entity, SpeciesRef.class);
                    if (ref != null && !ref.species.isAnimal() && ecs.get(entity, Believer.class) == null) {
                        ecs.add(entity, new Believer()); // drank the god's rain
                    }
                }
            }
        }
    }

    private static void removeOldFears(EcsWorld ecs, int tick) {
        ComponentStore<Fear> fears = ecs.store(Fear.class);
        for (int i = fears.size() - 1; i >= 0; i--) {
            if (!fears.componentAt(i).isActive(tick)) {
                fears.remove(fears.entityAt(i));
            }
        }
    }
}

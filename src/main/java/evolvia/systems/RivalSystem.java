package evolvia.systems;

import evolvia.components.SpeciesRef;
import evolvia.core.Time;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.Species;
import evolvia.world.Rivals;

/**
 * The rival people (phase 11), once per game second: when the game minute of the next step of its plan has come,
 * the rival gets that evolution node, and every rival creature at once takes the new look and traits (the rival
 * does not wait for generations).
 */
public final class RivalSystem implements GameSystem {

    private final Rivals.Config config;
    /** Steps of the plan done so far. */
    private int step;

    public RivalSystem(Rivals.Config config) {
        this.config = config;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        if (tick % Time.TICKS_PER_SECOND != 0) {
            return;
        }
        float minute = tick / (60f * Time.TICKS_PER_SECOND);
        boolean evolved = false;
        while (step < config.plan().size() && config.plan().get(step).minute() <= minute) {
            evolved |= config.species().grant(config.plan().get(step).node());
            step++;
        }
        if (evolved) {
            Species rival = config.species();
            ComponentStore<SpeciesRef> refs = world.store(SpeciesRef.class);
            for (int i = 0; i < refs.size(); i++) {
                if (refs.componentAt(i).species == rival) {
                    refs.componentAt(i).stage = rival.latestStage().index();
                }
            }
        }
    }

    /** Steps of the plan done (save games). */
    public int step() {
        return step;
    }

    /** Restores the plan's progress and the nodes it gave (save games). */
    public void restore(int step) {
        this.step = Math.clamp(step, 0, config.plan().size());
        for (String node : config.start()) {
            config.species().grant(node);
        }
        for (int i = 0; i < this.step; i++) {
            config.species().grant(config.plan().get(i).node());
        }
    }
}

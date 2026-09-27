package evolvia.systems;

import evolvia.components.Believer;
import evolvia.core.Time;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.god.Faith;
import evolvia.god.GodConfig;

/**
 * Faith income once per game second (DESIGN.md §9): a small base (so a game without believers does
 * not get stuck) plus a fixed amount for every believing creature.
 */
public final class FaithSystem implements GameSystem {

    private final Faith faith;
    private final GodConfig.FaithSettings settings;

    public FaithSystem(Faith faith, GodConfig.FaithSettings settings) {
        this.faith = faith;
        this.settings = settings;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        if (tick % Time.TICKS_PER_SECOND != 0) {
            return;
        }
        int believers = world.store(Believer.class).size();
        float perMinute = settings.basePerMinute() + settings.perBelieverPerMinute() * believers;
        faith.add(perMinute / 60f);
        faith.setIncome(believers, perMinute);
    }
}

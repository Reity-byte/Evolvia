package evolvia.systems;

import evolvia.components.Believer;
import evolvia.core.Time;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.god.Faith;
import evolvia.god.GodConfig;

import java.util.function.IntSupplier;

/**
 * Faith income once per game second (DESIGN.md §9): a small base (so a game without believers does
 * not get stuck) plus a fixed amount for every believing creature.
 */
public final class FaithSystem implements GameSystem {

    private final Faith faith;
    private final GodConfig.FaithSettings settings;
    private final IntSupplier sacredSleepers;
    private final float perSacredSleeper;
    private final java.util.function.DoubleSupplier bonusPerMinute;

    /**
     * @param sacredSleepers   believers asleep at a sacred place now (phase 9d)
     * @param perSacredSleeper extra faith per minute for each of them
     */
    public FaithSystem(Faith faith, GodConfig.FaithSettings settings, IntSupplier sacredSleepers, float perSacredSleeper) {
        this(faith, settings, sacredSleepers, perSacredSleeper, () -> 0);
    }

    /** @param bonusPerMinute extra faith per minute (the tribe's shrine, phase 9h) */
    public FaithSystem(Faith faith, GodConfig.FaithSettings settings, IntSupplier sacredSleepers, float perSacredSleeper,
                       java.util.function.DoubleSupplier bonusPerMinute) {
        this.bonusPerMinute = bonusPerMinute;
        this.faith = faith;
        this.settings = settings;
        this.sacredSleepers = sacredSleepers;
        this.perSacredSleeper = perSacredSleeper;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        if (tick % Time.TICKS_PER_SECOND != 0) {
            return;
        }
        int believers = world.store(Believer.class).size();
        float perMinute = settings.basePerMinute() + settings.perBelieverPerMinute() * believers
                + perSacredSleeper * sacredSleepers.getAsInt() + (float) bonusPerMinute.getAsDouble();
        faith.add(perMinute / 60f);
        faith.setIncome(believers, perMinute);
    }
}

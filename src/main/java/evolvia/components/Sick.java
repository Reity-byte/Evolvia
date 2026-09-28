package evolvia.components;

import evolvia.ecs.EcsWorld;

/**
 * A creature ill from spoiled food (phase 9e): it loses health and tires faster until {@link #untilTick},
 * then stays immune until {@link #immuneUntilTick} (the component is removed after that).
 */
public final class Sick {

    public int untilTick;
    public int immuneUntilTick;

    public boolean isActive(int tick) {
        return tick < untilTick;
    }

    /**
     * Makes a creature ill unless it is ill or immune already.
     *
     * @return true if it fell ill
     */
    public static boolean infect(EcsWorld ecs, int entity, int tick, int durationTicks, int immuneTicks) {
        if (ecs.get(entity, Sick.class) != null) {
            return false;
        }
        Sick sick = ecs.add(entity, new Sick());
        sick.untilTick = tick + durationTicks;
        sick.immuneUntilTick = sick.untilTick + immuneTicks;
        return true;
    }
}

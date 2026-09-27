package evolvia.god;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * God power state of a world: faith, the queue of powers the player has used (applied at the start
 * of the next tick by the {@code GodPowerSystem}, so the simulation stays deterministic), active rain
 * clouds and recent strikes (for visual effects).
 */
public final class GodPowers {

    /** A power used by the player at a ground point. */
    public record Command(DivinePower power, float x, float z) {
    }

    /** An active rain cloud. */
    public record RainArea(float x, float z, float radius, int startTick, int endTick) {
    }

    /** A power that was applied (for visual effects). */
    public record Strike(DivinePower power, float x, float z, float radius, int tick) {
    }

    private static final int MAX_STRIKES = 32;

    private final GodConfig config;
    private final Faith faith;
    private final List<Command> queue = new ArrayList<>();
    private final List<RainArea> rains = new ArrayList<>();
    private final Deque<Strike> strikes = new ArrayDeque<>();
    private float queuedCost;

    public GodPowers(GodConfig config) {
        this.config = config;
        this.faith = new Faith(config.faith().starting());
    }

    public GodConfig config() {
        return config;
    }

    public Faith faith() {
        return faith;
    }

    /** True if the player has enough faith for the power (counting powers already queued). */
    public boolean canAfford(DivinePower power) {
        return faith.canAfford(queuedCost + config.of(power).cost());
    }

    /**
     * Queues a power at a ground point; it takes effect at the start of the next tick.
     *
     * @return false if there is not enough faith
     */
    public boolean request(DivinePower power, float x, float z) {
        if (!canAfford(power)) {
            return false;
        }
        queue.add(new Command(power, x, z));
        queuedCost += config.of(power).cost();
        return true;
    }

    /** Removes and returns the queued commands (called by the simulation). */
    public List<Command> takeQueued() {
        List<Command> taken = new ArrayList<>(queue);
        queue.clear();
        queuedCost = 0f;
        return taken;
    }

    /** Commands waiting for the next tick (normally none between ticks). */
    public List<Command> queued() {
        return Collections.unmodifiableList(queue);
    }

    /** Restores a queued command from a save game (without checking the faith again). */
    public void restoreQueued(Command command) {
        queue.add(command);
        queuedCost += config.of(command.power()).cost();
    }

    public void addRain(RainArea rain) {
        rains.add(rain);
    }

    /** Active rain clouds. */
    public List<RainArea> rains() {
        return rains;
    }

    public void recordStrike(Strike strike) {
        if (strikes.size() == MAX_STRIKES) {
            strikes.removeFirst();
        }
        strikes.addLast(strike);
    }

    /** Recently applied powers, oldest first. */
    public List<Strike> recentStrikes() {
        return Collections.unmodifiableList(new ArrayList<>(strikes));
    }
}

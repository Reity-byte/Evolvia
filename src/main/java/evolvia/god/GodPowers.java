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

    /** The god's hand on a creature ({@code target} for attacks, {@code x, z} for moving). */
    public record HandCommand(HandAction action, int entity, int target, float x, float z) {
    }

    /** A hand action that was applied (for visual effects). */
    public record HandEffect(HandAction action, float x, float z, int tick) {
    }

    /** A power that was applied (for visual effects). */
    public record Strike(DivinePower power, float x, float z, float radius, int tick) {
    }

    private static final int MAX_STRIKES = 32;

    private final GodConfig config;
    private final Faith faith;
    private final List<Command> queue = new ArrayList<>();
    private final List<HandCommand> handQueue = new ArrayList<>();
    private final List<RainArea> rains = new ArrayList<>();
    private final Deque<Strike> strikes = new ArrayDeque<>();
    private final Deque<HandEffect> handEffects = new ArrayDeque<>();
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

    /** True if the player has enough faith for a hand action (counting everything already queued). */
    public boolean canAfford(HandAction action) {
        return faith.canAfford(queuedCost + config.hand().cost(action));
    }

    /**
     * Queues a hand action; it takes effect at the start of the next tick (or at once while paused).
     *
     * @return false if there is not enough faith
     */
    public boolean request(HandCommand command) {
        if (!canAfford(command.action())) {
            return false;
        }
        handQueue.add(command);
        queuedCost += config.hand().cost(command.action());
        return true;
    }

    /** Removes and returns the queued hand actions (called by the simulation). */
    public List<HandCommand> takeQueuedHand() {
        List<HandCommand> taken = new ArrayList<>(handQueue);
        handQueue.clear();
        for (HandCommand command : taken) {
            queuedCost -= config.hand().cost(command.action());
        }
        return taken;
    }

    /** Hand actions waiting for the next tick (save games). */
    public List<HandCommand> queuedHand() {
        return Collections.unmodifiableList(handQueue);
    }

    /** Removes and returns the queued commands (called by the simulation). */
    public List<Command> takeQueued() {
        List<Command> taken = new ArrayList<>(queue);
        queue.clear();
        for (Command command : taken) {
            queuedCost -= config.of(command.power()).cost();
        }
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

    public void recordHand(HandEffect effect) {
        if (handEffects.size() == MAX_STRIKES) {
            handEffects.removeFirst();
        }
        handEffects.addLast(effect);
    }

    /** Recently applied hand actions, oldest first. */
    public List<HandEffect> recentHandEffects() {
        return Collections.unmodifiableList(new ArrayList<>(handEffects));
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

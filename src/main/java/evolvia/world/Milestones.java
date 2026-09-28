package evolvia.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Early game goals (DESIGN.md §11, 9c) from {@code data/milestones.json}: reached once, each gives
 * evolution points and faith. Checked once per game second by the {@code MilestoneSystem}.
 */
public final class Milestones {

    /** What a milestone counts. */
    public enum Type {
        /** Believers (the player's people). */
        PEOPLE,
        /** Unlocked evolution nodes. */
        NODES,
        /** Fights the player's people won. */
        VICTORIES,
        /** Herds of the player's people. */
        HERDS,
        /** The player's people asleep in a refuge (phase 9d). */
        SHELTERED,
        /** Days passed (nights survived). */
        DAYS,
        /** Sacred places. */
        SACRED,
        /** Materials stored in the best camp of the player's people (phase 9g). */
        STOCK,
        /** The tribe exists (phase 9h). */
        TRIBE,
        /** Kinds of finished buildings (phase 9h). */
        BUILDINGS
    }

    public record Milestone(String id, String name, String description, Type type, int value, float rewardEp,
                            float rewardFaith) {
    }

    private final List<Milestone> all;
    private final Set<String> completed = new LinkedHashSet<>();
    private final Deque<Milestone> announcements = new ArrayDeque<>();

    public Milestones(List<Milestone> all) {
        this.all = List.copyOf(all);
    }

    public List<Milestone> all() {
        return all;
    }

    public boolean isCompleted(String id) {
        return completed.contains(id);
    }

    /** IDs of reached milestones, in the order they were reached. */
    public Set<String> completed() {
        return Collections.unmodifiableSet(completed);
    }

    /** Marks a milestone reached; returns false if it already was. */
    public boolean complete(Milestone milestone) {
        if (!completed.add(milestone.id())) {
            return false;
        }
        announcements.addLast(milestone);
        return true;
    }

    /** Milestones reached since the last call (for UI notifications). */
    public List<Milestone> takeAnnouncements() {
        List<Milestone> taken = new ArrayList<>(announcements);
        announcements.clear();
        return taken;
    }

    /** Restores reached milestones from a save (without announcing them again). */
    public void restore(List<String> ids) {
        completed.clear();
        for (String id : ids) { // in the order they were reached
            if (all.stream().anyMatch(m -> m.id().equals(id))) {
                completed.add(id);
            }
        }
    }
}

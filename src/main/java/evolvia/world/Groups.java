package evolvia.world;

import java.util.Collection;
import java.util.Collections;
import java.util.TreeMap;

/**
 * The herds of a world (phases 9a, 9c; DESIGN.md §11): each has a leader, an owner (the player's people
 * or wild), a home with a territory around it, a shared memory of where water and food were found and
 * possibly an attack order from the god. Membership is the {@code GroupMember} component; the
 * {@code GroupSystem} forms, splits and dissolves herds. Kept in ID order so everything that iterates
 * herds is deterministic.
 */
public final class Groups {

    /** Species ability with herd bonuses (shared memory, bigger herds, defending each other). */
    public static final String ABILITY = "groups";

    public static final class Group {
        public final int id;
        /** Leading creature, -1 while a new leader has to be chosen (e.g. the leader died). */
        public int leader = -1;
        /** Members at the last herd update. */
        public int size;
        /** The player's people (believers) or a wild herd. */
        public boolean player;
        /** Home: the centre of the territory. Follows the leader unless the god settled the herd. */
        public float homeX;
        public float homeZ;
        public boolean settled;
        /** Average hunger of the members at the last update (hungry herds defend their territory). */
        public float hunger;
        /** Herd the god ordered this one to attack (0 = none), until {@link #attackUntilTick}. */
        public int attackGroup;
        public int attackUntilTick;
        public boolean knowsWater;
        public float waterX;
        public float waterZ;
        public boolean knowsFood;
        public float foodX;
        public float foodZ;

        public Group(int id) {
            this.id = id;
        }

        public boolean attackOrdered(int tick) {
            return attackGroup != 0 && tick < attackUntilTick;
        }
    }

    private final TreeMap<Integer, Group> groups = new TreeMap<>();
    private int nextId = 1;
    /** Fights the player's people won (an enemy surrendered to them or died), for milestones. */
    private int playerVictories;

    /** Founds a new, empty herd. */
    public Group create() {
        Group group = new Group(nextId++);
        groups.put(group.id, group);
        return group;
    }

    /** Herd with that ID, or null. */
    public Group get(int id) {
        return groups.get(id);
    }

    public void remove(int id) {
        groups.remove(id);
    }

    /** All herds in ID order. */
    public Collection<Group> all() {
        return Collections.unmodifiableCollection(groups.values());
    }

    public int count() {
        return groups.size();
    }

    /** Herds of the player's people. */
    public int playerCount() {
        int count = 0;
        for (Group group : groups.values()) {
            if (group.player) {
                count++;
            }
        }
        return count;
    }

    /** Whether two herds fight each other (the player's herds never fight among themselves). */
    public static boolean enemies(Group a, Group b) {
        return a != null && b != null && a != b && !(a.player && b.player);
    }

    /** Called when a creature dies: a dead leader has to be replaced. */
    public void died(int entity, int groupId) {
        Group group = groups.get(groupId);
        if (group != null && group.leader == entity) {
            group.leader = -1;
        }
    }

    public int playerVictories() {
        return playerVictories;
    }

    public void recordPlayerVictory() {
        playerVictories++;
    }

    /** ID the next new herd gets (save games). */
    public int nextId() {
        return nextId;
    }

    /** Restores a saved herd and the ID counter (save games). */
    public void restore(Group group, int nextId) {
        groups.put(group.id, group);
        this.nextId = Math.max(this.nextId, nextId);
    }

    /** Restores the victory counter (save games). */
    public void restoreVictories(int victories) {
        playerVictories = victories;
    }
}

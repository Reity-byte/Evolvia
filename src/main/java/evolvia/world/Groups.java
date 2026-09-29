package evolvia.world;

import evolvia.evolution.Species;

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
        /** Species of the herd (phase 9f); null = the player's species. */
        public Species species;
        /** Home: the centre of the territory. Follows the leader unless the god settled the herd. */
        public float homeX;
        public float homeZ;
        public boolean settled;
        /** Average hunger of the members at the last update (hungry herds defend their territory). */
        public float hunger;
        /** Herd the god ordered this one to attack (0 = none), until {@link #attackUntilTick}. */
        public int attackGroup;
        public int attackUntilTick;
        /** Refuge the herd spends the night at (0 = none chosen, phase 9d). */
        public int shelter;
        /** The herd's camp (phase 9g): where gatherers bring materials; set by the first delivery. */
        /** The tribe (phase 9h): the one herd of the people that builds. */
        public boolean tribe;
        /** A war party of the rival on a raid (phase 11c): it neither gathers nor founds a camp. */
        public boolean raid;
        public boolean hasCamp;
        public float campX;
        public float campZ;
        /** Materials stored in the camp (material -> amount), in name order. */
        public final java.util.TreeMap<String, Float> stock = new java.util.TreeMap<>();

        public float stock(String material) {
            return stock.getOrDefault(material, 0f);
        }

        public float stockTotal() {
            float total = 0f;
            for (float amount : stock.values()) {
                total += amount;
            }
            return total;
        }
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
    /** The god may order herd {@code a} to attack herd {@code b}: any other herd but the player's own (also game). */
    public static boolean canAttack(Group a, Group b) {
        return a != null && b != null && a != b && !(a.player && b.player);
    }

    public static boolean enemies(Group a, Group b) {
        return a != null && b != null && a != b && !(a.player && b.player) && a.species == b.species // territory: own kind
                && (a.species == null || !a.species.isRival()); // the rival people are one people (phase 11)
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

package evolvia.world;

import java.util.Collection;
import java.util.Collections;
import java.util.TreeMap;

/**
 * The herds of a world (phase 9a, DESIGN.md §11): each has a leader and a shared memory of where water
 * and food were found. Membership is the {@code GroupMember} component; the {@code GroupSystem} forms,
 * splits and dissolves herds. Kept in ID order so everything that iterates herds is deterministic.
 */
public final class Groups {

    /** Species ability that makes creatures live in herds. */
    public static final String ABILITY = "groups";

    public static final class Group {
        public final int id;
        /** Leading creature, -1 while a new leader has to be chosen (e.g. the leader died). */
        public int leader = -1;
        /** Members at the last herd update. */
        public int size;
        public boolean knowsWater;
        public float waterX;
        public float waterZ;
        public boolean knowsFood;
        public float foodX;
        public float foodZ;

        public Group(int id) {
            this.id = id;
        }
    }

    private final TreeMap<Integer, Group> groups = new TreeMap<>();
    private int nextId = 1;

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

    /** Called when a creature dies: a dead leader has to be replaced. */
    public void died(int entity, int groupId) {
        Group group = groups.get(groupId);
        if (group != null && group.leader == entity) {
            group.leader = -1;
        }
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
}

package evolvia.components;

/** Membership of a creature in a herd (see {@code Groups}). */
public final class GroupMember {

    /** Herd ID. */
    public int group;
    /** How long the creature has been too far from its leader (it leaves the herd after a while). */
    public int farTicks;

    public GroupMember() {
    }

    public GroupMember(int group) {
        this.group = group;
    }
}

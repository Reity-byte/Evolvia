package evolvia.god;

/** What the god's hand can do with a selected creature (phase 9c, DESIGN.md §11). */
public enum HandAction {
    /** Carry the creature (a leader: its herd follows) to another place. Own creatures. */
    MOVE,
    /** Order the leader's herd to attack another herd. Own leaders; a cruel act. */
    ATTACK,
    /** Make the leader's herd settle: its home stays here. Own leaders. */
    SETTLE,
    /** Full health. Own creatures; a kind act. */
    HEAL,
    /** Own creature: it may have young at once and feels less hunger and thirst; a wild one starts believing. */
    BLESS
}

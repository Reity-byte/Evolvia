package evolvia.ai;

/** Actions a creature can perform (DESIGN.md §8). */
public enum ActionType {
    WANDER("Wander"),
    SEEK_FOOD("SeekFood"),
    EAT("Eat"),
    SEEK_WATER("SeekWater"),
    DRINK("Drink"),
    SLEEP("Sleep"),
    SEEK_MATE("SeekMate");

    private final String label;

    ActionType(String label) {
        this.label = label;
    }

    /** Short name for debug display. */
    public String label() {
        return label;
    }
}

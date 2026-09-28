package evolvia.world;

/** What a resource node provides. */
public enum ResourceKind {
    /** Edible, has a limited amount that regrows. */
    FOOD,
    /** Drinkable, unlimited; placed on land tiles next to water. */
    WATER,
    /** Wood or stone for the tribe (phase 9g): gathered and carried to the herd's camp. */
    MATERIAL
}

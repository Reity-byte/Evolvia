package evolvia.evolution;

import java.util.List;

/**
 * One node of the evolution tree, loaded from {@code data/evolution/*.json} (DESIGN.md §7.2).
 *
 * @param id             unique identifier
 * @param name           display name (Czech)
 * @param description    short explanation (Czech)
 * @param branch         branch id (body, diet, adaptation, mind, traits)
 * @param cost           evolution points needed before the price growth ({@link Species#cost})
 * @param requires       nodes that must all be unlocked first
 * @param exclusiveGroup nodes in the same group exclude each other (null = none)
 * @param condition      extra world condition (null = none)
 * @param effects        what unlocking does
 * @param trait          the levelled trait this node is one level of (phase 10a), or null
 */
public record EvolutionNode(
        String id,
        String name,
        String description,
        String branch,
        int cost,
        List<String> requires,
        String exclusiveGroup,
        Condition condition,
        List<Effect> effects,
        Trait trait) {

    /**
     * A levelled trait ("Síla I … V", phase 10a): one data entry with {@code "levels"} becomes a chain of nodes
     * {@code <id>_1 … <id>_<levels>}, each requiring the one before.
     *
     * @param id     the trait's id (without the level)
     * @param name   the trait's name (without the level)
     * @param level  this node's level, 1-based
     * @param levels how many levels the trait has
     */
    public record Trait(String id, String name, int level, int levels) {

        /** Node id of level {@code level} of this trait. */
        public String nodeId(int level) {
            return id + "_" + level;
        }
    }

    public EvolutionNode(String id, String name, String description, String branch, int cost, List<String> requires,
                         String exclusiveGroup, Condition condition, List<Effect> effects) {
        this(id, name, description, branch, cost, requires, exclusiveGroup, condition, effects, null);
    }

    /** Whether the tree view draws this node as its own card (every level of a trait after the first shares one). */
    public boolean isShown() {
        return trait == null || trait.level() == 1;
    }
}

package evolvia.evolution;

import java.util.List;

/**
 * One node of the evolution tree, loaded from {@code data/evolution/*.json} (DESIGN.md §7.2).
 *
 * @param id             unique identifier
 * @param name           display name (Czech)
 * @param description    short explanation (Czech)
 * @param branch         branch id (body, diet, adaptation, mind)
 * @param cost           evolution points needed
 * @param requires       nodes that must all be unlocked first
 * @param exclusiveGroup nodes in the same group exclude each other (null = none)
 * @param condition      extra world condition (null = none)
 * @param effects        what unlocking does
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
        List<Effect> effects) {
}

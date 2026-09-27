package evolvia.evolution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared state of one species (DESIGN.md §7.1): evolution points, unlocked nodes and the stats,
 * abilities and visual parts that result from them. Stats are recomputed once per unlock, not per tick.
 * Creatures refer to their species via {@code SpeciesRef}.
 */
public final class Species {

    /** State of a node for this species (as shown in the evolution UI). */
    public enum NodeStatus { UNLOCKED, AVAILABLE, LOCKED, EXCLUDED }

    /** Status plus the reason when a node cannot be unlocked (null when it can). */
    public record Availability(NodeStatus status, String reason) {
    }

    private final SpeciesDefinition base;
    private final EvolutionTree tree;
    private final Set<String> unlocked = new LinkedHashSet<>();
    private final Set<String> abilities = new HashSet<>();
    private final Set<String> actions = new HashSet<>();
    private final Map<String, String> visuals = new LinkedHashMap<>();
    private SpeciesDefinition stats;
    private float points;
    private float pointsEarned;

    public Species(SpeciesDefinition base, EvolutionTree tree) {
        this.base = base;
        this.tree = tree;
        recompute();
    }

    /** Current stats: base definition with the effects of all unlocked nodes. */
    public SpeciesDefinition stats() {
        return stats;
    }

    public SpeciesDefinition base() {
        return base;
    }

    public EvolutionTree tree() {
        return tree;
    }

    public boolean hasAbility(String ability) {
        return abilities.contains(ability);
    }

    public Set<String> abilities() {
        return Collections.unmodifiableSet(abilities);
    }

    public Set<String> unlockedActions() {
        return Collections.unmodifiableSet(actions);
    }

    /** Visual part variants chosen by evolution (part -> variant). */
    public Map<String, String> visuals() {
        return Collections.unmodifiableMap(visuals);
    }

    public boolean isUnlocked(String nodeId) {
        return unlocked.contains(nodeId);
    }

    public Set<String> unlockedNodes() {
        return Collections.unmodifiableSet(unlocked);
    }

    /** Evolution points available to spend. */
    public float points() {
        return points;
    }

    /** Evolution points earned since the start. */
    public float pointsEarned() {
        return pointsEarned;
    }

    public void addPoints(float amount) {
        if (amount > 0) {
            points += amount;
            pointsEarned += amount;
        }
    }

    /** Whether a node can be unlocked now, and why not. */
    public Availability availability(EvolutionNode node, EvolutionConditions world) {
        if (unlocked.contains(node.id())) {
            return new Availability(NodeStatus.UNLOCKED, null);
        }
        if (node.exclusiveGroup() != null) {
            for (String id : unlocked) {
                EvolutionNode other = tree.node(id);
                if (node.exclusiveGroup().equals(other.exclusiveGroup())) {
                    return new Availability(NodeStatus.EXCLUDED, "vylučuje se s " + other.name());
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String required : node.requires()) {
            if (!unlocked.contains(required)) {
                missing.add(tree.node(required).name());
            }
        }
        if (!missing.isEmpty()) {
            return new Availability(NodeStatus.LOCKED, "vyžaduje " + String.join(", ", missing));
        }
        if (node.condition() != null && !node.condition().isMet(world)) {
            return new Availability(NodeStatus.LOCKED, node.condition().describe());
        }
        if (points < node.cost()) {
            return new Availability(NodeStatus.LOCKED, String.format(Locale.ROOT, "chybí %.0f EP", node.cost() - points));
        }
        return new Availability(NodeStatus.AVAILABLE, null);
    }

    /**
     * Unlocks a node: pays its cost and recomputes the stats.
     *
     * @throws IllegalStateException if the node is not available (unknown id, missing requirements, ...)
     */
    public void unlock(String nodeId, EvolutionConditions world) {
        EvolutionNode node = tree.node(nodeId);
        if (node == null) {
            throw new IllegalStateException("Unknown evolution node '" + nodeId + "'");
        }
        Availability availability = availability(node, world);
        if (availability.status() != NodeStatus.AVAILABLE) {
            throw new IllegalStateException("Cannot unlock '" + nodeId + "': " + availability.status()
                    + (availability.reason() != null ? " (" + availability.reason() + ")" : ""));
        }
        points -= node.cost();
        unlocked.add(nodeId);
        recompute();
    }

    private void recompute() {
        List<EvolutionNode> nodes = new ArrayList<>();
        for (String id : unlocked) {
            nodes.add(tree.node(id));
        }
        stats = SpeciesStats.compute(base, nodes);
        abilities.clear();
        actions.clear();
        visuals.clear();
        for (EvolutionNode node : nodes) {
            for (Effect effect : node.effects()) {
                switch (effect) {
                    case Effect.UnlockAbility ability -> abilities.add(ability.ability());
                    case Effect.UnlockAction action -> actions.add(action.action());
                    case Effect.Visual visual -> visuals.put(visual.part(), visual.variant());
                    case Effect.StatAdd ignored -> {
                    }
                    case Effect.StatMul ignored -> {
                    }
                }
            }
        }
    }
}

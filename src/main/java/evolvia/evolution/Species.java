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

    /**
     * An evolutionary stage: the traits of the first {@code index} unlocked nodes (stage 0 = the starting
     * species). Creatures keep the stage they were born with; unlocking a node only makes a new stage
     * that newborns can reach (DESIGN.md §7.3, generational evolution).
     */
    public record Stage(int index, SpeciesDefinition stats, Set<String> abilities, Map<String, String> visuals) {

        public boolean hasAbility(String ability) {
            return abilities.contains(ability);
        }
    }

    /** Status plus the reason when a node cannot be unlocked (null when it can). */
    public record Availability(NodeStatus status, String reason) {
    }

    private final SpeciesDefinition base;
    private final EvolutionTree tree;
    /** Wild game (phase 9f), or null for the player's species. */
    private final Animal animal;
    private final Set<String> unlocked = new LinkedHashSet<>();
    private final Set<String> abilities = new HashSet<>();
    private final Set<String> actions = new HashSet<>();
    private final Map<String, String> visuals = new LinkedHashMap<>();
    private final List<Stage> stages = new ArrayList<>();
    /** Discoveries of the people (science, phase 10b): they apply to every stage, i.e. to everybody at once. */
    private final List<EvolutionNode> culture = new ArrayList<>();
    private SpeciesDefinition stats;
    private float points;
    private float pointsEarned;
    private int revision;

    public Species(SpeciesDefinition base, EvolutionTree tree) {
        this(base, tree, null);
    }

    /** A species of wild game: no evolution, its look from {@code animal}. */
    public Species(SpeciesDefinition base, Animal animal) {
        this(base, new EvolutionTree(List.of(), "none"), animal);
    }

    private Species(SpeciesDefinition base, EvolutionTree tree, Animal animal) {
        this.base = base;
        this.tree = tree;
        this.animal = animal;
        recompute();
    }

    /** Wild game (phase 9f): no faith, no evolution, hunts or is hunted. */
    public boolean isAnimal() {
        return animal != null;
    }

    /** The game traits, or null for the player's species. */
    public Animal animal() {
        return animal;
    }

    /** Species id (as in the data files). */
    public String id() {
        return base.id();
    }

    /** Stage {@code index} (clamped to the existing stages). */
    public Stage stage(int index) {
        return stages.get(Math.clamp(index, 0, stages.size() - 1));
    }

    /** The most evolved stage (all unlocked nodes). */
    public Stage latestStage() {
        return stages.getLast();
    }

    /** Stage at which a node's traits appear (its position in the unlock order + 1), or -1 if not unlocked. */
    public int stageOf(String nodeId) {
        int index = 1;
        for (String id : unlocked) {
            if (id.equals(nodeId)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /** Stats of the most evolved stage: base definition with the effects of all unlocked nodes. */
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

    /** Increases whenever the unlocked nodes change (e.g. so renderers know to rebuild the creature mesh). */
    public int revision() {
        return revision;
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

    /**
     * What unlocking {@code node} costs now (phase 10a): its data cost, raised by
     * {@code evolution.costGrowthPerNode} for every node unlocked so far.
     */
    public int cost(EvolutionNode node) {
        return Math.round(node.cost() * (1f + base.evolution().costGrowthPerNode() * unlocked.size()));
    }

    /**
     * The level of a trait to show and unlock next (phase 10a): the first level not unlocked yet, or the last
     * level once all are. A node that is no trait is returned as it is.
     */
    public EvolutionNode currentLevel(EvolutionNode node) {
        EvolutionNode.Trait trait = node.trait();
        if (trait == null) {
            return node;
        }
        EvolutionNode last = node;
        for (int level = 1; level <= trait.levels(); level++) {
            last = tree.node(trait.nodeId(level));
            if (!unlocked.contains(last.id())) {
                return last;
            }
        }
        return last;
    }

    /** How many levels of the trait {@code node} belongs to are unlocked (0 for a node that is no trait). */
    public int unlockedLevels(EvolutionNode node) {
        EvolutionNode.Trait trait = node.trait();
        if (trait == null) {
            return 0;
        }
        int count = 0;
        for (int level = 1; level <= trait.levels(); level++) {
            if (unlocked.contains(trait.nodeId(level))) {
                count++;
            }
        }
        return count;
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
            Condition unmet = node.condition() instanceof Condition.All all ? all.firstUnmet(world) : node.condition();
            return new Availability(NodeStatus.LOCKED, unmet.describe());
        }
        if (points < cost(node)) {
            return new Availability(NodeStatus.LOCKED, String.format(Locale.ROOT, "chybí %.0f EP", cost(node) - points));
        }
        return new Availability(NodeStatus.AVAILABLE, null);
    }

    /**
     * Restores a saved species state: points and unlocked nodes (in their order), without paying or
     * checking conditions. Nodes the current tree does not have are skipped.
     *
     * @return ids of skipped nodes
     */
    public List<String> restore(float points, float pointsEarned, List<String> unlockedNodes) {
        this.points = points;
        this.pointsEarned = pointsEarned;
        unlocked.clear();
        List<String> skipped = new ArrayList<>();
        for (String id : unlockedNodes) {
            if (tree.node(id) != null) {
                unlocked.add(id);
            } else {
                skipped.add(id);
            }
        }
        recompute();
        return skipped;
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
        points -= cost(node);
        unlocked.add(nodeId);
        recompute();
    }

    /**
     * Sets the people's discoveries (science, phase 10b). Unlike evolution they are culture: their effects apply
     * to every stage at once, so all living creatures get them, not only newborns.
     */
    public void setCulture(List<EvolutionNode> discoveries) {
        culture.clear();
        culture.addAll(discoveries);
        recompute();
    }

    /** The people's discoveries whose effects every stage has. */
    public List<EvolutionNode> culture() {
        return Collections.unmodifiableList(culture);
    }

    /**
     * Rebuilds all stages (stage k = the first k unlocked nodes plus the culture); the latest one is the species'
     * current state.
     */
    private void recompute() {
        List<EvolutionNode> nodes = new ArrayList<>();
        for (String id : unlocked) {
            nodes.add(tree.node(id));
        }
        stages.clear();
        for (int k = 0; k <= nodes.size(); k++) {
            List<EvolutionNode> first = new ArrayList<>(nodes.subList(0, k));
            first.addAll(culture);
            Set<String> stageAbilities = new HashSet<>();
            Map<String, String> stageVisuals = new LinkedHashMap<>();
            if (animal != null) {
                stageVisuals.putAll(animal.visuals());
            }
            for (EvolutionNode node : first) {
                for (Effect effect : node.effects()) {
                    if (effect instanceof Effect.UnlockAbility ability) {
                        stageAbilities.add(ability.ability());
                    } else if (effect instanceof Effect.Visual visual) {
                        stageVisuals.put(visual.part(), visual.variant());
                    }
                }
            }
            stages.add(new Stage(k, SpeciesStats.compute(base, first), Collections.unmodifiableSet(stageAbilities),
                    Collections.unmodifiableMap(stageVisuals)));
        }
        Stage latest = stages.getLast();
        stats = latest.stats();
        revision++;
        abilities.clear();
        abilities.addAll(latest.abilities());
        visuals.clear();
        visuals.putAll(latest.visuals());
        actions.clear();
        nodes.addAll(culture);
        for (EvolutionNode node : nodes) {
            for (Effect effect : node.effects()) {
                if (effect instanceof Effect.UnlockAction action) {
                    actions.add(action.action());
                }
            }
        }
    }
}

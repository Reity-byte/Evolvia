package evolvia.world;

import evolvia.evolution.Condition;
import evolvia.evolution.EvolutionConditions;
import evolvia.evolution.EvolutionNode;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.Species;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The people's science (phase 10b): knowledge made by the people flows into the chosen research target; a
 * finished research is a discovery whose effects apply to the whole species at once (culture, see
 * {@link Species#setCulture}). Research progress is kept per discovery, so switching the target loses nothing.
 * Without a target the knowledge waits in a store for the next one.
 */
public final class Science {

    /**
     * Rules from {@code data/science/science.json}.
     *
     * @param ability              science starts once the species has this ability (speech)
     * @param basePerMinute        knowledge per minute times log(1 + adult speakers)
     * @param perDelivery          knowledge for every load brought to the camp
     * @param perBuilding          knowledge for every finished building
     * @param tribeFactor          a tribe makes this much more knowledge
     * @param costGrowthPerDiscovery every discovery makes the next ones this much dearer
     * @param queueMax             research queue length (the target not counted)
     * @param cookingSpoilFactor   with Cooking, spoiled food makes ill this much less often
     * @param herbalSpreadFactor   with Herbalism, disease spreads this much less
     * @param herbalDurationFactor with Herbalism, disease lasts this much shorter
     */
    public record Rules(String ability, float basePerMinute, float perDelivery, float perBuilding, float tribeFactor,
                        float costGrowthPerDiscovery, int queueMax, float cookingSpoilFactor, float herbalSpreadFactor,
                        float herbalDurationFactor) {
    }

    /** The rules and the tree of discoveries. */
    public record Config(Rules rules, EvolutionTree tree) {
    }

    /** Abilities of discoveries the simulation reads. */
    public static final String COOKING = "cooking";
    public static final String HERBALISM = "herbalism";

    /** Saved research state. */
    public record State(List<String> discovered, Map<String, Float> progress, String target, List<String> queue,
                        float stored, float earned, float perMinute) {
    }

    private final Config config;
    private final Species species;
    private final Set<String> discovered = new LinkedHashSet<>();
    private final TreeMap<String, Float> progress = new TreeMap<>();
    private final List<String> queue = new ArrayList<>();
    private final List<String> announcements = new ArrayList<>();
    private String target;
    private float stored;
    private float earned;
    private float perMinute;

    public Science(Config config, Species species) {
        this.config = config;
        this.species = species;
    }

    public Config config() {
        return config;
    }

    public Rules rules() {
        return config.rules();
    }

    public EvolutionTree tree() {
        return config.tree();
    }

    /** Science has begun: the species has speech (the latest stage, so it starts with the first speakers). */
    public boolean isActive() {
        return species.hasAbility(config.rules().ability());
    }

    public boolean isDiscovered(String id) {
        return discovered.contains(id);
    }

    public Set<String> discovered() {
        return Collections.unmodifiableSet(discovered);
    }

    /** The current research, or null. */
    public String target() {
        return target;
    }

    public List<String> queue() {
        return Collections.unmodifiableList(queue);
    }

    /** Knowledge waiting for a target. */
    public float stored() {
        return stored;
    }

    public float earned() {
        return earned;
    }

    /** Steady knowledge income per game minute (the people, without deliveries and buildings). */
    public float perMinute() {
        return perMinute;
    }

    public void setPerMinute(float perMinute) {
        this.perMinute = perMinute;
    }

    /** Knowledge put into a discovery so far. */
    public float progress(String id) {
        return progress.getOrDefault(id, 0f);
    }

    /** What researching {@code node} costs now: its data cost, raised for every discovery made. */
    public int cost(EvolutionNode node) {
        return Math.round(node.cost() * (1f + config.rules().costGrowthPerDiscovery() * discovered.size()));
    }

    /** Share (0..1) of {@code node} researched. */
    public float share(EvolutionNode node) {
        return Math.min(1f, progress(node.id()) / Math.max(1, cost(node)));
    }

    /** UNLOCKED = discovered, AVAILABLE = can be researched now, LOCKED (with the reason) otherwise. */
    public Species.Availability availability(EvolutionNode node, EvolutionConditions world) {
        if (discovered.contains(node.id())) {
            return new Species.Availability(Species.NodeStatus.UNLOCKED, null);
        }
        List<String> missing = new ArrayList<>();
        for (String required : node.requires()) {
            if (!discovered.contains(required)) {
                missing.add(tree().node(required).name());
            }
        }
        if (!missing.isEmpty()) {
            return new Species.Availability(Species.NodeStatus.LOCKED, "vyžaduje " + String.join(", ", missing));
        }
        if (node.condition() != null && !node.condition().isMet(world)) {
            Condition unmet = node.condition() instanceof Condition.All all ? all.firstUnmet(world) : node.condition();
            return new Species.Availability(Species.NodeStatus.LOCKED, unmet.describe());
        }
        return new Species.Availability(Species.NodeStatus.AVAILABLE, null);
    }

    /**
     * Makes {@code id} the research target (it leaves the queue, the old target goes to the front of the queue);
     * false if it cannot be researched now.
     */
    public boolean setTarget(String id, EvolutionConditions world) {
        EvolutionNode node = tree().node(id);
        if (node == null || availability(node, world).status() != Species.NodeStatus.AVAILABLE) {
            return false;
        }
        queue.remove(id);
        if (target != null && !target.equals(id)) {
            queue.addFirst(target);
            if (queue.size() > config.rules().queueMax()) {
                queue.removeLast();
            }
        }
        target = id;
        return true;
    }

    /**
     * Puts {@code id} at the end of the queue (the target when there is none). It must not be discovered or queued
     * yet, and each of its requirements must be discovered, the target or earlier in the queue.
     */
    public boolean enqueue(String id, EvolutionConditions world) {
        EvolutionNode node = tree().node(id);
        if (node == null || discovered.contains(id) || id.equals(target) || queue.contains(id)) {
            return false;
        }
        if (target == null) {
            return setTarget(id, world);
        }
        if (queue.size() >= config.rules().queueMax()) {
            return false;
        }
        for (String required : node.requires()) {
            if (!discovered.contains(required) && !required.equals(target) && !queue.contains(required)) {
                return false;
            }
        }
        queue.add(id);
        return true;
    }

    /** Takes {@code id} off the queue or stops researching it (its progress stays). */
    public void cancel(String id, EvolutionConditions world) {
        queue.remove(id);
        if (id.equals(target)) {
            nextTarget(world);
        }
    }

    /** Knowledge for the research: into the target (a finished one is discovered, the rest goes on), else stored. */
    public void add(float amount, EvolutionConditions world) {
        if (amount <= 0f) {
            return;
        }
        earned += amount;
        if (target == null) {
            nextTarget(world);
        }
        if (target == null) {
            stored += amount;
            return;
        }
        float left = amount + stored;
        stored = 0f;
        while (target != null && left > 0f) {
            EvolutionNode node = tree().node(target);
            float need = cost(node) - progress(target);
            if (left < need) {
                progress.put(target, progress(target) + left);
                return;
            }
            left -= need;
            discover(target);
            nextTarget(world);
        }
        stored += left;
    }

    /** The first queued discovery that can be researched now becomes the target. */
    private void nextTarget(EvolutionConditions world) {
        target = null;
        for (String id : queue) {
            if (world == null || availability(tree().node(id), world).status() == Species.NodeStatus.AVAILABLE) {
                target = id;
                queue.remove(id);
                return;
            }
        }
    }

    /** Makes the discovery now (research finished, a debug key or an old save). */
    public void discover(String id) {
        EvolutionNode node = tree().node(id);
        if (node == null || !discovered.add(id)) {
            return;
        }
        progress.remove(id);
        queue.remove(id);
        if (id.equals(target)) {
            target = null;
        }
        announcements.add("Objev: " + node.name());
        applyCulture();
    }

    private void applyCulture() {
        List<EvolutionNode> nodes = new ArrayList<>();
        for (String id : discovered) {
            nodes.add(tree().node(id));
        }
        species.setCulture(nodes);
    }

    /** New discoveries since the last call (for the UI). */
    public List<String> takeAnnouncements() {
        List<String> taken = new ArrayList<>(announcements);
        announcements.clear();
        return taken;
    }

    public State state() {
        return new State(new ArrayList<>(discovered), new TreeMap<>(progress), target, new ArrayList<>(queue), stored,
                earned, perMinute);
    }

    /** Restores a saved state; discoveries the tree does not know any more are skipped. */
    public void restore(State state) {
        discovered.clear();
        progress.clear();
        queue.clear();
        for (String id : state.discovered()) {
            if (tree().node(id) != null) {
                discovered.add(id);
            }
        }
        if (state.progress() != null) {
            state.progress().forEach((id, value) -> {
                if (tree().node(id) != null) {
                    progress.put(id, value);
                }
            });
        }
        target = state.target() != null && tree().node(state.target()) != null ? state.target() : null;
        if (state.queue() != null) {
            for (String id : state.queue()) {
                if (tree().node(id) != null) {
                    queue.add(id);
                }
            }
        }
        stored = state.stored();
        earned = state.earned();
        perMinute = state.perMinute();
        applyCulture();
    }
}

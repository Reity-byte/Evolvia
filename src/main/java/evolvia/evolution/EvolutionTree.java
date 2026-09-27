package evolvia.evolution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All evolution nodes. Validated when built: duplicate ids, requirements that do not exist and
 * requirement cycles fail with a clear message (DESIGN.md §7.2).
 */
public final class EvolutionTree {

    private final Map<String, EvolutionNode> nodes = new LinkedHashMap<>();
    private final List<String> branches = new ArrayList<>();

    /**
     * @param nodes  nodes in display order
     * @param source description of where the nodes come from (for error messages)
     * @throws IllegalStateException if the tree is inconsistent
     */
    public EvolutionTree(List<EvolutionNode> nodes, String source) {
        for (EvolutionNode node : nodes) {
            if (this.nodes.putIfAbsent(node.id(), node) != null) {
                throw new IllegalStateException(source + ": duplicate evolution node id '" + node.id() + "'");
            }
            if (!branches.contains(node.branch())) {
                branches.add(node.branch());
            }
        }
        for (EvolutionNode node : nodes) {
            for (String required : node.requires()) {
                if (!this.nodes.containsKey(required)) {
                    throw new IllegalStateException(source + ": node '" + node.id() + "' requires unknown node '" + required + "'");
                }
                if (required.equals(node.id())) {
                    throw new IllegalStateException(source + ": node '" + node.id() + "' requires itself");
                }
            }
        }
        checkCycles(source);
    }

    /** Depth-first search; a node met again while on the current path closes a cycle. */
    private void checkCycles(String source) {
        Map<String, Integer> state = new HashMap<>(); // 1 = on the path, 2 = done
        for (String id : nodes.keySet()) {
            visit(id, state, new ArrayList<>(), source);
        }
    }

    private void visit(String id, Map<String, Integer> state, List<String> path, String source) {
        Integer s = state.get(id);
        if (s != null && s == 2) {
            return;
        }
        path.add(id);
        if (s != null) {
            List<String> cycle = path.subList(path.indexOf(id), path.size());
            throw new IllegalStateException(source + ": evolution requirements form a cycle: " + String.join(" -> ", cycle));
        }
        state.put(id, 1);
        for (String required : nodes.get(id).requires()) {
            visit(required, state, path, source);
        }
        state.put(id, 2);
        path.remove(path.size() - 1);
    }

    /** Node with the given id, or null. */
    public EvolutionNode node(String id) {
        return nodes.get(id);
    }

    /** All nodes in display order (branch files in order, nodes in file order). */
    public List<EvolutionNode> nodes() {
        return List.copyOf(nodes.values());
    }

    public List<String> branches() {
        return Collections.unmodifiableList(branches);
    }

    public int size() {
        return nodes.size();
    }
}

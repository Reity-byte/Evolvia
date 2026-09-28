package evolvia.ui;

import evolvia.evolution.EvolutionNode;
import evolvia.evolution.EvolutionTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Positions of the evolution tree's nodes on screen, computed from the tree data (no positions in JSON).
 * Each branch is a block: a node's row is its depth (longest chain of same-branch requirements), its column is
 * under its first requirement from the same branch when free, otherwise the next free one. Blocks are
 * placed left to right and wrap to a new line when they do not fit the available width.
 */
public final class TreeLayout {

    public static final float NODE_WIDTH = 150f;
    public static final float NODE_HEIGHT = 46f;
    public static final float COLUMN_GAP = 14f;
    public static final float ROW_GAP = 30f;
    public static final float BRANCH_GAP = 40f;
    public static final float HEADER_HEIGHT = 30f;

    /** Rectangle in UI units, relative to the layout origin. */
    public record Box(float x, float y, float w, float h) {

        public float centerX() {
            return x + w / 2f;
        }

        public float bottom() {
            return y + h;
        }

        boolean overlaps(Box o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }
    }

    private final Map<String, Box> nodes = new LinkedHashMap<>();
    private final Map<String, Box> branches = new LinkedHashMap<>();
    private float width;
    private float height;

    private TreeLayout() {
    }

    /** Lays out the tree to fit into {@code availableWidth} where possible. */
    public static TreeLayout of(EvolutionTree tree, float availableWidth) {
        TreeLayout layout = new TreeLayout();
        Map<String, Integer> depths = new HashMap<>();
        for (EvolutionNode node : tree.nodes()) {
            depth(tree, node, depths);
        }

        float x = 0;
        float y = 0;
        float lineHeight = 0;
        for (String branch : tree.branches()) {
            // Columns within the branch
            Map<String, Integer> columns = new HashMap<>();
            Map<Integer, List<Integer>> used = new HashMap<>();
            int columnCount = 0;
            int maxDepth = 0;
            List<EvolutionNode> branchNodes = new ArrayList<>();
            for (EvolutionNode node : tree.nodes()) {
                if (node.branch().equals(branch) && node.isShown()) { // one card per levelled trait (phase 10a)
                    branchNodes.add(node);
                }
            }
            branchNodes.sort((a, b) -> Integer.compare(depths.get(a.id()), depths.get(b.id()))); // stable
            for (EvolutionNode node : branchNodes) {
                int depth = depths.get(node.id());
                int preferred = 0;
                for (String required : node.requires()) {
                    if (columns.containsKey(required)) {
                        preferred = columns.get(required);
                        break;
                    }
                }
                List<Integer> taken = used.computeIfAbsent(depth, d -> new ArrayList<>());
                int column = preferred;
                while (taken.contains(column)) {
                    column++;
                }
                taken.add(column);
                columns.put(node.id(), column);
                columnCount = Math.max(columnCount, column + 1);
                maxDepth = Math.max(maxDepth, depth);
            }

            float blockWidth = columnCount * NODE_WIDTH + (columnCount - 1) * COLUMN_GAP;
            float blockHeight = HEADER_HEIGHT + (maxDepth + 1) * NODE_HEIGHT + maxDepth * ROW_GAP;
            if (x > 0 && x + blockWidth > availableWidth) { // wrap
                x = 0;
                y += lineHeight + BRANCH_GAP;
                lineHeight = 0;
            }
            layout.branches.put(branch, new Box(x, y, blockWidth, blockHeight));
            for (EvolutionNode node : branchNodes) {
                float nodeX = x + columns.get(node.id()) * (NODE_WIDTH + COLUMN_GAP);
                float nodeY = y + HEADER_HEIGHT + depths.get(node.id()) * (NODE_HEIGHT + ROW_GAP);
                layout.nodes.put(node.id(), new Box(nodeX, nodeY, NODE_WIDTH, NODE_HEIGHT));
            }
            layout.width = Math.max(layout.width, x + blockWidth);
            lineHeight = Math.max(lineHeight, blockHeight);
            x += blockWidth + BRANCH_GAP;
        }
        layout.height = y + lineHeight;
        return layout;
    }

    /**
     * Longest chain of requirements from the same branch below the node (0 = none). Requirements from
     * other branches do not push a node down (the tooltip names them). The tree has no cycles.
     */
    private static int depth(EvolutionTree tree, EvolutionNode node, Map<String, Integer> depths) {
        Integer known = depths.get(node.id());
        if (known != null) {
            return known;
        }
        int depth = 0;
        for (String required : node.requires()) {
            EvolutionNode parent = tree.node(required);
            if (parent.branch().equals(node.branch())) {
                depth = Math.max(depth, depth(tree, parent, depths) + 1);
            }
        }
        depths.put(node.id(), depth);
        return depth;
    }

    public Box node(String id) {
        return nodes.get(id);
    }

    public Map<String, Box> nodes() {
        return nodes;
    }

    /** Block of a branch (header on top, then its nodes). */
    public Map<String, Box> branches() {
        return branches;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    /** True if no two node boxes overlap (used by tests). */
    boolean hasNoOverlaps() {
        List<Box> boxes = new ArrayList<>(nodes.values());
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                if (boxes.get(i).overlaps(boxes.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }
}

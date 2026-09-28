package evolvia.ui;

import evolvia.data.DataLoader;
import evolvia.evolution.Effect;
import evolvia.evolution.EvolutionNode;
import evolvia.evolution.EvolutionTree;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeLayoutTest {

    private static EvolutionTree tree;

    @BeforeAll
    static void load() {
        tree = DataLoader.loadEvolutionTree(DataLoader.loadBiomes());
    }

    @Test
    void everyNodeHasItsOwnPlace() {
        for (float width : new float[]{600f, 1200f, 2400f}) {
            TreeLayout layout = TreeLayout.of(tree, width);
            assertEquals(tree.nodes().stream().filter(EvolutionNode::isShown).count(), layout.nodes().size(),
                    "one card per node, one per levelled trait");
            assertTrue(layout.hasNoOverlaps(), "overlap at width " + width);
        }
    }

    @Test
    void nodesAreBelowTheirRequirements() {
        TreeLayout layout = TreeLayout.of(tree, 1200f);
        for (EvolutionNode node : tree.nodes()) {
            for (String required : node.requires()) {
                if (node.isShown() && tree.node(required).branch().equals(node.branch())) {
                    assertTrue(layout.node(node.id()).y() > layout.node(required).bottom(), node.id() + " under " + required);
                }
            }
        }
        // A single child sits directly under its parent.
        assertEquals(layout.node("body_strong_legs").x(), layout.node("body_long_legs").x());
    }

    @Test
    void branchesWrapToFitTheScreen() {
        TreeLayout wide = TreeLayout.of(tree, 3000f);
        TreeLayout narrow = TreeLayout.of(tree, 1200f);
        assertTrue(wide.width() <= 3000f);
        assertTrue(narrow.width() <= 1200f);
        assertTrue(narrow.height() > wide.height(), "narrow screen should use more rows");
        for (String branch : tree.branches()) {
            assertNotNull(narrow.branches().get(branch));
        }
    }

    @Test
    void everyEffectHasAReadableDescription() {
        for (EvolutionNode node : tree.nodes()) {
            for (Effect effect : node.effects()) {
                String text = Texts.effect(effect);
                assertFalse(text.isBlank(), node.id());
                assertFalse(text.contains("_"), "raw key in '" + text + "' (" + node.id() + ")");
            }
        }
    }

    @Test
    void percentagesUseSignsAndRoundNicely() {
        assertEquals("+20 %", Texts.percent(0.2f));
        assertEquals("−25 %", Texts.percent(-0.25f));
        assertEquals("+0 %", Texts.percent(0.001f));
    }
}

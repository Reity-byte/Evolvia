package evolvia.render;

import evolvia.data.DataLoader;
import evolvia.evolution.Effect;
import evolvia.evolution.EvolutionConditions;
import evolvia.evolution.EvolutionNode;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.Species;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 6 DoD: unlocking a node visibly changes how the creatures look. */
class CreatureMeshBuilderTest {

    private static final int COLOR = 0xD0843F;
    /** Everything is available: enough population and every biome. */
    private static final EvolutionConditions RICH_WORLD = new EvolutionConditions() {
        @Override
        public int population() {
            return 10_000;
        }

        @Override
        public float biomeRatio(String biomeId) {
            return 1f;
        }
    };

    private static EvolutionTree tree;

    @BeforeAll
    static void load() {
        tree = DataLoader.loadEvolutionTree(DataLoader.loadBiomes());
    }

    @Test
    void everyVisualInTheTreeCanBeDrawn() {
        assertDoesNotThrow(() -> CreatureMeshBuilder.validate(tree));
    }

    @Test
    void unknownVisualsAreRejected() {
        EvolutionTree bad = DataLoader.parseEvolutionTree(Map.of("t.json", """
                {"branch": "body", "nodes": [{"id": "wings", "name": "Křídla", "cost": 10,
                  "effects": [{"type": "visual", "part": "wings", "variant": "big"}]}]}"""), null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CreatureMeshBuilder.validate(bad));
        assertTrue(e.getMessage().contains("wings"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CreatureMeshBuilder.build(COLOR, Map.of("legs", "wobbly")));
    }

    @Test
    void everyVisualNodeChangesTheMesh() {
        for (EvolutionNode node : tree.nodes()) {
            if (node.effects().stream().noneMatch(e -> e instanceof Effect.Visual)) {
                continue;
            }
            Species species = new Species(DataLoader.loadSpecies(), tree);
            species.addPoints(10_000f);
            unlockWithRequirements(species, node);
            // Compare with the same species without this node's visual effects.
            Map<String, String> before = new java.util.HashMap<>(species.visuals());
            for (Effect effect : node.effects()) {
                if (effect instanceof Effect.Visual visual) {
                    before.remove(visual.part());
                }
            }
            MeshData without = CreatureMeshBuilder.build(COLOR, before);
            MeshData with = CreatureMeshBuilder.build(COLOR, species.visuals());
            assertFalse(Arrays.equals(without.vertices(), with.vertices()), node.id() + " does not change the look");
        }
    }

    private static void unlockWithRequirements(Species species, EvolutionNode node) {
        for (String required : node.requires()) {
            unlockWithRequirements(species, tree.node(required));
        }
        if (!species.isUnlocked(node.id())) {
            species.unlock(node.id(), RICH_WORLD);
        }
    }

    @Test
    void unlockingChangesTheRevisionSoRenderersRebuild() {
        Species species = new Species(DataLoader.loadSpecies(), tree);
        int before = species.revision();
        species.addPoints(100f);
        species.unlock("body_strong_legs", RICH_WORLD);
        assertTrue(species.revision() > before);
    }

    @Test
    void defaultCreatureHasFourSwingingLegsStandingOnTheGround() {
        MeshData mesh = CreatureMeshBuilder.build(COLOR, Map.of());
        int floats = mesh.floatsPerVertex();
        float minY = Float.MAX_VALUE;
        long forward = 0;
        long backward = 0;
        for (int v = 0; v < mesh.vertexCount(); v++) {
            minY = Math.min(minY, mesh.vertices()[v * floats + 1]);
            float swing = mesh.vertices()[v * floats + 11];
            if (swing > 0) {
                forward++;
            } else if (swing < 0) {
                backward++;
            }
        }
        assertEquals(0f, minY, 0.01f, "feet on the ground");
        assertEquals(2 * 24, forward, "two legs swing one way");
        assertEquals(2 * 24, backward, "two legs swing the other way");
        assertEquals(0, mesh.indices().length % 3);
    }

    @Test
    void longLegsMakeTheCreatureTaller() {
        assertTrue(height(Map.of("legs", "long")) > height(Map.of()) + 0.1f);
        assertTrue(height(Map.of("head", "large")) > height(Map.of()));
    }

    private static float height(Map<String, String> visuals) {
        MeshData mesh = CreatureMeshBuilder.build(COLOR, visuals);
        float max = 0;
        for (int v = 0; v < mesh.vertexCount(); v++) {
            max = Math.max(max, mesh.vertices()[v * mesh.floatsPerVertex() + 1]);
        }
        return max;
    }

    @Test
    void everySupportedVariantBuilds() {
        for (String part : CreatureMeshBuilder.parts()) {
            for (String variant : List.of("normal", "none", "strong", "long", "large", "thick", "sandy", "moist", "bare",
                    "white", "webbed", "flat", "mixed", "sharp", "big", "alert", "round")) {
                if (CreatureMeshBuilder.supports(part, variant)) {
                    MeshData mesh = CreatureMeshBuilder.build(COLOR, Map.of(part, variant));
                    for (float value : mesh.vertices()) {
                        assertTrue(Float.isFinite(value), part + "/" + variant);
                    }
                }
            }
        }
    }
}

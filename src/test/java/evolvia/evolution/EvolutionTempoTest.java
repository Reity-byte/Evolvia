package evolvia.evolution;

import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.world.BiomeTable;
import evolvia.world.ResourceTable;
import evolvia.world.World;
import evolvia.world.WorldConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 10a DoD: dearer nodes, levelled traits and a fast start that slows down. */
class EvolutionTempoTest {

    private static BiomeTable biomes;
    private static SpeciesDefinition people;
    private static EvolutionTree tree;

    @BeforeAll
    static void loadData() {
        biomes = DataLoader.loadBiomes();
        people = DataLoader.loadSpecies();
        tree = DataLoader.loadEvolutionTree(biomes);
    }

    private static final EvolutionConditions ANYWHERE = new EvolutionConditions() {
        @Override
        public int population() {
            return 100;
        }

        @Override
        public float biomeRatio(String biome) {
            return 1f;
        }
    };

    @Test
    void everyUnlockMakesTheNextNodesDearer() {
        Species species = new Species(people, tree);
        EvolutionNode legs = tree.node("body_strong_legs");
        EvolutionNode eyes = tree.node("body_keen_eyes");
        assertEquals(legs.cost(), species.cost(legs), "nothing unlocked: the data cost");
        species.addPoints(1000f);
        float before = species.points();
        species.unlock("body_strong_legs", ANYWHERE);
        assertEquals(before - legs.cost(), species.points(), 1e-3);
        float growth = people.evolution().costGrowthPerNode();
        assertTrue(growth > 0f);
        assertEquals(Math.round(eyes.cost() * (1f + growth)), species.cost(eyes));
        species.unlock("body_keen_eyes", ANYWHERE);
        assertEquals(Math.round(tree.node("body_tough_hide").cost() * (1f + 2 * growth)), species.cost(tree.node("body_tough_hide")));

        Species poor = new Species(people, tree);
        poor.addPoints(legs.cost() + 1f);
        poor.unlock("body_strong_legs", ANYWHERE);
        poor.addPoints(eyes.cost() - 1f); // enough for the data cost, not for the grown one
        assertEquals(Species.NodeStatus.LOCKED, poor.availability(eyes, ANYWHERE).status());
        assertTrue(poor.availability(eyes, ANYWHERE).reason().contains("EP"));
    }

    @Test
    void aLevelledTraitBecomesAChainOfNodes() {
        EvolutionNode first = tree.node("trait_strength_1");
        assertNotNull(first);
        assertNull(tree.node("trait_strength"), "only the levels are nodes");
        assertEquals("Síla I", first.name());
        assertEquals("Síla V", tree.node("trait_strength_5").name());
        assertNull(tree.node("trait_strength_6"));
        assertEquals(List.of(), first.requires());
        for (int level = 2; level <= 5; level++) {
            EvolutionNode node = tree.node("trait_strength_" + level);
            assertEquals(List.of("trait_strength_" + (level - 1)), node.requires());
            assertEquals(Math.round(first.cost() * (float) Math.pow(1.5, level - 1)), node.cost());
            assertEquals(new EvolutionNode.Trait("trait_strength", "Síla", level, 5), node.trait());
            assertTrue(!node.isShown(), "the levels share one card");
        }
        assertTrue(first.isShown());
        assertEquals(List.of("mind_speech"), tree.node("trait_wit_1").requires(), "Wit needs speech");
        assertEquals(3, tree.node("trait_fertility_1").trait().levels());

        Species species = new Species(people, tree);
        species.addPoints(10_000f);
        assertEquals("trait_strength_1", species.currentLevel(first).id());
        species.unlock("trait_strength_1", ANYWHERE);
        species.unlock("trait_strength_2", ANYWHERE);
        assertEquals("trait_strength_3", species.currentLevel(first).id());
        assertEquals(2, species.unlockedLevels(first));
        assertEquals(Species.NodeStatus.LOCKED, species.availability(tree.node("trait_strength_4"), ANYWHERE).status());
        for (int level = 3; level <= 5; level++) {
            species.unlock("trait_strength_" + level, ANYWHERE);
        }
        assertEquals("trait_strength_5", species.currentLevel(first).id(), "all levels: the last one");
        assertEquals(Species.NodeStatus.UNLOCKED, species.availability(species.currentLevel(first), ANYWHERE).status());
    }

    @Test
    void badLevelsAreRejected() {
        String json = """
                {"branch": "traits", "nodes": [{"id": "t", "name": "T", "cost": 10, "levels": 1, "effects": []}]}""";
        assertThrows(IllegalStateException.class, () -> DataLoader.parseEvolutionTree(Map.of("t.json", json), biomes));
    }

    @Test
    void strengthAndWitChangeTheStats() {
        Species species = new Species(people, tree);
        species.addPoints(10_000f);
        species.unlock("trait_strength_1", ANYWHERE);
        species.unlock("trait_strength_2", ANYWHERE);
        SpeciesDefinition stats = species.stats();
        assertEquals(people.combat().damagePerSecond() * 1.15f * 1.15f, stats.combat().damagePerSecond(), 1e-4);
        assertEquals(1.08f * 1.08f, stats.skills().workSpeed(), 1e-4);
        assertEquals(1f, stats.skills().learning(), 1e-6);
        assertTrue(stats.needs().hungerPerSecond() > people.needs().hungerPerSecond(), "strength eats more");

        for (String node : List.of("mind_instincts", "mind_memory", "mind_social_groups", "mind_speech", "trait_wit_1")) {
            species.unlock(node, ANYWHERE);
        }
        assertEquals(1.1f, species.stats().skills().learning(), 1e-4);
        assertEquals(1f, species.stage(0).stats().skills().workSpeed(), 1e-6, "the starting stage is unchanged");
    }

    @Test
    void theStartIsFastAndThenSlowsDown() {
        WorldConfig config = DataLoader.loadWorldConfig();
        ResourceTable resources = DataLoader.loadResources();
        World world = World.create(config, biomes, people, tree, resources, 11);
        List<Float> minutes = new ArrayList<>();
        int end = 30 * 60 * Time.TICKS_PER_SECOND;
        for (int tick = 0; tick < end && !world.playerDefeated(); tick++) {
            world.tick(tick);
            if (tick % Time.TICKS_PER_SECOND == 0) {
                EvolutionNode cheapest = null;
                for (EvolutionNode node : tree.nodes()) {
                    Species species = world.species();
                    if (species.availability(node, world).status() == Species.NodeStatus.AVAILABLE
                            && (cheapest == null || species.cost(node) < species.cost(cheapest))) {
                        cheapest = node;
                    }
                }
                if (cheapest != null) {
                    world.unlock(cheapest.id());
                    minutes.add(tick / (60f * Time.TICKS_PER_SECOND));
                }
            }
        }
        System.out.println("unlock minutes: " + minutes);
        assertTrue(minutes.size() >= 8, "unlocked only " + minutes.size());
        assertTrue(minutes.getFirst() <= 2f, "first node after " + minutes.getFirst() + " min");
        assertTrue(minutes.get(7) >= 8f, "eighth node already after " + minutes.get(7) + " min");
        float early = minutes.get(3) - minutes.get(0);
        float late = minutes.get(minutes.size() - 1) - minutes.get(minutes.size() - 4);
        assertTrue(late > early, "later nodes come slower: first three gaps " + early + " min, last three " + late + " min");
    }
}

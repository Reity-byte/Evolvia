package evolvia.evolution;

import evolvia.data.DataLoader;
import evolvia.world.BiomeTable;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvolutionTreeTest {

    private static final BiomeTable BIOMES = DataLoader.loadBiomes();

    private static EvolutionTree parse(String nodesJson) {
        return DataLoader.parseEvolutionTree(Map.of("test.json", "{\"branch\": \"body\", \"nodes\": [" + nodesJson + "]}"), BIOMES);
    }

    private static void assertInvalid(String expectedMessagePart, String nodesJson) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> parse(nodesJson));
        assertTrue(e.getMessage().contains(expectedMessagePart), "unexpected message: " + e.getMessage());
    }

    @Test
    void shippedTreeLoadsWithAllBranches() {
        EvolutionTree tree = DataLoader.loadEvolutionTree(BIOMES);
        assertTrue(tree.size() >= 15, "only " + tree.size() + " nodes");
        assertEquals(java.util.List.of("body", "diet", "adaptation", "mind", "traits"), tree.branches());
        assertNotNull(tree.node("mind_memory"));
        assertEquals(java.util.List.of("mind_instincts"), tree.node("mind_memory").requires());
    }

    @Test
    void parsesEffectsAndConditions() {
        EvolutionTree tree = parse("""
                {"id": "a", "name": "A", "cost": 10,
                 "requiresCondition": {"type": "biome_presence", "biome": "tundra", "ratio": 0.2},
                 "effects": [{"type": "stat_add", "stat": "speed", "value": 0.5},
                             {"type": "stat_mul", "stat": "size", "value": 2},
                             {"type": "unlock_ability", "ability": "swim"},
                             {"type": "visual", "part": "legs", "variant": "long"},
                             {"type": "unlock_action", "action": "Hunt"}]}
                """);
        EvolutionNode a = tree.node("a");
        assertEquals(5, a.effects().size());
        assertEquals(new Effect.StatAdd(Stat.SPEED, 0.5f), a.effects().get(0));
        assertEquals(new Condition.BiomePresence("tundra", 0.2f), a.condition());
    }

    @Test
    void rejectsDuplicateIds() {
        assertInvalid("duplicate evolution node id 'a'",
                "{\"id\": \"a\", \"name\": \"A\", \"cost\": 1}, {\"id\": \"a\", \"name\": \"B\", \"cost\": 1}");
    }

    @Test
    void rejectsUnknownRequirement() {
        assertInvalid("requires unknown node 'ghost'", "{\"id\": \"a\", \"name\": \"A\", \"cost\": 1, \"requires\": [\"ghost\"]}");
    }

    @Test
    void rejectsCycles() {
        assertInvalid("cycle", """
                {"id": "a", "name": "A", "cost": 1, "requires": ["c"]},
                {"id": "b", "name": "B", "cost": 1, "requires": ["a"]},
                {"id": "c", "name": "C", "cost": 1, "requires": ["b"]}
                """);
        assertInvalid("requires itself", "{\"id\": \"a\", \"name\": \"A\", \"cost\": 1, \"requires\": [\"a\"]}");
    }

    @Test
    void rejectsUnknownStatsEffectsAndConditions() {
        assertInvalid("unknown stat 'wings'",
                "{\"id\": \"a\", \"name\": \"A\", \"cost\": 1, \"effects\": [{\"type\": \"stat_mul\", \"stat\": \"wings\", \"value\": 2}]}");
        assertInvalid("unknown effect type 'teleport'",
                "{\"id\": \"a\", \"name\": \"A\", \"cost\": 1, \"effects\": [{\"type\": \"teleport\"}]}");
        assertInvalid("unknown condition type 'moon'",
                "{\"id\": \"a\", \"name\": \"A\", \"cost\": 1, \"requiresCondition\": {\"type\": \"moon\"}}");
        assertInvalid("existing \"biome\"",
                "{\"id\": \"a\", \"name\": \"A\", \"cost\": 1, \"requiresCondition\": {\"type\": \"biome_presence\", \"biome\": \"lava\", \"ratio\": 0.1}}");
        assertInvalid("cost", "{\"id\": \"a\", \"name\": \"A\", \"cost\": -5}");
    }
}

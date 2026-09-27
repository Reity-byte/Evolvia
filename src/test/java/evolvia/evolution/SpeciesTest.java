package evolvia.evolution;

import evolvia.data.DataLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeciesTest {

    private static SpeciesDefinition base;

    /** Test world facts: fixed population and biome ratio. */
    private record Facts(int population, float tundraRatio) implements EvolutionConditions {
        @Override
        public float biomeRatio(String biomeId) {
            return biomeId.equals("tundra") ? tundraRatio : 0f;
        }
    }

    private static final Facts WORLD = new Facts(100, 0f);

    @BeforeAll
    static void load() {
        base = DataLoader.loadSpecies();
    }

    private static EvolutionTree tree(String nodesJson) {
        return DataLoader.parseEvolutionTree(Map.of("t.json", "{\"branch\": \"body\", \"nodes\": [" + nodesJson + "]}"), null);
    }

    private static final String TREE = """
            {"id": "add", "name": "Add", "cost": 10, "effects": [{"type": "stat_add", "stat": "speed", "value": 1}]},
            {"id": "mul", "name": "Mul", "cost": 10, "effects": [{"type": "stat_mul", "stat": "speed", "value": 2}]},
            {"id": "next", "name": "Next", "cost": 10, "requires": ["add"],
             "effects": [{"type": "unlock_ability", "ability": "swim"}, {"type": "visual", "part": "legs", "variant": "long"}]},
            {"id": "herb", "name": "Herb", "cost": 5, "exclusiveGroup": "diet",
             "effects": [{"type": "stat_mul", "stat": "plantNutrition", "value": 1.5}]},
            {"id": "carn", "name": "Carn", "cost": 5, "exclusiveGroup": "diet",
             "effects": [{"type": "stat_mul", "stat": "plantNutrition", "value": 0}, {"type": "stat_add", "stat": "meatNutrition", "value": 1}]},
            {"id": "big", "name": "Big", "cost": 5, "requiresCondition": {"type": "population_min", "value": 500}},
            {"id": "cold", "name": "Cold", "cost": 5, "requiresCondition": {"type": "biome_presence", "biome": "tundra", "ratio": 0.2}}
            """;

    @Test
    void statsStartAtBaseValues() {
        Species species = new Species(base, tree(TREE));
        assertEquals(base.speed(), species.stats().speed());
        assertEquals(base.diet(), species.stats().diet());
        assertTrue(species.abilities().isEmpty());
    }

    @Test
    void additionsApplyBeforeMultiplicationsWhateverTheUnlockOrder() {
        Species first = new Species(base, tree(TREE));
        first.addPoints(100);
        first.unlock("add", WORLD);
        first.unlock("mul", WORLD);
        Species second = new Species(base, tree(TREE));
        second.addPoints(100);
        second.unlock("mul", WORLD);
        second.unlock("add", WORLD);
        float expected = (base.speed() + 1f) * 2f;
        assertEquals(expected, first.stats().speed(), 1e-5f);
        assertEquals(expected, second.stats().speed(), 1e-5f);
    }

    @Test
    void unlockingCostsPointsAndNeedsRequirements() {
        Species species = new Species(base, tree(TREE));
        EvolutionNode add = species.tree().node("add");
        EvolutionNode next = species.tree().node("next");
        assertEquals(Species.NodeStatus.LOCKED, species.availability(add, WORLD).status(), "no points yet");
        assertTrue(species.availability(add, WORLD).reason().contains("EP"));

        species.addPoints(15);
        assertEquals(Species.NodeStatus.AVAILABLE, species.availability(add, WORLD).status());
        assertEquals(Species.NodeStatus.LOCKED, species.availability(next, WORLD).status(), "requires 'add'");
        species.unlock("add", WORLD);
        assertEquals(5f, species.points(), 1e-6f);
        assertEquals(15f, species.pointsEarned(), 1e-6f);
        assertEquals(Species.NodeStatus.UNLOCKED, species.availability(add, WORLD).status());
        assertThrows(IllegalStateException.class, () -> species.unlock("next", WORLD), "only 5 EP left");

        species.addPoints(10);
        species.unlock("next", WORLD);
        assertTrue(species.hasAbility("swim"));
        assertEquals("long", species.visuals().get("legs"));
    }

    @Test
    void exclusiveGroupBlocksTheOthers() {
        Species species = new Species(base, tree(TREE));
        species.addPoints(100);
        species.unlock("carn", WORLD);
        assertEquals(Species.NodeStatus.EXCLUDED, species.availability(species.tree().node("herb"), WORLD).status());
        assertThrows(IllegalStateException.class, () -> species.unlock("herb", WORLD));
        assertEquals(0f, species.stats().diet().plantNutrition());
        assertEquals(base.diet().meatNutrition() + 1f, species.stats().diet().meatNutrition(), 1e-6f);
    }

    @Test
    void worldConditionsAreChecked() {
        Species species = new Species(base, tree(TREE));
        species.addPoints(100);
        assertEquals(Species.NodeStatus.LOCKED, species.availability(species.tree().node("big"), WORLD).status());
        assertEquals(Species.NodeStatus.AVAILABLE, species.availability(species.tree().node("big"), new Facts(600, 0f)).status());
        assertFalse(species.availability(species.tree().node("cold"), new Facts(100, 0.1f)).status() == Species.NodeStatus.AVAILABLE);
        species.unlock("cold", new Facts(100, 0.3f));
        assertTrue(species.isUnlocked("cold"));
    }

    @Test
    void unknownNodeIsRejected() {
        Species species = new Species(base, tree(TREE));
        assertThrows(IllegalStateException.class, () -> species.unlock("nope", WORLD));
    }
}

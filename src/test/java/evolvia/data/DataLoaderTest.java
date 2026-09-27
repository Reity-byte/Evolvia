package evolvia.data;

import evolvia.world.BiomeTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataLoaderTest {

    private static final String WATER = "{\"id\": \"water\", \"color\": \"#0000ff\", \"water\": true}";
    private static final String GRASS = "{\"id\": \"grass\", \"color\": \"#00ff00\"}";
    private static final String COLD = "{\"id\": \"cold\", \"color\": \"#ffffff\", \"temperature\": [0.0, 0.2]}";

    private static BiomeTable parse(String... biomes) {
        return DataLoader.parseBiomes("{\"biomes\": [" + String.join(",", biomes) + "]}", "test.json");
    }

    private static void assertInvalid(String expectedMessagePart, String... biomes) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> parse(biomes));
        assertTrue(e.getMessage().contains(expectedMessagePart), "unexpected message: " + e.getMessage());
    }

    @Test
    void parsesSpecies() {
        var species = DataLoader.parseSpecies("""
                {"id": "x", "color": "#102030", "bodySize": 0.5, "speed": 2,
                 "wander": {"radius": 8, "pauseSeconds": [1, 2]}, "startingPopulation": 10}
                """, "species.json");
        assertEquals(0x102030, species.rgb());
        assertEquals(0.1f, species.speedPerTick(), 1e-6f);
        assertEquals(10, species.startingPopulation());
        assertEquals("x", species.name(), "name defaults to id");
    }

    @Test
    void rejectsInvalidSpecies() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> DataLoader.parseSpecies("""
                {"id": "x", "color": "#102030", "bodySize": 0.5, "speed": 2,
                 "wander": {"radius": 8, "pauseSeconds": [3, 1]}, "startingPopulation": 10}
                """, "species.json"));
        assertTrue(e.getMessage().contains("pauseSeconds"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> DataLoader.parseSpecies(
                "{\"id\": \"x\", \"color\": \"#102030\", \"bodySize\": 0.5, \"wander\": {\"radius\": 8, \"pauseSeconds\": [1, 2]}, \"startingPopulation\": 1}",
                "species.json"), "missing speed");
    }

    @Test
    void shippedDataFilesAreValid() {
        assertNotNull(DataLoader.loadSpecies());
        assertNotNull(DataLoader.loadWorldConfig());
        BiomeTable biomes = DataLoader.loadBiomes();
        assertNotNull(biomes.water());
        assertNotNull(biomes.byId("grass"));
    }

    @Test
    void landBiomesAreMatchedInOrderWithFallback() {
        BiomeTable table = parse(WATER, COLD, GRASS);
        assertEquals("cold", table.classifyLand(0.1f, 0.5f, 0.5f).id());
        assertEquals("grass", table.classifyLand(0.5f, 0.5f, 0.5f).id());
        assertEquals("water", table.water().id());
        assertEquals(0x00ff00, table.byId("grass").rgb());
    }

    @Test
    void passableDefaultsToFalseOnlyForWater() {
        BiomeTable table = parse(WATER, GRASS);
        assertTrue(table.byId("grass").passable());
        assertFalse(table.water().passable());
    }

    @Test
    void rejectsDuplicateIds() {
        assertInvalid("duplicate biome id 'grass'", WATER, GRASS, GRASS);
    }

    @Test
    void requiresWaterBiome() {
        assertInvalid("no water biome", GRASS);
    }

    @Test
    void requiresCatchAllAsLastLandBiome() {
        assertInvalid("must have no temperature/moisture/altitude limits", WATER, COLD);
    }

    @Test
    void rejectsUnreachableBiomeAfterCatchAll() {
        assertInvalid("can never appear", WATER, GRASS, COLD);
    }

    @Test
    void rejectsBadColor() {
        assertInvalid("#RRGGBB", WATER, "{\"id\": \"grass\", \"color\": \"green\"}");
    }

    @Test
    void rejectsBadRange() {
        assertInvalid("min <= max", WATER, "{\"id\": \"x\", \"color\": \"#000000\", \"moisture\": [0.8, 0.2]}", GRASS);
    }

    @Test
    void rejectsMalformedJson() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DataLoader.parseBiomes("{\"biomes\": [", "broken.json"));
        assertTrue(e.getMessage().startsWith("broken.json: invalid JSON"), e.getMessage());
    }

    @Test
    void rejectsInvalidWorldConfig() {
        String json = """
                {"width": 0, "depth": 64, "heightScale": 10, "seaLevel": 0.2,
                 "height": {"scale": 50, "octaves": 3, "lacunarity": 2, "gain": 0.5}, "heightExponent": 1,
                 "edgeFalloff": {"width": 10, "minFactor": 0},
                 "temperature": {"scale": 50, "octaves": 3, "lacunarity": 2, "gain": 0.5}, "altitudeCooling": 0.2,
                 "moisture": {"scale": 50, "octaves": 3, "lacunarity": 2, "gain": 0.5},
                 "water": {"color": "#0000ff", "alpha": 0.5}}
                """;
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DataLoader.parseWorldConfig(json, "world.json"));
        assertTrue(e.getMessage().contains("width and depth must be positive"), e.getMessage());
    }
}

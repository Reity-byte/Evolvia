package evolvia.world;

import evolvia.data.DataLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainGeneratorTest {

    private static WorldConfig config;
    private static BiomeTable biomes;

    @BeforeAll
    static void loadData() {
        config = DataLoader.loadWorldConfig();
        biomes = DataLoader.loadBiomes();
    }

    @Test
    void sameSeedGivesIdenticalWorld() {
        Terrain a = TerrainGenerator.generate(config, biomes, 12345);
        Terrain b = TerrainGenerator.generate(config, biomes, 12345);
        for (int z = 0; z <= a.depth(); z++) {
            for (int x = 0; x <= a.width(); x++) {
                assertEquals(a.cornerHeight(x, z), b.cornerHeight(x, z), "height at corner " + x + "," + z);
            }
        }
        for (int z = 0; z < a.depth(); z++) {
            for (int x = 0; x < a.width(); x++) {
                assertEquals(a.biome(x, z), b.biome(x, z), "biome at tile " + x + "," + z);
                assertEquals(a.temperature(x, z), b.temperature(x, z));
                assertEquals(a.moisture(x, z), b.moisture(x, z));
            }
        }
    }

    @Test
    void differentSeedsGiveDifferentWorlds() {
        Terrain a = TerrainGenerator.generate(config, biomes, 1);
        Terrain b = TerrainGenerator.generate(config, biomes, 2);
        int differentBiomes = 0;
        for (int z = 0; z < a.depth(); z++) {
            for (int x = 0; x < a.width(); x++) {
                if (a.biome(x, z) != b.biome(x, z)) {
                    differentBiomes++;
                }
            }
        }
        assertTrue(differentBiomes > a.width() * a.depth() / 10, "worlds are too similar: " + differentBiomes);
    }

    @Test
    void worldHasConfiguredSizeAndHeightRange() {
        Terrain terrain = TerrainGenerator.generate(config, biomes, 99);
        assertEquals(config.width(), terrain.width());
        assertEquals(config.depth(), terrain.depth());
        assertEquals(config.seaLevel() * config.heightScale(), terrain.seaLevel(), 1e-5f);
        for (int z = 0; z <= terrain.depth(); z++) {
            for (int x = 0; x <= terrain.width(); x++) {
                float h = terrain.cornerHeight(x, z);
                assertTrue(h >= 0 && h <= config.heightScale(), "height out of range: " + h);
            }
        }
    }

    @Test
    void mapBorderIsUnderWater() {
        Terrain terrain = TerrainGenerator.generate(config, biomes, 5);
        for (int i = 0; i < terrain.width(); i++) {
            assertTrue(terrain.biome(i, 0).water());
            assertTrue(terrain.biome(i, terrain.depth() - 1).water());
        }
        for (int i = 0; i < terrain.depth(); i++) {
            assertTrue(terrain.biome(0, i).water());
            assertTrue(terrain.biome(terrain.width() - 1, i).water());
        }
    }

    @Test
    void waterTilesAreBelowSeaLevelAndImpassable() {
        Terrain terrain = TerrainGenerator.generate(config, biomes, 77);
        for (int z = 0; z < terrain.depth(); z++) {
            for (int x = 0; x < terrain.width(); x++) {
                boolean belowSea = terrain.tileHeight(x, z) < terrain.seaLevel();
                assertEquals(belowSea, terrain.biome(x, z).water(), "tile " + x + "," + z);
                assertEquals(!belowSea, terrain.isPassable(x, z), "tile " + x + "," + z);
            }
        }
    }

    @Test
    void everyBiomeAppearsWithDefaultData() {
        // Climate zones are large (after phase 10), so a single map need not have every biome; together they do.
        Set<String> all = new HashSet<>();
        for (long seed : new long[]{1, 2, 3, 42, 99}) {
            Terrain terrain = TerrainGenerator.generate(config, biomes, seed);
            Set<String> seen = new HashSet<>();
            for (int z = 0; z < terrain.depth(); z++) {
                for (int x = 0; x < terrain.width(); x++) {
                    seen.add(terrain.biome(x, z).id());
                }
            }
            assertTrue(seen.size() >= biomes.all().size() - 1, "seed " + seed + ": only " + seen);
            assertTrue(seen.contains("tundra"), "seed " + seed + ": every map has snowy mountains");
            all.addAll(seen);
        }
        for (Biome biome : biomes.all()) {
            assertTrue(all.contains(biome.id()), "biome '" + biome.id() + "' missing");
        }
    }

    /** Height difference within the tile (world units). */
    private static float relief(Terrain t, int x, int z) {
        float h0 = t.cornerHeight(x, z);
        float h1 = t.cornerHeight(x + 1, z);
        float h2 = t.cornerHeight(x, z + 1);
        float h3 = t.cornerHeight(x + 1, z + 1);
        return Math.max(Math.max(h0, h1), Math.max(h2, h3)) - Math.min(Math.min(h0, h1), Math.min(h2, h3));
    }

    private static float altitude(Terrain t, int x, int z) {
        return (t.heightAt(x + 0.5f, z + 0.5f) - t.seaLevel()) / (config.heightScale() - t.seaLevel());
    }

    @Test
    void theLandIsMostlyPlainWithHillsAndMountainsHereAndThere() {
        for (long seed : new long[]{1, 7, 42, 99}) {
            Terrain t = TerrainGenerator.generate(config, biomes, seed);
            int land = 0;
            int flat = 0;
            int steep = 0;
            int high = 0;
            for (int z = 0; z < t.depth(); z++) {
                for (int x = 0; x < t.width(); x++) {
                    if (!t.isPassable(x, z)) {
                        continue;
                    }
                    land++;
                    float d = relief(t, x, z);
                    flat += d < 0.25f ? 1 : 0;
                    steep += d > 0.8f ? 1 : 0;
                    high += altitude(t, x, z) > 0.35f ? 1 : 0;
                }
            }
            assertTrue(flat > 0.6f * land, "seed " + seed + ": flat " + flat + " of " + land);
            assertTrue(steep < 0.13f * land, "seed " + seed + ": steep " + steep + " of " + land);
            assertTrue(high > 0.005f * land, "seed " + seed + ": no mountains");
            float steepest = 0f;
            for (int z = 0; z < t.depth(); z++) {
                for (int x = 0; x < t.width(); x++) {
                    if (t.isPassable(x, z)) {
                        steepest = Math.max(steepest, relief(t, x, z));
                    }
                }
            }
            // Mountains fall into foothills (thermal erosion): no walls anywhere on land.
            assertTrue(steepest < 3f * config.relief().talus(), "seed " + seed + ": a wall " + steepest + " high in one tile");
        }
    }

    @Test
    void snowLiesOnlyUpInTheMountainsAndClimateZonesAreLarge() {
        for (long seed : new long[]{1, 7, 42, 99}) {
            Terrain t = TerrainGenerator.generate(config, biomes, seed);
            int tundra = 0;
            int clustered = 0;
            int pairs = 0;
            int changes = 0;
            for (int z = 1; z < t.depth() - 1; z++) {
                for (int x = 1; x < t.width() - 1; x++) {
                    if (!t.isPassable(x, z)) {
                        continue;
                    }
                    String id = t.biome(x, z).id();
                    if (id.equals("tundra")) {
                        tundra++;
                        assertTrue(altitude(t, x, z) > 0.1f, "seed " + seed + ": snow in the lowland at " + x + "," + z);
                        int near = 0;
                        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                            near += t.biome(x + d[0], z + d[1]).id().equals("tundra") ? 1 : 0;
                        }
                        clustered += near >= 2 ? 1 : 0;
                    }
                    if (t.isPassable(x + 1, z)) {
                        pairs++;
                        changes += id.equals(t.biome(x + 1, z).id()) ? 0 : 1;
                    }
                }
            }
            assertTrue(tundra > 0, "seed " + seed);
            assertTrue(clustered > 0.8f * tundra, "seed " + seed + ": snow in patches, " + clustered + " of " + tundra);
            assertTrue(changes < 0.05f * pairs, "seed " + seed + ": small climate patches, " + changes + " changes of " + pairs);
        }
    }

    @Test
    void heightAtMatchesCornersAndStaysWithinTile() {
        Terrain terrain = TerrainGenerator.generate(config, biomes, 8);
        for (int z = 0; z <= terrain.depth(); z += 7) {
            for (int x = 0; x <= terrain.width(); x += 5) {
                assertEquals(terrain.cornerHeight(x, z), terrain.heightAt(x, z), 1e-4f);
            }
        }
        // Inside a tile the surface lies between the lowest and the highest corner.
        for (int tz = 10; tz < 200; tz += 13) {
            for (int tx = 10; tx < 200; tx += 11) {
                float min = Math.min(Math.min(terrain.cornerHeight(tx, tz), terrain.cornerHeight(tx + 1, tz)),
                        Math.min(terrain.cornerHeight(tx, tz + 1), terrain.cornerHeight(tx + 1, tz + 1)));
                float max = Math.max(Math.max(terrain.cornerHeight(tx, tz), terrain.cornerHeight(tx + 1, tz)),
                        Math.max(terrain.cornerHeight(tx, tz + 1), terrain.cornerHeight(tx + 1, tz + 1)));
                for (float f = 0.1f; f < 1f; f += 0.2f) {
                    float h = terrain.heightAt(tx + f, tz + 1 - f);
                    assertTrue(h >= min - 1e-4f && h <= max + 1e-4f);
                }
            }
        }
    }

    @Test
    void heightAtIsContinuousAcrossTheTileDiagonal() {
        Terrain terrain = TerrainGenerator.generate(config, biomes, 8);
        // Points just either side of the diagonal fx == fz must give (almost) the same height.
        float below = terrain.heightAt(100.5f + 1e-4f, 100.5f);
        float above = terrain.heightAt(100.5f, 100.5f + 1e-4f);
        assertEquals(below, above, 1e-2f);
    }

    @Test
    void heightAtOutsideMapIsClamped() {
        Terrain terrain = TerrainGenerator.generate(config, biomes, 8);
        assertEquals(terrain.cornerHeight(0, 0), terrain.heightAt(-10, -10), 1e-5f);
        assertEquals(terrain.cornerHeight(terrain.width(), terrain.depth()),
                terrain.heightAt(terrain.width() + 5, terrain.depth() + 5), 1e-5f);
        assertFalse(terrain.isPassable(-1, 0));
    }
}

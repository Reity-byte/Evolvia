package evolvia.ai;

import evolvia.world.Terrain;
import evolvia.world.TestTerrains;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathfinderTest {

    /** Walks the path from the start and checks every segment stays on passable tiles. */
    private static void assertWalkable(Pathfinder pathfinder, float startX, float startZ, Path path) {
        float x = startX;
        float z = startZ;
        for (int i = 0; i < path.length(); i++) {
            assertTrue(pathfinder.hasLineOfSight(x, z, path.x(i), path.z(i)),
                    "segment " + i + " crosses impassable tiles");
            x = path.x(i);
            z = path.z(i);
        }
    }

    private static float length(float startX, float startZ, Path path) {
        float total = 0f;
        float x = startX;
        float z = startZ;
        for (int i = 0; i < path.length(); i++) {
            total += (float) Math.hypot(path.x(i) - x, path.z(i) - z);
            x = path.x(i);
            z = path.z(i);
        }
        return total;
    }

    @Test
    void straightPathOnOpenGroundIsOneSegment() {
        Terrain terrain = TestTerrains.fromAscii(
                "..........",
                "..........",
                "..........");
        Pathfinder pathfinder = new Pathfinder(terrain);
        Path path = pathfinder.find(0.5f, 1.5f, 9.3f, 1.2f);
        assertNotNull(path);
        assertEquals(1, path.length(), "smoothing removes all intermediate waypoints");
        assertEquals(9.3f, path.x(0));
        assertEquals(1.2f, path.z(0));
    }

    @Test
    void goesAroundWater() {
        Terrain terrain = TestTerrains.fromAscii(
                "..........",
                "....~.....",
                "....~.....",
                "....~.....",
                "..........");
        Pathfinder pathfinder = new Pathfinder(terrain);
        Path path = pathfinder.find(1.5f, 2.5f, 8.5f, 2.5f);
        assertNotNull(path);
        assertTrue(path.length() >= 2, "must bend around the water");
        assertWalkable(pathfinder, 1.5f, 2.5f, path);
        assertEquals(8.5f, path.x(path.length() - 1));
        assertEquals(2.5f, path.z(path.length() - 1));
        // Detour via row 0 or 4: noticeably longer than the straight 7 tiles, but not absurd.
        float length = length(1.5f, 2.5f, path);
        assertTrue(length > 7.5f && length < 10.5f, "unexpected path length " + length);
    }

    @Test
    void doesNotCutCornersBetweenWaterTiles() {
        Terrain terrain = TestTerrains.fromAscii(
                "...",
                ".~.",
                "..~");
        Pathfinder pathfinder = new Pathfinder(terrain);
        // Tiles (1,2) and (2,1) touch only diagonally; squeezing between (1,1) and (2,2) is not allowed.
        assertFalse(pathfinder.hasLineOfSight(1.5f, 2.5f, 2.5f, 1.5f));
        assertEquals(pathfinder.region(1, 2), pathfinder.region(2, 1), "still connected around the top");
        Path path = pathfinder.find(1.5f, 2.5f, 2.5f, 1.5f);
        assertNotNull(path);
        assertWalkable(pathfinder, 1.5f, 2.5f, path);
    }

    @Test
    void unreachableGoalFailsImmediately() {
        Terrain terrain = TestTerrains.fromAscii(
                "...~...",
                "...~...",
                "...~...");
        Pathfinder pathfinder = new Pathfinder(terrain);
        assertNotEquals(pathfinder.region(0, 0), pathfinder.region(6, 0));
        assertNull(pathfinder.find(0.5f, 0.5f, 6.5f, 0.5f));
        assertEquals(0, pathfinder.lastExpansions(), "different regions: no search at all");
        assertNull(pathfinder.find(0.5f, 0.5f, 3.5f, 1.5f), "goal on water");
        assertEquals(-1, pathfinder.region(3, 1));
    }

    @Test
    void goalInStartTile() {
        Terrain terrain = TestTerrains.fromAscii("...");
        Path path = new Pathfinder(terrain).find(1.2f, 0.2f, 1.8f, 0.9f);
        assertNotNull(path);
        assertEquals(1, path.length());
    }

    @Test
    void findsShortestRouteThroughMaze() {
        Terrain terrain = TestTerrains.fromAscii(
                ".~.......",
                ".~.~~~~~.",
                ".~.~.....",
                ".~.~.~~~~",
                "...~.....");
        Pathfinder pathfinder = new Pathfinder(terrain);
        Path path = pathfinder.find(0.5f, 0.5f, 8.5f, 4.5f);
        assertNotNull(path);
        assertWalkable(pathfinder, 0.5f, 0.5f, path);
        assertTrue(pathfinder.lastExpansions() < 100);
    }
}

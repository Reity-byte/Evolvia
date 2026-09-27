package evolvia.world;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialGridTest {

    @Test
    void findsNearestAcrossCells() {
        SpatialGrid grid = new SpatialGrid(256, 256, 16);
        grid.insert(1, 10, 10);
        grid.insert(2, 40, 10);
        grid.insert(3, 100, 100);
        assertEquals(1, grid.nearest(12, 12, 50, e -> true));
        assertEquals(2, grid.nearest(35, 10, 50, e -> true));
        assertEquals(3, grid.nearest(90, 90, 50, e -> true));
        assertEquals(3, grid.size());
    }

    @Test
    void respectsRadiusAndFilter() {
        SpatialGrid grid = new SpatialGrid(256, 256, 16);
        grid.insert(1, 10, 10);
        grid.insert(2, 30, 10);
        assertEquals(-1, grid.nearest(100, 100, 20, e -> true), "nothing within the radius");
        assertEquals(2, grid.nearest(10, 10, 50, e -> e != 1), "filter skips the closer one");
        assertEquals(-1, grid.nearest(10, 10, 15, e -> e != 1));
    }

    @Test
    void nearestIsReallyTheNearest() {
        SpatialGrid grid = new SpatialGrid(128, 128, 16);
        java.util.Random random = new java.util.Random(5);
        float[] xs = new float[300];
        float[] zs = new float[300];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = random.nextFloat() * 128;
            zs[i] = random.nextFloat() * 128;
            grid.insert(i, xs[i], zs[i]);
        }
        for (int q = 0; q < 50; q++) {
            float x = random.nextFloat() * 128;
            float z = random.nextFloat() * 128;
            int expected = -1;
            float best = Float.MAX_VALUE;
            for (int i = 0; i < xs.length; i++) {
                float d = (xs[i] - x) * (xs[i] - x) + (zs[i] - z) * (zs[i] - z);
                if (d < best && d <= 40 * 40) {
                    best = d;
                    expected = i;
                }
            }
            assertEquals(expected, grid.nearest(x, z, 40, e -> true));
        }
    }

    @Test
    void moveAndRemove() {
        SpatialGrid grid = new SpatialGrid(64, 64, 16);
        grid.insert(7, 5, 5);
        grid.move(7, 5, 5, 60, 60);
        assertEquals(-1, grid.nearest(5, 5, 10, e -> true));
        assertEquals(7, grid.nearest(58, 58, 10, e -> true));
        assertTrue(grid.remove(7, 60, 60));
        assertFalse(grid.remove(7, 60, 60), "already removed");
        assertEquals(0, grid.size());
    }

    @Test
    void crowdedCellKeepsTrackOfEveryEntity() {
        SpatialGrid grid = new SpatialGrid(64, 64, 16);
        for (int e = 0; e < 200; e++) {
            grid.insert(e, 5 + e * 0.01f, 5);
        }
        for (int e = 0; e < 200; e += 2) {
            grid.move(e, 5, 5, 40, 40); // to another cell
        }
        for (int e = 1; e < 200; e += 4) {
            assertTrue(grid.remove(e, 0, 0));
        }
        Set<Integer> left = new HashSet<>();
        grid.forEachWithin(5, 5, 10, left::add);
        Set<Integer> moved = new HashSet<>();
        grid.forEachWithin(40, 40, 1, moved::add);
        assertEquals(50, left.size());
        assertEquals(100, moved.size());
        assertEquals(150, grid.size());
        for (int e : left) {
            assertTrue(e % 4 == 3, "unexpected entity " + e);
        }
    }

    @Test
    void forEachWithinVisitsOnlyCloseEntities() {
        SpatialGrid grid = new SpatialGrid(64, 64, 16);
        grid.insert(1, 10, 10);
        grid.insert(2, 14, 10);
        grid.insert(3, 30, 30);
        Set<Integer> found = new HashSet<>();
        grid.forEachWithin(10, 10, 5, found::add);
        assertEquals(Set.of(1, 2), found);
    }
}

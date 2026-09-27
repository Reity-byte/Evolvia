package evolvia.ai;

import evolvia.world.Terrain;

import java.util.Arrays;

/**
 * A* on the tile grid (8 directions, no cutting corners past impassable tiles).
 * <p>
 * Also labels connected land regions once, so a request between two regions (e.g. to another
 * island) fails immediately instead of searching the whole map. Found paths are smoothed: waypoints
 * that can be skipped because the straight line stays on passable tiles are removed.
 * Search arrays are reused between searches (no allocation per search except the result).
 */
public final class Pathfinder {

    /** Give up after this many expanded tiles (safety net; regions already rule out unreachable goals). */
    public static final int MAX_EXPANSIONS = 20_000;

    private static final float SQRT2 = (float) Math.sqrt(2);
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private final Terrain terrain;
    private final int width;
    private final int depth;
    private final int[] region;

    private final float[] g;
    private final int[] parent;
    private final int[] seenStamp;
    private final int[] closedStamp;
    private int stamp;

    private int[] heapNodes = new int[1024];
    private float[] heapKeys = new float[1024];
    private int heapSize;

    private int lastExpansions;

    public Pathfinder(Terrain terrain) {
        this.terrain = terrain;
        this.width = terrain.width();
        this.depth = terrain.depth();
        int tiles = width * depth;
        region = new int[tiles];
        g = new float[tiles];
        parent = new int[tiles];
        seenStamp = new int[tiles];
        closedStamp = new int[tiles];
        labelRegions();
    }

    // ---------------------------------------------------------------- regions

    /** Region ID of a tile (tiles with the same ID are connected by land), -1 for impassable tiles. */
    public int region(int tx, int tz) {
        return terrain.inBounds(tx, tz) ? region[tz * width + tx] : -1;
    }

    /** Region ID at a world position. */
    public int regionAt(float x, float z) {
        return region((int) Math.floor(x), (int) Math.floor(z));
    }

    private void labelRegions() {
        Arrays.fill(region, -1);
        int[] stack = new int[width * depth];
        int next = 0;
        for (int start = 0; start < region.length; start++) {
            if (region[start] != -1 || !terrain.isPassable(start % width, start / width)) {
                continue;
            }
            int size = 0;
            stack[size++] = start;
            region[start] = next;
            while (size > 0) {
                int tile = stack[--size];
                int tx = tile % width;
                int tz = tile / width;
                for (int d = 0; d < 4; d++) { // 4-neighbourhood: diagonals need both sides free anyway
                    int nx = tx + DX[d];
                    int nz = tz + DZ[d];
                    if (terrain.isPassable(nx, nz)) {
                        int n = nz * width + nx;
                        if (region[n] == -1) {
                            region[n] = next;
                            stack[size++] = n;
                        }
                    }
                }
            }
            next++;
        }
    }

    // ---------------------------------------------------------------- search

    /**
     * Path from a world position to a goal position, or null if there is none.
     * The last waypoint is the exact goal; the start position itself is not included.
     */
    public Path find(float startX, float startZ, float goalX, float goalZ) {
        int sx = (int) Math.floor(startX);
        int sz = (int) Math.floor(startZ);
        int gx = (int) Math.floor(goalX);
        int gz = (int) Math.floor(goalZ);
        lastExpansions = 0;
        int startRegion = region(sx, sz);
        if (startRegion < 0 || startRegion != region(gx, gz)) {
            return null;
        }
        if (sx == gx && sz == gz) {
            return new Path(new float[]{goalX}, new float[]{goalZ});
        }

        int start = sz * width + sx;
        int goal = gz * width + gx;
        if (++stamp == Integer.MAX_VALUE) {
            Arrays.fill(seenStamp, 0);
            Arrays.fill(closedStamp, 0);
            stamp = 1;
        }
        heapSize = 0;
        g[start] = 0;
        parent[start] = -1;
        seenStamp[start] = stamp;
        push(start, heuristic(sx, sz, gx, gz));

        while (heapSize > 0) {
            int current = pop();
            if (closedStamp[current] == stamp) {
                continue; // stale heap entry
            }
            if (current == goal) {
                return buildPath(start, goal, startX, startZ, goalX, goalZ);
            }
            closedStamp[current] = stamp;
            if (++lastExpansions > MAX_EXPANSIONS) {
                return null;
            }
            int cx = current % width;
            int cz = current / width;
            for (int d = 0; d < 8; d++) {
                int nx = cx + DX[d];
                int nz = cz + DZ[d];
                if (!terrain.isPassable(nx, nz)) {
                    continue;
                }
                boolean diagonal = d >= 4;
                if (diagonal && (!terrain.isPassable(cx + DX[d], cz) || !terrain.isPassable(cx, cz + DZ[d]))) {
                    continue; // no cutting corners
                }
                int n = nz * width + nx;
                if (closedStamp[n] == stamp) {
                    continue;
                }
                float cost = g[current] + (diagonal ? SQRT2 : 1f);
                if (seenStamp[n] != stamp || cost < g[n]) {
                    seenStamp[n] = stamp;
                    g[n] = cost;
                    parent[n] = current;
                    push(n, cost + heuristic(nx, nz, gx, gz));
                }
            }
        }
        return null;
    }

    /** Number of tiles expanded by the last {@link #find} call. */
    public int lastExpansions() {
        return lastExpansions;
    }

    /** Octile distance: exact cost on an empty 8-direction grid. */
    private static float heuristic(int x, int z, int gx, int gz) {
        int dx = Math.abs(x - gx);
        int dz = Math.abs(z - gz);
        return dx + dz + (SQRT2 - 2f) * Math.min(dx, dz);
    }

    private Path buildPath(int start, int goal, float startX, float startZ, float goalX, float goalZ) {
        int count = 0;
        for (int t = goal; t != start; t = parent[t]) {
            count++;
        }
        // Tile centres from the first step to the goal tile; the goal tile uses the exact goal point.
        float[] xs = new float[count];
        float[] zs = new float[count];
        int i = count - 1;
        for (int t = goal; t != start; t = parent[t]) {
            xs[i] = t % width + 0.5f;
            zs[i] = t / width + 0.5f;
            i--;
        }
        xs[count - 1] = goalX;
        zs[count - 1] = goalZ;
        return smooth(startX, startZ, xs, zs);
    }

    /** Skips waypoints while the straight line to a later one stays on passable tiles. */
    private Path smooth(float startX, float startZ, float[] xs, float[] zs) {
        float[] outX = new float[xs.length];
        float[] outZ = new float[zs.length];
        int out = 0;
        float fromX = startX;
        float fromZ = startZ;
        int i = 0;
        while (i < xs.length) {
            int farthest = i;
            while (farthest + 1 < xs.length && hasLineOfSight(fromX, fromZ, xs[farthest + 1], zs[farthest + 1])) {
                farthest++;
            }
            outX[out] = xs[farthest];
            outZ[out] = zs[farthest];
            out++;
            fromX = xs[farthest];
            fromZ = zs[farthest];
            i = farthest + 1;
        }
        return new Path(Arrays.copyOf(outX, out), Arrays.copyOf(outZ, out));
    }

    /**
     * True if every tile touched by the segment is passable (grid traversal, Amanatides &amp; Woo).
     * Passing exactly through a tile corner requires both side tiles to be passable.
     */
    public boolean hasLineOfSight(float x0, float z0, float x1, float z1) {
        int tx = (int) Math.floor(x0);
        int tz = (int) Math.floor(z0);
        int endX = (int) Math.floor(x1);
        int endZ = (int) Math.floor(z1);
        float dx = x1 - x0;
        float dz = z1 - z0;
        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        float tDeltaX = stepX != 0 ? 1f / Math.abs(dx) : Float.POSITIVE_INFINITY;
        float tDeltaZ = stepZ != 0 ? 1f / Math.abs(dz) : Float.POSITIVE_INFINITY;
        float tMaxX = stepX > 0 ? (tx + 1 - x0) * tDeltaX : (stepX < 0 ? (x0 - tx) * tDeltaX : Float.POSITIVE_INFINITY);
        float tMaxZ = stepZ > 0 ? (tz + 1 - z0) * tDeltaZ : (stepZ < 0 ? (z0 - tz) * tDeltaZ : Float.POSITIVE_INFINITY);

        int guard = Math.abs(endX - tx) + Math.abs(endZ - tz) + 2;
        while (guard-- > 0) {
            if (!terrain.isPassable(tx, tz)) {
                return false;
            }
            if (tx == endX && tz == endZ) {
                return true;
            }
            if (tMaxX < tMaxZ) {
                tMaxX += tDeltaX;
                tx += stepX;
            } else if (tMaxZ < tMaxX) {
                tMaxZ += tDeltaZ;
                tz += stepZ;
            } else {
                if (!terrain.isPassable(tx + stepX, tz) || !terrain.isPassable(tx, tz + stepZ)) {
                    return false;
                }
                tMaxX += tDeltaX;
                tMaxZ += tDeltaZ;
                tx += stepX;
                tz += stepZ;
            }
        }
        return terrain.isPassable(endX, endZ);
    }

    // ---------------------------------------------------------------- binary min-heap on f = g + h

    private void push(int node, float key) {
        if (heapSize == heapNodes.length) {
            heapNodes = Arrays.copyOf(heapNodes, heapSize * 2);
            heapKeys = Arrays.copyOf(heapKeys, heapSize * 2);
        }
        int i = heapSize++;
        while (i > 0) {
            int parentIndex = (i - 1) >>> 1;
            if (heapKeys[parentIndex] <= key) {
                break;
            }
            heapNodes[i] = heapNodes[parentIndex];
            heapKeys[i] = heapKeys[parentIndex];
            i = parentIndex;
        }
        heapNodes[i] = node;
        heapKeys[i] = key;
    }

    private int pop() {
        int top = heapNodes[0];
        int lastNode = heapNodes[--heapSize];
        float lastKey = heapKeys[heapSize];
        int i = 0;
        while (true) {
            int child = 2 * i + 1;
            if (child >= heapSize) {
                break;
            }
            if (child + 1 < heapSize && heapKeys[child + 1] < heapKeys[child]) {
                child++;
            }
            if (heapKeys[child] >= lastKey) {
                break;
            }
            heapNodes[i] = heapNodes[child];
            heapKeys[i] = heapKeys[child];
            i = child;
        }
        heapNodes[i] = lastNode;
        heapKeys[i] = lastKey;
        return top;
    }
}

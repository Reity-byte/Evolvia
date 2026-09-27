package evolvia.world;

import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * Uniform grid over the map for "what is near X" queries without scanning every entity
 * (DESIGN.md §6). Stores entity IDs with their positions; moving entities must be updated
 * with {@link #move}.
 */
public final class SpatialGrid {

    private final int cellSize;
    private final int columns;
    private final int rows;
    private final Cell[] cells;
    private int size;

    /**
     * @param width    map width in tiles
     * @param depth    map depth in tiles
     * @param cellSize cell edge in tiles
     */
    public SpatialGrid(int width, int depth, int cellSize) {
        this.cellSize = cellSize;
        this.columns = Math.max(1, (width + cellSize - 1) / cellSize);
        this.rows = Math.max(1, (depth + cellSize - 1) / cellSize);
        this.cells = new Cell[columns * rows];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = new Cell();
        }
    }

    public void insert(int entity, float x, float z) {
        cells[cellIndex(x, z)].add(entity, x, z);
        size++;
    }

    /** Removes an entity stored at (x, z); returns false if it was not there. */
    public boolean remove(int entity, float x, float z) {
        boolean removed = cells[cellIndex(x, z)].remove(entity);
        if (removed) {
            size--;
        }
        return removed;
    }

    /** Updates an entity's position. */
    public void move(int entity, float oldX, float oldZ, float newX, float newZ) {
        int from = cellIndex(oldX, oldZ);
        int to = cellIndex(newX, newZ);
        if (from == to) {
            cells[from].update(entity, newX, newZ);
        } else if (cells[from].remove(entity)) {
            cells[to].add(entity, newX, newZ);
        }
    }

    public int size() {
        return size;
    }

    /**
     * Nearest entity within {@code maxRadius} of (x, z) that passes {@code filter}, or -1.
     * Searches cell rings outwards and stops once no closer entity is possible.
     */
    public int nearest(float x, float z, float maxRadius, IntPredicate filter) {
        int cx = clampColumn(x);
        int cz = clampRow(z);
        int maxRing = (int) Math.ceil(maxRadius / cellSize) + 1;
        float maxDistanceSq = maxRadius * maxRadius;
        float bestSq = Float.POSITIVE_INFINITY;
        int best = -1;
        for (int ring = 0; ring <= maxRing; ring++) {
            float ringMin = (ring - 1) * (float) cellSize; // no entity in this ring is closer than this
            if (ringMin > 0 && ringMin * ringMin > Math.min(bestSq, maxDistanceSq)) {
                break;
            }
            for (int gz = cz - ring; gz <= cz + ring; gz++) {
                if (gz < 0 || gz >= rows) {
                    continue;
                }
                boolean edgeRow = gz == cz - ring || gz == cz + ring;
                for (int gx = cx - ring; gx <= cx + ring; gx += edgeRow ? 1 : 2 * ring) {
                    if (gx >= 0 && gx < columns) {
                        Cell cell = cells[gz * columns + gx];
                        for (int i = 0; i < cell.count; i++) {
                            float dx = cell.xs[i] - x;
                            float dz = cell.zs[i] - z;
                            float dSq = dx * dx + dz * dz;
                            if (dSq < bestSq && dSq <= maxDistanceSq && filter.test(cell.ids[i])) {
                                bestSq = dSq;
                                best = cell.ids[i];
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Calls {@code action} for every entity within {@code radius} of (x, z). */
    public void forEachWithin(float x, float z, float radius, IntConsumer action) {
        int minX = clampColumn(x - radius);
        int maxX = clampColumn(x + radius);
        int minZ = clampRow(z - radius);
        int maxZ = clampRow(z + radius);
        float radiusSq = radius * radius;
        for (int gz = minZ; gz <= maxZ; gz++) {
            for (int gx = minX; gx <= maxX; gx++) {
                Cell cell = cells[gz * columns + gx];
                for (int i = 0; i < cell.count; i++) {
                    float dx = cell.xs[i] - x;
                    float dz = cell.zs[i] - z;
                    if (dx * dx + dz * dz <= radiusSq) {
                        action.accept(cell.ids[i]);
                    }
                }
            }
        }
    }

    private int cellIndex(float x, float z) {
        return clampRow(z) * columns + clampColumn(x);
    }

    private int clampColumn(float x) {
        return Math.clamp((int) Math.floor(x / cellSize), 0, columns - 1);
    }

    private int clampRow(float z) {
        return Math.clamp((int) Math.floor(z / cellSize), 0, rows - 1);
    }

    private static final class Cell {
        int[] ids = new int[8];
        float[] xs = new float[8];
        float[] zs = new float[8];
        int count;

        void add(int entity, float x, float z) {
            if (count == ids.length) {
                ids = Arrays.copyOf(ids, count * 2);
                xs = Arrays.copyOf(xs, count * 2);
                zs = Arrays.copyOf(zs, count * 2);
            }
            ids[count] = entity;
            xs[count] = x;
            zs[count] = z;
            count++;
        }

        boolean remove(int entity) {
            for (int i = 0; i < count; i++) {
                if (ids[i] == entity) {
                    count--;
                    ids[i] = ids[count];
                    xs[i] = xs[count];
                    zs[i] = zs[count];
                    return true;
                }
            }
            return false;
        }

        void update(int entity, float x, float z) {
            for (int i = 0; i < count; i++) {
                if (ids[i] == entity) {
                    xs[i] = x;
                    zs[i] = z;
                    return;
                }
            }
        }
    }
}

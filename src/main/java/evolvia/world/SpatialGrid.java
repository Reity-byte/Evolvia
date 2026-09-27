package evolvia.world;

import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * Uniform grid over the map for "what is near X" queries without scanning every entity
 * (DESIGN.md §6). Stores entity IDs with their positions; moving entities must be updated
 * with {@link #move}. Each entity's cell and slot are remembered, so move and remove are O(1)
 * even in crowded cells.
 */
public final class SpatialGrid {

    private static final int NONE = -1;

    private final int cellSize;
    private final int columns;
    private final int rows;
    private final Cell[] cells;
    /** Per entity ID: index of its cell, or NONE. */
    private int[] cellOf = new int[256];
    /** Per entity ID: slot within its cell. */
    private int[] slotOf = new int[256];
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
        Arrays.fill(cellOf, NONE);
    }

    /** Adds an entity at (x, z). An entity can be stored only once. */
    public void insert(int entity, float x, float z) {
        ensureCapacity(entity);
        if (cellOf[entity] != NONE) {
            throw new IllegalStateException("Entity " + entity + " is already in the grid");
        }
        addToCell(entity, cellIndex(x, z), x, z);
        size++;
    }

    /** Removes an entity; returns false if it was not stored. The position argument is not needed any more. */
    public boolean remove(int entity, float x, float z) {
        if (entity < 0 || entity >= cellOf.length || cellOf[entity] == NONE) {
            return false;
        }
        removeFromCell(entity);
        size--;
        return true;
    }

    /** Updates an entity's position. */
    public void move(int entity, float oldX, float oldZ, float newX, float newZ) {
        if (entity < 0 || entity >= cellOf.length || cellOf[entity] == NONE) {
            return;
        }
        int to = cellIndex(newX, newZ);
        if (cellOf[entity] == to) {
            Cell cell = cells[to];
            int slot = slotOf[entity];
            cell.xs[slot] = newX;
            cell.zs[slot] = newZ;
        } else {
            removeFromCell(entity);
            addToCell(entity, to, newX, newZ);
        }
    }

    public int size() {
        return size;
    }

    /**
     * Nearest entity within {@code maxRadius} of (x, z) that passes {@code filter}, or -1.
     * Searches cell rings outwards (only cells the radius can reach) and stops once no closer
     * entity is possible. Of equally distant entities the lowest ID wins, so the result does not
     * depend on the order inside the cells (a loaded save rebuilds the grid in another order).
     */
    public int nearest(float x, float z, float maxRadius, IntPredicate filter) {
        int cx = clampColumn(x);
        int cz = clampRow(z);
        int minColumn = clampColumn(x - maxRadius);
        int maxColumn = clampColumn(x + maxRadius);
        int minRow = clampRow(z - maxRadius);
        int maxRow = clampRow(z + maxRadius);
        int maxRing = Math.max(Math.max(cx - minColumn, maxColumn - cx), Math.max(cz - minRow, maxRow - cz));
        float maxDistanceSq = maxRadius * maxRadius;
        float bestSq = Float.POSITIVE_INFINITY;
        int best = -1;
        for (int ring = 0; ring <= maxRing; ring++) {
            float ringMin = (ring - 1) * (float) cellSize; // no entity in this ring is closer than this
            if (ringMin > 0 && ringMin * ringMin > Math.min(bestSq, maxDistanceSq)) {
                break;
            }
            for (int gz = Math.max(cz - ring, minRow); gz <= Math.min(cz + ring, maxRow); gz++) {
                boolean edgeRow = gz == cz - ring || gz == cz + ring;
                int step = edgeRow ? 1 : 2 * ring;
                for (int gx = cx - ring; gx <= cx + ring; gx += step) {
                    if (gx < minColumn || gx > maxColumn) {
                        continue;
                    }
                    Cell cell = cells[gz * columns + gx];
                    for (int i = 0; i < cell.count; i++) {
                        float dx = cell.xs[i] - x;
                        float dz = cell.zs[i] - z;
                        float dSq = dx * dx + dz * dz;
                        boolean closer = dSq < bestSq || (dSq == bestSq && cell.ids[i] < best);
                        if (closer && dSq <= maxDistanceSq && filter.test(cell.ids[i])) {
                            bestSq = dSq;
                            best = cell.ids[i];
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

    private void addToCell(int entity, int cellIndex, float x, float z) {
        Cell cell = cells[cellIndex];
        cell.add(entity, x, z);
        cellOf[entity] = cellIndex;
        slotOf[entity] = cell.count - 1;
    }

    private void removeFromCell(int entity) {
        Cell cell = cells[cellOf[entity]];
        int slot = slotOf[entity];
        int last = cell.count - 1;
        if (slot != last) {
            int moved = cell.ids[last];
            cell.ids[slot] = moved;
            cell.xs[slot] = cell.xs[last];
            cell.zs[slot] = cell.zs[last];
            slotOf[moved] = slot;
        }
        cell.count--;
        cellOf[entity] = NONE;
    }

    private void ensureCapacity(int entity) {
        if (entity < 0) {
            throw new IllegalArgumentException("Invalid entity id " + entity);
        }
        if (entity >= cellOf.length) {
            int oldLength = cellOf.length;
            int newLength = Math.max(entity + 1, oldLength * 2);
            cellOf = Arrays.copyOf(cellOf, newLength);
            slotOf = Arrays.copyOf(slotOf, newLength);
            Arrays.fill(cellOf, oldLength, newLength, NONE);
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
    }
}

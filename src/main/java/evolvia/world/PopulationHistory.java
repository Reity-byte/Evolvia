package evolvia.world;

/**
 * Population and food over time, sampled every {@link #SAMPLE_INTERVAL_TICKS} ticks into a ring
 * buffer (for the population graph).
 */
public final class PopulationHistory {

    /** One sample every 10 game seconds. */
    public static final int SAMPLE_INTERVAL_TICKS = 200;
    /** Two hours of game time. */
    public static final int CAPACITY = 720;

    private final int[] population = new int[CAPACITY];
    private final float[] food = new float[CAPACITY];
    private int start;
    private int size;

    public void record(int populationValue, float foodValue) {
        int index = (start + size) % CAPACITY;
        if (size == CAPACITY) {
            start = (start + 1) % CAPACITY;
        } else {
            size++;
        }
        population[index] = populationValue;
        food[index] = foodValue;
    }

    /** Number of samples (at most {@link #CAPACITY}). */
    public int size() {
        return size;
    }

    /** Population of sample {@code i}, 0 = oldest. */
    public int population(int i) {
        return population[(start + i) % CAPACITY];
    }

    /** Food units in the world at sample {@code i}, 0 = oldest. */
    public float food(int i) {
        return food[(start + i) % CAPACITY];
    }

    public int maxPopulation() {
        int max = 0;
        for (int i = 0; i < size; i++) {
            max = Math.max(max, population(i));
        }
        return max;
    }

    public float maxFood() {
        float max = 0;
        for (int i = 0; i < size; i++) {
            max = Math.max(max, food(i));
        }
        return max;
    }
}

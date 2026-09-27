package evolvia.world;

import java.util.Arrays;

/**
 * Matings that happened this tick. The AI records them; the reproduction system spawns the
 * offspring later in the tick, so no entities are created while the AI iterates its components.
 */
public final class Births {

    private int[] parentsA = new int[16];
    private int[] parentsB = new int[16];
    private float[] xs = new float[16];
    private float[] zs = new float[16];
    private int count;
    private int total;

    /** Records a mating of two parents; offspring appear at (x, z). */
    public void add(int parentA, int parentB, float x, float z) {
        if (count == parentsA.length) {
            parentsA = Arrays.copyOf(parentsA, count * 2);
            parentsB = Arrays.copyOf(parentsB, count * 2);
            xs = Arrays.copyOf(xs, count * 2);
            zs = Arrays.copyOf(zs, count * 2);
        }
        parentsA[count] = parentA;
        parentsB[count] = parentB;
        xs[count] = x;
        zs[count] = z;
        count++;
    }

    public int pending() {
        return count;
    }

    public int parentA(int i) {
        return parentsA[i];
    }

    public int parentB(int i) {
        return parentsB[i];
    }

    public float x(int i) {
        return xs[i];
    }

    public float z(int i) {
        return zs[i];
    }

    /** Called by the reproduction system after spawning the offspring. */
    public void clear(int offspringBorn) {
        count = 0;
        total += offspringBorn;
    }

    /** Offspring born since the world started. */
    public int total() {
        return total;
    }
}

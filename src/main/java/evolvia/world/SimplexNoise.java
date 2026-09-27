package evolvia.world;

import java.util.Random;

/**
 * Seeded 2D simplex noise (after Stefan Gustavson's public-domain reference implementation).
 * Output is roughly in [-1, 1]. Deterministic for a given {@link Random} state.
 */
public final class SimplexNoise {

    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
    private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
    private static final int[][] GRADIENTS = {
            {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
            {1, 0}, {-1, 0}, {1, 0}, {-1, 0},
            {0, 1}, {0, -1}, {0, 1}, {0, -1},
    };

    private final short[] perm = new short[512];

    /** Creates a noise instance with a permutation table shuffled by {@code random}. */
    public SimplexNoise(Random random) {
        short[] p = new short[256];
        for (short i = 0; i < 256; i++) {
            p[i] = i;
        }
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            short tmp = p[i];
            p[i] = p[j];
            p[j] = tmp;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = p[i & 255];
        }
    }

    /** Single-octave simplex noise at (x, y), roughly in [-1, 1]. */
    public double noise(double x, double y) {
        double s = (x + y) * F2;
        int i = fastFloor(x + s);
        int j = fastFloor(y + s);
        double t = (i + j) * G2;
        double x0 = x - (i - t);
        double y0 = y - (j - t);

        int i1 = x0 > y0 ? 1 : 0;
        int j1 = x0 > y0 ? 0 : 1;

        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        int ii = i & 255;
        int jj = j & 255;
        int g0 = perm[ii + perm[jj]] % 12;
        int g1 = perm[ii + i1 + perm[jj + j1]] % 12;
        int g2 = perm[ii + 1 + perm[jj + 1]] % 12;

        return 70.0 * (corner(g0, x0, y0) + corner(g1, x1, y1) + corner(g2, x2, y2));
    }

    /**
     * Fractal Brownian motion: sum of {@code octaves} noise layers, each with frequency
     * multiplied by {@code lacunarity} and amplitude by {@code gain}. Normalized to roughly [-1, 1].
     */
    public double fbm(double x, double y, int octaves, double lacunarity, double gain) {
        double sum = 0;
        double amplitude = 1;
        double frequency = 1;
        double amplitudeSum = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amplitude * noise(x * frequency, y * frequency);
            amplitudeSum += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return sum / amplitudeSum;
    }

    private static double corner(int gradient, double x, double y) {
        double t = 0.5 - x * x - y * y;
        if (t < 0) {
            return 0;
        }
        t *= t;
        return t * t * (GRADIENTS[gradient][0] * x + GRADIENTS[gradient][1] * y);
    }

    private static int fastFloor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}

package evolvia.world;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimplexNoiseTest {

    @Test
    void sameSeedGivesSameValues() {
        SimplexNoise a = new SimplexNoise(new Random(7));
        SimplexNoise b = new SimplexNoise(new Random(7));
        for (int i = 0; i < 1000; i++) {
            double x = i * 0.37;
            double y = i * 0.11 - 50;
            assertEquals(a.noise(x, y), b.noise(x, y));
        }
    }

    @Test
    void differentSeedsGiveDifferentValues() {
        SimplexNoise a = new SimplexNoise(new Random(1));
        SimplexNoise b = new SimplexNoise(new Random(2));
        int differences = 0;
        for (int i = 0; i < 100; i++) {
            if (a.noise(i * 0.5, i * 0.3) != b.noise(i * 0.5, i * 0.3)) {
                differences++;
            }
        }
        assertTrue(differences > 90, "only " + differences + " of 100 samples differ");
    }

    @Test
    void valuesStayInRange() {
        SimplexNoise noise = new SimplexNoise(new Random(3));
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int x = 0; x < 300; x++) {
            for (int y = 0; y < 300; y++) {
                double v = noise.noise(x * 0.071, y * 0.053);
                min = Math.min(min, v);
                max = Math.max(max, v);
                double f = noise.fbm(x * 0.02, y * 0.02, 5, 2.0, 0.5);
                assertTrue(f >= -1.0 && f <= 1.0, "fbm out of range: " + f);
            }
        }
        assertTrue(min >= -1.0 && max <= 1.0, "noise out of range: [" + min + ", " + max + "]");
        // The noise should actually use most of its range.
        assertTrue(min < -0.6 && max > 0.6, "noise range too narrow: [" + min + ", " + max + "]");
    }

    @Test
    void noiseIsContinuous() {
        SimplexNoise noise = new SimplexNoise(new Random(4));
        for (int i = 0; i < 1000; i++) {
            double x = i * 0.013;
            double v = noise.noise(x, 1.5);
            assertTrue(Double.isFinite(v));
            assertTrue(Math.abs(v - noise.noise(x + 0.001, 1.5)) < 0.05);
        }
    }
}

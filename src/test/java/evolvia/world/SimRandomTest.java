package evolvia.world;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimRandomTest {

    @Test
    void sameSequenceAsJavaUtilRandom() {
        for (long seed : new long[]{0L, 7L, -123456789L, Long.MAX_VALUE}) {
            Random expected = new Random(seed);
            SimRandom actual = new SimRandom(seed);
            for (int i = 0; i < 2000; i++) {
                switch (i % 8) {
                    case 0 -> assertEquals(expected.nextInt(), actual.nextInt());
                    case 1 -> assertEquals(expected.nextInt(1000), actual.nextInt(1000));
                    case 2 -> assertEquals(expected.nextFloat(), actual.nextFloat());
                    case 3 -> assertEquals(expected.nextDouble(), actual.nextDouble());
                    case 4 -> assertEquals(expected.nextGaussian(), actual.nextGaussian());
                    case 5 -> assertEquals(expected.nextLong(), actual.nextLong());
                    case 6 -> assertEquals(expected.nextBoolean(), actual.nextBoolean());
                    default -> assertEquals(expected.nextInt(7, 99), actual.nextInt(7, 99));
                }
            }
        }
    }

    @Test
    void restoredGeneratorContinuesTheSameSequence() {
        SimRandom original = new SimRandom(42L);
        for (int i = 0; i < 101; i++) {
            original.nextGaussian(); // odd count: a cached second Gaussian is part of the state
        }
        SimRandom restored = SimRandom.restore(original.state());
        for (int i = 0; i < 500; i++) {
            assertEquals(original.nextGaussian(), restored.nextGaussian());
            assertEquals(original.nextInt(50), restored.nextInt(50));
        }
    }
}

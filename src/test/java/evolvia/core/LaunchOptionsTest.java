package evolvia.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchOptionsTest {

    @Test
    void noArgumentsMeansDefaults() {
        LaunchOptions options = LaunchOptions.parse(new String[0]);
        assertTrue(options.seed().isEmpty());
        assertTrue(options.fpsCap().isEmpty());
    }

    @Test
    void parsesSeedAndFpsCap() {
        LaunchOptions options = LaunchOptions.parse(new String[]{"--seed", "-42", "--fps-cap", "0"});
        assertEquals(-42, options.seed().getAsLong());
        assertEquals(0, options.fpsCap().getAsInt());
        assertTrue(options.load().isEmpty());
    }

    @Test
    void parsesSaveToLoad() {
        assertEquals("Můj svět", LaunchOptions.parse(new String[]{"--load", "Můj svět"}).load().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parse(new String[]{"--load"}));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parse(new String[]{"--seed"}));
        assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parse(new String[]{"--seed", "abc"}));
        assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parse(new String[]{"--fps-cap", "-1"}));
        assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parse(new String[]{"--fullscreen"}));
    }
}

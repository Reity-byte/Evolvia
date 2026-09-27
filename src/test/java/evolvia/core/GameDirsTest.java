package evolvia.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameDirsTest {

    private static final String HOME = "/home/player";

    @Test
    void windowsUsesAppData() {
        Map<String, String> env = Map.of("APPDATA", "C:/Users/p/AppData/Roaming");
        assertEquals(Path.of("C:/Users/p/AppData/Roaming", "Evolvia"),
                GameDirs.userDataDir(Platform.WINDOWS, env::get, HOME));
    }

    @Test
    void windowsWithoutAppDataFallsBackToProfile() {
        assertEquals(Path.of(HOME, "AppData", "Roaming", "Evolvia"),
                GameDirs.userDataDir(Platform.WINDOWS, name -> null, HOME));
    }

    @Test
    void macUsesApplicationSupport() {
        assertEquals(Path.of(HOME, "Library", "Application Support", "Evolvia"),
                GameDirs.userDataDir(Platform.MACOS, name -> null, HOME));
    }

    @Test
    void linuxUsesXdgDataHomeOrLocalShare() {
        Map<String, String> env = Map.of("XDG_DATA_HOME", "/data");
        assertEquals(Path.of("/data", "evolvia"), GameDirs.userDataDir(Platform.LINUX, env::get, HOME));
        assertEquals(Path.of(HOME, ".local", "share", "evolvia"),
                GameDirs.userDataDir(Platform.LINUX, name -> "", HOME));
    }

    @Test
    void platformFromOsName() {
        assertEquals(Platform.WINDOWS, Platform.fromOsName("Windows 11"));
        assertEquals(Platform.MACOS, Platform.fromOsName("Mac OS X"));
        assertEquals(Platform.LINUX, Platform.fromOsName("Linux"));
    }
}

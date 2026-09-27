package evolvia.core;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * Root folder for everything the game writes (saves, settings, logs) and, for the launcher,
 * the installed game versions ({@code versions/}). All versions share one folder, so an update
 * never touches saves.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>system property {@code -Devolvia.home=...}</li>
 *   <li>environment variable {@code EVOLVIA_HOME} (the launcher sets it for the game it starts)</li>
 *   <li>after {@link #useUserData()} (player entry points {@code Launch} / {@code LauncherMain}):
 *       the OS user data folder; otherwise (development via {@code Main}): {@code ./run}</li>
 * </ol>
 * Classes that derive file paths must call {@link #root()} only after the entry point has chosen
 * the root, which is why the player entry points call {@link #useUserData()} before anything else.
 */
public final class GameDirs {

    public static final String HOME_PROPERTY = "evolvia.home";
    public static final String HOME_ENV = "EVOLVIA_HOME";
    /** Data folder name on Windows and macOS. */
    public static final String FOLDER_NAME = "Evolvia";
    /** Data folder name on Linux (XDG convention: lower case). */
    public static final String LINUX_FOLDER_NAME = "evolvia";
    /** Development data folder, relative to the working directory (git-ignored). */
    private static final Path DEV_ROOT = Path.of("run");

    private static Path root = override(System::getenv).orElse(DEV_ROOT).toAbsolutePath().normalize();

    private GameDirs() {
    }

    /** Switches to the OS user data folder (unless overridden). Called first by player entry points. */
    public static void useUserData() {
        root = override(System::getenv)
                .orElseGet(() -> userDataDir(Platform.current(), System::getenv, System.getProperty("user.home")))
                .toAbsolutePath().normalize();
    }

    /** Data root folder. May not exist yet. */
    public static Path root() {
        return root;
    }

    /** Folder with game versions installed by the launcher. */
    public static Path versions() {
        return root.resolve("versions");
    }

    private static Optional<Path> override(Function<String, String> env) {
        String property = System.getProperty(HOME_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Optional.of(Path.of(property));
        }
        String variable = env.apply(HOME_ENV);
        if (variable != null && !variable.isBlank()) {
            return Optional.of(Path.of(variable));
        }
        return Optional.empty();
    }

    /**
     * OS-specific user data folder:
     * Windows {@code %APPDATA%\Evolvia}, macOS {@code ~/Library/Application Support/Evolvia},
     * Linux {@code $XDG_DATA_HOME/evolvia} or {@code ~/.local/share/evolvia}.
     */
    static Path userDataDir(Platform platform, Function<String, String> env, String userHome) {
        return switch (platform) {
            case WINDOWS -> {
                String appData = env.apply("APPDATA");
                Path base = appData != null && !appData.isBlank()
                        ? Path.of(appData)
                        : Path.of(userHome, "AppData", "Roaming");
                yield base.resolve(FOLDER_NAME);
            }
            case MACOS -> Path.of(userHome, "Library", "Application Support", FOLDER_NAME);
            case LINUX -> {
                String xdg = env.apply("XDG_DATA_HOME");
                Path base = xdg != null && !xdg.isBlank()
                        ? Path.of(xdg)
                        : Path.of(userHome, ".local", "share");
                yield base.resolve(LINUX_FOLDER_NAME);
            }
        };
    }
}

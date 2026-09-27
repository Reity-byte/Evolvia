package evolvia;

import evolvia.core.GameDirs;
import evolvia.core.Platform;
import evolvia.launcher.LauncherCore;
import evolvia.launcher.LauncherWindow;
import evolvia.launcher.Release;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Launcher entry point: downloads/updates the game from GitHub Releases and starts it.
 * <p>
 * Options:
 * <ul>
 *   <li>{@code --local <dir>}: use a local release folder made by {@code packaging/package.ps1}
 *       instead of GitHub (for testing builds without CI)</li>
 *   <li>{@code --check}: print the latest release and the matching package, then exit (no window)</li>
 * </ul>
 */
public final class LauncherMain {

    private static final String USAGE = "Usage: EvolviaLauncher [--local <release folder>] [--check]";

    private LauncherMain() {
    }

    public static void main(String[] args) {
        GameDirs.useUserData();

        Path localReleases = null;
        boolean check = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--local" -> {
                    if (i + 1 >= args.length) {
                        exitWithUsage("--local needs a folder");
                    }
                    localReleases = Path.of(args[++i]);
                }
                case "--check" -> check = true;
                default -> exitWithUsage("Unknown argument: " + args[i]);
            }
        }

        LauncherCore core = localReleases != null
                ? new LauncherCore(GameDirs.root(), Platform.current(), LauncherCore.localReleaseSource(localReleases))
                : new LauncherCore(GameDirs.root(), Platform.current(), LauncherCore.GITHUB_LATEST_RELEASE);

        if (check) {
            System.exit(printCheck(core));
        }
        LauncherWindow.open(core);
    }

    private static int printCheck(LauncherCore core) {
        System.out.println("Data folder: " + core.dataRoot());
        System.out.println("Installed:   " + Optional.ofNullable(core.installedVersion()).orElse("(none)"));
        try {
            Release latest = core.latest();
            System.out.println("Latest:      " + latest.tag());
            System.out.println("Package:     " + LauncherCore.assetFor(latest, core.platform())
                    .map(Release.Asset::name).orElse("(none for " + core.platform().key() + ")"));
            return 0;
        } catch (Exception e) {
            System.out.println("Latest:      unavailable - " + e.getMessage());
            return 1;
        }
    }

    private static void exitWithUsage(String message) {
        System.err.println(message);
        System.err.println(USAGE);
        System.exit(2);
    }
}

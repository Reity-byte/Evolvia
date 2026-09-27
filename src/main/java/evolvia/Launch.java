package evolvia;

import evolvia.core.GameDirs;

/**
 * Game entry point for players (the packaged game started by the launcher).
 * <p>
 * Chooses the user data folder first and only then hands over to {@link Main}. It must stay
 * a separate class that touches nothing else: classes that derive paths from {@link GameDirs}
 * (e.g. static path constants in data loaders or saves) must not be loaded before the root is set.
 * Development runs ({@code Main} from the IDE or {@code java -jar}) keep their data in {@code ./run}.
 */
public final class Launch {

    private Launch() {
    }

    public static void main(String[] args) {
        GameDirs.useUserData();
        Main.main(args);
    }
}

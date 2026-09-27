package evolvia;

import evolvia.core.LaunchOptions;

/**
 * Development entry point (IDE, {@code java -jar target/evolvia.jar}): data stays in {@code ./run}.
 * Packaged builds start through {@link Launch}, which switches to the user data folder first.
 * <p>
 * On macOS the JVM must be started with {@code -XstartOnFirstThread} (GLFW requirement).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        LaunchOptions options;
        try {
            options = LaunchOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(LaunchOptions.USAGE);
            System.exit(2);
            return;
        }
        new Evolvia(options).run();
    }
}

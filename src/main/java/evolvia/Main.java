package evolvia;

import evolvia.core.LaunchOptions;

/**
 * Entry point.
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

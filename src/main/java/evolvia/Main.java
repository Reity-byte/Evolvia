package evolvia;

/**
 * Entry point.
 * <p>
 * On macOS the JVM must be started with {@code -XstartOnFirstThread} (GLFW requirement).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        new Evolvia().run();
    }
}

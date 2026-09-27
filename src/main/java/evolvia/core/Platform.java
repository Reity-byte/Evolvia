package evolvia.core;

import java.util.Locale;

/**
 * Operating system family, used for data folder locations and release package names.
 */
public enum Platform {
    WINDOWS("windows"),
    MACOS("macos"),
    LINUX("linux");

    private final String key;

    Platform(String key) {
        this.key = key;
    }

    /** Short name used in release package names, e.g. {@code Evolvia-windows.zip}. */
    public String key() {
        return key;
    }

    public static Platform current() {
        return fromOsName(System.getProperty("os.name", ""));
    }

    static Platform fromOsName(String osName) {
        String name = osName.toLowerCase(Locale.ROOT);
        if (name.startsWith("windows")) {
            return WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        }
        return LINUX;
    }
}

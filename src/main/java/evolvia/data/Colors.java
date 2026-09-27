package evolvia.data;

/**
 * Color parsing for data files.
 */
public final class Colors {

    private Colors() {
    }

    /**
     * Parses {@code "#RRGGBB"} into 0xRRGGBB.
     *
     * @param where description of the value for the error message
     */
    public static int parseHex(String hex, String where) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}")) {
            throw new IllegalStateException(where + " must be a color in the form \"#RRGGBB\", got: " + hex);
        }
        return Integer.parseInt(hex.substring(1), 16);
    }
}

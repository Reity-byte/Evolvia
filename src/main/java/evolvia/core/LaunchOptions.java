package evolvia.core;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Command line options.
 *
 * @param seed   world seed ({@code --seed <number>}); random when absent
 * @param fpsCap frame rate cap ({@code --fps-cap <number>}, 0 = unlimited); monitor refresh rate when absent
 * @param load   save game to load at start ({@code --load <name>}); a new world when absent
 */
public record LaunchOptions(OptionalLong seed, OptionalInt fpsCap, Optional<String> load) {

    public static final String USAGE = "Usage: java -jar evolvia.jar [--seed <number>] [--fps-cap <fps, 0 = unlimited>] [--load <save name>]";

    /** Parses the arguments; throws {@link IllegalArgumentException} with a readable message on bad input. */
    public static LaunchOptions parse(String[] args) {
        OptionalLong seed = OptionalLong.empty();
        OptionalInt fpsCap = OptionalInt.empty();
        Optional<String> load = Optional.empty();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--seed" -> seed = OptionalLong.of(parseLong(arg, value(args, ++i, arg)));
                case "--fps-cap" -> {
                    long cap = parseLong(arg, value(args, ++i, arg));
                    if (cap < 0 || cap > 10_000) {
                        throw new IllegalArgumentException(arg + " must be between 0 and 10000");
                    }
                    fpsCap = OptionalInt.of((int) cap);
                }
                case "--load" -> load = Optional.of(value(args, ++i, arg));
                default -> throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        return new LaunchOptions(seed, fpsCap, load);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static long parseLong(String option, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " expects a whole number, got: " + value);
        }
    }
}

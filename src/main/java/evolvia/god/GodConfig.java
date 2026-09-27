package evolvia.god;

/**
 * Faith and god power parameters, loaded from {@code data/powers.json} (DESIGN.md §9).
 */
public record GodConfig(FaithSettings faith, Rain rain, Abundance abundance, TerrainBrush raise, TerrainBrush lower,
                        Lightning lightning) {

    /**
     * @param starting             faith at the start of a game
     * @param basePerMinute        income without believers (so the game never gets stuck)
     * @param perBelieverPerMinute income for every believing creature
     */
    public record FaithSettings(float starting, float basePerMinute, float perBelieverPerMinute) {
    }

    /** What every power has: display texts, faith cost, area radius and the shift on the good/evil axis. */
    public interface Power {
        String name();

        String description();

        float cost();

        float radius();

        /** Shift of the god's alignment per use (positive = good). */
        float alignment();
    }

    public record Rain(String name, String description, float cost, float radius, float alignment,
                       float durationSeconds, float regrowMultiplier, float thirstReliefPerSecond) implements Power {
    }

    /**
     * @param resource food resource that is refilled and spawned
     * @param newNodes new nodes placed in the area
     */
    public record Abundance(String name, String description, float cost, float radius, float alignment,
                            String resource, int newNodes) implements Power {
    }

    /**
     * @param step          height change at the centre per use (world units, soft edge)
     * @param repeatSeconds while the mouse button is held, the power repeats this often (real time)
     */
    public record TerrainBrush(String name, String description, float cost, float radius, float alignment,
                               float step, float repeatSeconds) implements Power {
    }

    /**
     * @param radius       creatures this close to the strike die
     * @param maxKills     at most this many die per strike
     * @param scareRadius  creatures within this distance flee and start believing (from fear)
     * @param scareSeconds how long they flee
     * @param fleeDistance how far they try to run
     */
    public record Lightning(String name, String description, float cost, float radius, float alignment,
                            int maxKills, float scareRadius, float scareSeconds, float fleeDistance) implements Power {
    }

    /** The parameters of a power. */
    public Power of(DivinePower power) {
        return switch (power) {
            case RAIN -> rain;
            case ABUNDANCE -> abundance;
            case RAISE -> raise;
            case LOWER -> lower;
            case LIGHTNING -> lightning;
        };
    }

    /** @throws IllegalArgumentException with the file name and the problem */
    public void validate(String source) {
        check(faith != null, source, "missing \"faith\"");
        check(faith.starting() >= 0 && faith.basePerMinute() >= 0 && faith.perBelieverPerMinute() >= 0, source,
                "faith values must not be negative");
        for (DivinePower power : DivinePower.values()) {
            Power p = of(power);
            String where = "\"" + power.key() + "\"";
            check(p != null, source, "missing " + where);
            check(p.name() != null && !p.name().isBlank(), source, where + ": missing \"name\"");
            check(p.description() != null, source, where + ": missing \"description\"");
            check(p.cost() > 0, source, where + ": \"cost\" must be > 0");
            check(p.radius() > 0, source, where + ": \"radius\" must be > 0");
            check(p.alignment() >= -1 && p.alignment() <= 1, source, where + ": \"alignment\" must be in -1..1");
        }
        check(rain.durationSeconds() > 0 && rain.regrowMultiplier() >= 1 && rain.thirstReliefPerSecond() >= 0, source,
                "\"rain\": durationSeconds > 0, regrowMultiplier >= 1, thirstReliefPerSecond >= 0");
        check(abundance.resource() != null && abundance.newNodes() >= 0, source, "\"abundance\": missing \"resource\" or negative \"newNodes\"");
        for (TerrainBrush brush : new TerrainBrush[]{raise, lower}) {
            check(brush.step() > 0 && brush.repeatSeconds() > 0, source, "terrain powers: \"step\" and \"repeatSeconds\" must be > 0");
        }
        check(lightning.maxKills() >= 0 && lightning.scareRadius() >= lightning.radius() && lightning.scareSeconds() > 0
                && lightning.fleeDistance() > 0, source, "\"lightning\": invalid maxKills / scareRadius / scareSeconds / fleeDistance");
    }

    private static void check(boolean condition, String source, String message) {
        if (!condition) {
            throw new IllegalArgumentException(source + ": " + message);
        }
    }
}

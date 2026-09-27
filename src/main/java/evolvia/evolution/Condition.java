package evolvia.evolution;

import java.util.Locale;

/** Extra world condition for unlocking a node ({@code requiresCondition}). */
public sealed interface Condition {

    boolean isMet(EvolutionConditions world);

    /** Short readable description (Czech, for the UI). */
    String describe();

    /** At least {@code value} living creatures of the species. */
    record PopulationMin(int value) implements Condition {
        @Override
        public boolean isMet(EvolutionConditions world) {
            return world.population() >= value;
        }

        @Override
        public String describe() {
            return "populace alespoň " + value;
        }
    }

    /** At least {@code ratio} of the population lives in the given biome. */
    record BiomePresence(String biome, float ratio) implements Condition {
        @Override
        public boolean isMet(EvolutionConditions world) {
            return world.biomeRatio(biome) >= ratio;
        }

        @Override
        public String describe() {
            return String.format(Locale.ROOT, "alespoň %.0f %% populace v biomu %s", ratio * 100, biome);
        }
    }
}

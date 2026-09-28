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

    /** The people made the discovery {@code id} (science, phase 10b); {@code name} is for the UI. */
    record Discovery(String id, String name) implements Condition {
        @Override
        public boolean isMet(EvolutionConditions world) {
            return world.isDiscovered(id);
        }

        @Override
        public String describe() {
            return "vyžaduje objev " + name;
        }
    }

    /** The species evolved the node {@code id} (a discovery that needs a body or mind first, phase 10b). */
    record Evolved(String id, String name) implements Condition {
        @Override
        public boolean isMet(EvolutionConditions world) {
            return world.isEvolved(id);
        }

        @Override
        public String describe() {
            return "vyžaduje evoluci " + name;
        }
    }

    /** All of several conditions. */
    record All(java.util.List<Condition> conditions) implements Condition {
        @Override
        public boolean isMet(EvolutionConditions world) {
            for (Condition condition : conditions) {
                if (!condition.isMet(world)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String describe() {
            return String.join(", ", conditions.stream().map(Condition::describe).toList());
        }

        /** The first condition that is not met, or null. */
        public Condition firstUnmet(EvolutionConditions world) {
            for (Condition condition : conditions) {
                if (!condition.isMet(world)) {
                    return condition;
                }
            }
            return null;
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

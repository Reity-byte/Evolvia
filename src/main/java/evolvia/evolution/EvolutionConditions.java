package evolvia.evolution;

/** The facts about the world that node conditions can check. */
public interface EvolutionConditions {

    /** Number of living creatures of the species. */
    int population();

    /** Fraction (0..1) of the species' creatures standing in the biome with this id. */
    float biomeRatio(String biomeId);
}

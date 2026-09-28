package evolvia.components;

import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;

/**
 * Marks an entity as a creature of a species and records the evolutionary stage it was born with:
 * its stats, abilities and looks come from that stage, not from the species' latest unlocks
 * (generational evolution, DESIGN.md §7.3).
 */
public final class SpeciesRef {

    public final Species species;
    /** Evolutionary stage (see {@link Species#stage(int)}); fixed for life. */
    public int stage;

    /** A creature of the species' latest stage. */
    public SpeciesRef(Species species) {
        this(species, species.latestStage().index());
    }

    public SpeciesRef(Species species, int stage) {
        this.species = species;
        this.stage = stage;
    }

    public Species.Stage stageData() {
        return species.stage(stage);
    }

    /** This creature's stats (its stage's). */
    public SpeciesDefinition stats() {
        return species.stage(stage).stats();
    }

    public boolean hasAbility(String ability) {
        return species.stage(stage).hasAbility(ability);
    }
}

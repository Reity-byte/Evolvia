package evolvia.components;

import evolvia.evolution.Species;

/** Marks an entity as a creature of a species (the species state is shared, not copied). */
public final class SpeciesRef {

    public final Species species;

    public SpeciesRef(Species species) {
        this.species = species;
    }
}

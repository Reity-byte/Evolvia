package evolvia.components;

import evolvia.evolution.SpeciesDefinition;

/** Marks an entity as a creature of a species (the species state is shared, not copied). */
public final class SpeciesRef {

    public final SpeciesDefinition species;

    public SpeciesRef(SpeciesDefinition species) {
        this.species = species;
    }
}

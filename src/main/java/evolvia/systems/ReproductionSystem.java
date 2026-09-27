package evolvia.systems;

import evolvia.components.Believer;
import evolvia.components.Genome;
import evolvia.components.SpeciesRef;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Births;
import evolvia.world.CreatureFactory;
import evolvia.world.Terrain;

/**
 * Spawns the offspring of this tick's matings (recorded by the SeekMate action): genome inherited
 * from both parents with mutation, placed next to the parents on passable ground.
 */
public final class ReproductionSystem implements GameSystem {

    /** Offspring start slightly hungry and thirsty but rested. */
    private static final float NEWBORN_NEED = 0.2f;

    private final Births births;
    private final CreatureFactory factory;
    private final Terrain terrain;
    private int maxGeneration;

    public ReproductionSystem(Births births, CreatureFactory factory, Terrain terrain) {
        this.births = births;
        this.factory = factory;
        this.terrain = terrain;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        int born = 0;
        for (int i = 0; i < births.pending(); i++) {
            SpeciesRef ref = world.get(births.parentA(i), SpeciesRef.class);
            Genome a = world.get(births.parentA(i), Genome.class);
            Genome b = world.get(births.parentB(i), Genome.class);
            if (ref == null || a == null || b == null) {
                continue; // a parent vanished; should not happen within one tick
            }
            Species kind = ref.species;
            SpeciesDefinition species = kind.stats();
            int readyAt = tick + SpeciesDefinition.secondsToTicks(species.reproduction().adultAgeSeconds());
            boolean believer = world.get(births.parentA(i), Believer.class) != null
                    || world.get(births.parentB(i), Believer.class) != null; // raised in the faith
            for (int n = 0; n < species.reproduction().litterSize(); n++) {
                float x = births.x(i) + (factory.random().nextFloat() - 0.5f) * 0.6f;
                float z = births.z(i) + (factory.random().nextFloat() - 0.5f) * 0.6f;
                if (!terrain.isPassable((int) Math.floor(x), (int) Math.floor(z))) {
                    x = births.x(i);
                    z = births.z(i);
                }
                Genome genome = factory.inherit(species, a, b);
                maxGeneration = Math.max(maxGeneration, genome.generation);
                int child = factory.spawn(kind, genome, x, z, 0, NEWBORN_NEED, NEWBORN_NEED, 1f, readyAt);
                if (believer) {
                    world.add(child, new Believer());
                }
                born++;
            }
        }
        births.clear(born);
    }

    /** Highest generation born so far. */
    public int maxGeneration() {
        return maxGeneration;
    }
}

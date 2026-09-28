package evolvia.world;

import evolvia.ai.Navigation;
import evolvia.components.Age;
import evolvia.components.AiState;
import evolvia.components.Genome;
import evolvia.components.Health;
import evolvia.components.Memory;
import evolvia.components.Needs;
import evolvia.components.PrevTransform;
import evolvia.components.Reproduction;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.components.Velocity;
import evolvia.ecs.EcsWorld;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;

import java.util.Random;

/**
 * Creates creature entities with all their components (used for the starting population and for
 * births) and builds genomes: random for the starting population, inherited with mutation for offspring.
 */
public final class CreatureFactory {

    private static final float TWO_PI = (float) (Math.PI * 2);

    private final EcsWorld ecs;
    private final Terrain terrain;
    private final SpatialGrid creatureGrid;
    private final Random random;

    public CreatureFactory(EcsWorld ecs, Terrain terrain, SpatialGrid creatureGrid, Random random) {
        this.ecs = ecs;
        this.terrain = terrain;
        this.creatureGrid = creatureGrid;
        this.random = random;
    }

    /**
     * Spawns a creature at (x, z) on the ground.
     *
     * @param stage       evolutionary stage the creature is born with
     * @param ageTicks    starting age
     * @param readyAtTick first tick at which it may reproduce
     */
    public int spawn(Species kind, int stage, Genome genome, float x, float z, int ageTicks,
                     float hunger, float thirst, float energy, int readyAtTick) {
        SpeciesDefinition species = kind.stage(stage).stats();
        int entity = ecs.createEntity();
        Transform transform = ecs.add(entity, new Transform());
        transform.position.set(x, Navigation.groundHeight(terrain, x, z), z);
        transform.yaw = random.nextFloat() * TWO_PI;
        PrevTransform prev = ecs.add(entity, new PrevTransform());
        prev.position.set(transform.position);
        prev.yaw = transform.yaw;
        ecs.add(entity, new Velocity());
        ecs.add(entity, new SpeciesRef(kind, stage));
        ecs.add(entity, genome);

        Needs needs = ecs.add(entity, new Needs());
        needs.hunger = hunger;
        needs.thirst = thirst;
        needs.energy = energy;
        ecs.add(entity, new Health(species.maxHealth()));

        int lifespanMin = SpeciesDefinition.secondsToTicks(species.lifespanMinSeconds());
        int lifespanMax = SpeciesDefinition.secondsToTicks(species.lifespanMaxSeconds());
        Age age = ecs.add(entity, new Age());
        age.maxAgeTicks = lifespanMin + (lifespanMax > lifespanMin ? random.nextInt(lifespanMax - lifespanMin + 1) : 0);
        age.ageTicks = Math.min(ageTicks, age.maxAgeTicks - 1);

        Reproduction reproduction = ecs.add(entity, new Reproduction());
        reproduction.readyAtTick = readyAtTick;
        ecs.add(entity, new AiState());
        ecs.add(entity, new Memory());
        creatureGrid.insert(entity, x, z);
        return entity;
    }

    /** Random genome for the starting population (each gene uniform within the species variation). */
    public Genome randomGenome(SpeciesDefinition species) {
        float variation = species.genome().variation();
        Genome genome = new Genome();
        genome.size = 1f + variation * (2f * random.nextFloat() - 1f);
        genome.speed = 1f + variation * (2f * random.nextFloat() - 1f);
        genome.tint = 1f + variation * (2f * random.nextFloat() - 1f);
        return genome;
    }

    /** Offspring genome: average of the parents plus a small Gaussian mutation, kept within the variation. */
    public Genome inherit(SpeciesDefinition species, Genome a, Genome b) {
        SpeciesDefinition.GenomeTuning tuning = species.genome();
        Genome child = new Genome();
        child.size = mutate((a.size + b.size) * 0.5f, tuning);
        child.speed = mutate((a.speed + b.speed) * 0.5f, tuning);
        child.tint = mutate((a.tint + b.tint) * 0.5f, tuning);
        child.generation = Math.max(a.generation, b.generation) + 1;
        return child;
    }

    private float mutate(float value, SpeciesDefinition.GenomeTuning tuning) {
        float mutated = value + (float) random.nextGaussian() * tuning.mutation();
        return Math.clamp(mutated, 1f - tuning.variation(), 1f + tuning.variation());
    }

    public Random random() {
        return random;
    }
}

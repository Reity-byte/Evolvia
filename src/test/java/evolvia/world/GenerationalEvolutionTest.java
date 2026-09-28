package evolvia.world;

import evolvia.components.Genome;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generational evolution: unlocking a node does not change living creatures; newborns carry the new
 * traits, at most one stage ahead of their more evolved parent, so the population changes gradually.
 */
class GenerationalEvolutionTest {

    private static WorldConfig config;
    private static BiomeTable biomes;
    private static SpeciesDefinition species;
    private static ResourceTable resources;
    private static EvolutionTree tree;

    @BeforeAll
    static void loadData() {
        config = DataLoader.loadWorldConfig();
        biomes = DataLoader.loadBiomes();
        species = DataLoader.loadSpecies();
        resources = DataLoader.loadResources();
        tree = DataLoader.loadEvolutionTree(biomes);
    }

    private static World create(long seed) {
        return World.create(config, biomes, species, tree, resources, seed);
    }

    /** Makes two creatures have a child now (in a tick that is not a herd update) and returns it. */
    private static int birth(World world, int parentA, int parentB, int tick) {
        java.util.Set<Integer> before = new java.util.HashSet<>();
        ComponentStore<SpeciesRef> creatures = world.ecs().store(SpeciesRef.class);
        for (int i = 0; i < creatures.size(); i++) {
            before.add(creatures.entityAt(i));
        }
        Transform t = world.ecs().get(parentA, Transform.class);
        world.births().add(parentA, parentB, t.position.x, t.position.z);
        world.tick(tick);
        for (int i = 0; i < creatures.size(); i++) {
            int e = creatures.entityAt(i);
            if (!before.contains(e) && world.ecs().get(e, Genome.class).generation > 0) {
                return e;
            }
        }
        throw new AssertionError("no child was born");
    }

    @Test
    void unlockingDoesNotChangeLivingCreatures() {
        World world = create(3);
        world.species().addPoints(100f);
        world.unlock("body_strong_legs");
        ComponentStore<SpeciesRef> creatures = world.ecs().store(SpeciesRef.class);
        for (int i = 0; i < creatures.size(); i++) {
            if (creatures.componentAt(i).species != world.species()) {
                continue; // wild game
            }
            assertEquals(0, creatures.componentAt(i).stage);
            assertEquals(species.speed(), creatures.componentAt(i).stats().speed(), 1e-6f, "living creature changed");
        }
        assertEquals(species.speed() * 1.2f, world.species().stats().speed(), 1e-4f, "the species knows the trait");
    }

    @Test
    void newbornsCarryTheNewTraitsOneStepPerGeneration() {
        World world = create(5);
        world.species().addPoints(500f);
        world.unlock("body_strong_legs");
        world.unlock("body_keen_eyes");
        world.unlock("body_upright");
        ComponentStore<SpeciesRef> creatures = world.ecs().store(SpeciesRef.class);
        int a = creatures.entityAt(0);
        int b = creatures.entityAt(1);

        int child = birth(world, a, b, 1);
        SpeciesRef childRef = world.ecs().get(child, SpeciesRef.class);
        assertEquals(1, childRef.stage, "one step ahead of the parents");
        assertEquals(species.speed() * 1.2f, childRef.stats().speed(), 1e-4f, "the child has strong legs");
        assertEquals(species.senseRadius(), childRef.stats().senseRadius(), 1e-4f, "but not the keen eyes yet");

        int grandchild = birth(world, child, a, 2);
        assertEquals(2, world.ecs().get(grandchild, SpeciesRef.class).stage, "one step ahead of the more evolved parent");
        int next = birth(world, grandchild, grandchild, 3);
        assertEquals(3, world.ecs().get(next, SpeciesRef.class).stage);
        int capped = birth(world, next, next, 4);
        assertEquals(3, world.ecs().get(capped, SpeciesRef.class).stage, "never beyond the unlocked traits");
        assertEquals("semi", world.ecs().get(capped, SpeciesRef.class).stageData().visuals().get("posture"));
    }

    @Test
    void newTraitsSpreadThroughThePopulationOverTime() {
        World world = create(7);
        world.species().addPoints(100f);
        world.unlock("body_strong_legs");
        int tick = 0;
        float[] share = new float[3];
        for (int sample = 0; sample < 3; sample++) {
            for (int i = 0; i < 6 * 60 * Time.TICKS_PER_SECOND; i++) {
                world.tick(tick++);
            }
            int[] stages = world.stageCounts();
            share[sample] = stages[1] / (float) world.creatureCount();
        }
        assertTrue(share[0] > 0f, "first newborns carry the trait");
        assertTrue(share[0] < 0.9f, "not everybody at once: " + share[0]);
        assertTrue(share[2] > share[0] + 0.1f, "the trait spreads: " + share[0] + " -> " + share[2]);
    }

    @Test
    void evolveEveryoneIsImmediate() {
        World world = create(9);
        world.species().addPoints(100f);
        world.unlock("body_strong_legs");
        world.evolveEveryone();
        int[] stages = world.stageCounts();
        assertEquals(0, stages[0]);
        assertEquals(world.creatureCount(), stages[1]);
    }
}

package evolvia.world;

import evolvia.ai.Navigation;
import evolvia.ai.Pathfinder;
import evolvia.components.AiState;
import evolvia.components.Memory;
import evolvia.components.Needs;
import evolvia.components.PrevTransform;
import evolvia.components.ResourceNode;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.systems.NeedsSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 DoD: unlocking a node changes the population's behaviour measurably
 * (speed, what they eat, where they survive), plus EP income, swimming and memory.
 */
class EvolutionBehaviourTest {

    private static WorldConfig config;
    private static BiomeTable biomes;
    private static SpeciesDefinition species;
    private static ResourceTable resources;
    private static EvolutionTree tree;

    @BeforeAll
    static void loadData() {
        config = DataLoader.loadWorldConfig();
        biomes = DataLoader.loadBiomes();
        // Spread out and capped: behaviour of individuals, no population growth.
        species = DataLoader.loadSpecies().withPopulation(new SpeciesDefinition.Population(1000, 0f, 1000));
        resources = DataLoader.loadResources();
        tree = DataLoader.loadEvolutionTree(biomes);
    }

    private static World create(long seed) {
        return World.create(config, biomes, species, tree, resources, seed);
    }

    private static int run(World world, int from, int ticks) {
        for (int tick = from; tick < from + ticks; tick++) {
            world.tick(tick);
        }
        return from + ticks;
    }

    private static void unlock(World world, String... nodes) {
        world.species().addPoints(1000f);
        for (String node : nodes) {
            world.unlock(node);
        }
        world.evolveEveryone(); // these tests look at fully evolved creatures, not at the generations in between
    }

    /** Average distance moved per tick by creatures that moved in that tick. */
    private static float averageStep(World world, int from, int ticks) {
        ComponentStore<PrevTransform> previous = world.ecs().store(PrevTransform.class);
        double sum = 0;
        long moves = 0;
        for (int tick = from; tick < from + ticks; tick++) {
            world.tick(tick);
            for (int i = 0; i < previous.size(); i++) {
                Transform t = world.ecs().get(previous.entityAt(i), Transform.class);
                PrevTransform p = previous.componentAt(i);
                double d = Math.hypot(t.position.x - p.position.x, t.position.z - p.position.z);
                if (d > 1e-5) {
                    sum += d;
                    moves++;
                }
            }
        }
        return (float) (sum / moves);
    }

    /** A creature (with no food in reach) prepared to be hungry but otherwise fine. */
    private static int hungryCreature(World world) {
        int creature = world.ecs().store(SpeciesRef.class).entityAt(0);
        Needs needs = world.ecs().get(creature, Needs.class);
        needs.hunger = 0.8f;
        needs.thirst = 0f;
        needs.energy = 1f;
        return creature;
    }

    @Test
    void strongLegsMakeThePopulationFaster() {
        World world = create(3);
        int tick = run(world, 0, 400);
        float before = averageStep(world, tick, 400);
        unlock(world, "body_strong_legs");
        float after = averageStep(world, tick + 400, 400);
        assertEquals(1.2f, after / before, 0.06f, "speed ratio " + after / before);
    }

    @Test
    void carnivoresEatCarcassesAndIgnorePlants() {
        World world = create(5);
        unlock(world, "diet_carnivore");
        int creature = hungryCreature(world);
        Transform t = world.ecs().get(creature, Transform.class);
        int bush = world.placeResource("berry_bush", t.position.x, t.position.z);
        int carcass = world.placeResource("carcass", t.position.x + 0.3f, t.position.z);
        float bushBefore = world.ecs().get(bush, ResourceNode.class).amount;
        float carcassBefore = world.ecs().get(carcass, ResourceNode.class).amount;
        run(world, 0, 10 * Time.TICKS_PER_SECOND);
        assertEquals(bushBefore, world.ecs().get(bush, ResourceNode.class).amount, 1e-3f, "carnivore ate a plant");
        ResourceNode eaten = world.ecs().get(carcass, ResourceNode.class);
        assertTrue(eaten == null || eaten.amount < carcassBefore - 0.5f, "carnivore did not eat the carcass");
        assertTrue(world.ecs().get(creature, Needs.class).hunger < 0.5f);
    }

    @Test
    void herbivoresDoNotEatCarcasses() {
        World world = create(5);
        int creature = hungryCreature(world);
        Transform t = world.ecs().get(creature, Transform.class);
        int carcass = world.placeResource("carcass", t.position.x, t.position.z);
        float before = world.ecs().get(carcass, ResourceNode.class).amount;
        run(world, 0, 10 * Time.TICKS_PER_SECOND);
        float decayed = SpeciesDefinition.perTick(resources.byId("carcass").decayPerSecond()) * 10 * Time.TICKS_PER_SECOND;
        assertEquals(before - decayed, world.ecs().get(carcass, ResourceNode.class).amount, 0.01f, "herbivore ate meat");
    }

    @Test
    void omnivoresEatBoth() {
        World world = create(5);
        unlock(world, "diet_omnivore");
        int creature = hungryCreature(world);
        Transform t = world.ecs().get(creature, Transform.class);
        int carcass = world.placeResource("carcass", t.position.x, t.position.z);
        float before = world.ecs().get(carcass, ResourceNode.class).amount;
        run(world, 0, 10 * Time.TICKS_PER_SECOND);
        assertTrue(world.ecs().get(carcass, ResourceNode.class).amount < before - 0.5f, "omnivore did not eat the carcass");
    }

    @Test
    void furLetsCreaturesLiveInTheCold() {
        // A tile at temperature 0.1: below the base comfort range (0.25), so hunger grows faster.
        SpeciesDefinition base = DataLoader.loadSpecies();
        Terrain cold = TestTerrains.fromAscii(0.1f, "....");
        float plainRate = hungerAfterOneMinute(new Species(base, new EvolutionTree(java.util.List.of(), "none")), TestTerrains.fromAscii(0.5f, "...."));
        float coldRate = hungerAfterOneMinute(new Species(base, new EvolutionTree(java.util.List.of(), "none")), cold);
        Species furry = new Species(base, tree);
        furry.addPoints(100f);
        furry.unlock("adapt_fur", new evolvia.evolution.EvolutionConditions() {
            public int population() {
                return 1;
            }

            public float biomeRatio(String biomeId) {
                return 0f;
            }
        });
        float furRate = hungerAfterOneMinute(furry, cold);
        assertTrue(coldRate > plainRate * 1.4f, "cold should make creatures much hungrier: " + coldRate + " vs " + plainRate);
        assertTrue(furRate < coldRate * 0.8f, "fur should help in the cold: " + furRate + " vs " + coldRate);
    }

    private static float hungerAfterOneMinute(Species kind, Terrain terrain) {
        EcsWorld ecs = new EcsWorld();
        int e = ecs.createEntity();
        ecs.add(e, new SpeciesRef(kind));
        Transform t = ecs.add(e, new Transform());
        t.position.set(1.5f, 1f, 0.5f);
        Needs needs = ecs.add(e, new Needs());
        NeedsSystem system = new NeedsSystem(terrain);
        for (int tick = 0; tick < 60 * Time.TICKS_PER_SECOND; tick++) {
            system.update(ecs, tick);
        }
        return needs.hunger;
    }

    @Test
    void swimmersCrossShallowWater() {
        // Two islands separated by shallow water (depth 0.5 below sea level, shallow limit 2.5).
        Terrain terrain = TestTerrains.fromAscii(
                "..~~~~..",
                "..~~~~..");
        Navigation navigation = new Navigation(terrain, 2.5f);
        Pathfinder land = navigation.land();
        Pathfinder swim = navigation.swim();
        assertNotEquals(land.region(0, 0), land.region(7, 0));
        assertEquals(swim.region(0, 0), swim.region(7, 0));
        assertEquals(null, land.find(0.5f, 0.5f, 7.5f, 0.5f));
        assertNotNull(swim.find(0.5f, 0.5f, 7.5f, 0.5f));
        assertFalse(new Navigation(terrain, 0.2f).swim().isWalkable(4, 0), "mid-channel tile is too deep for a 0.2 shallow limit");
    }

    @Test
    void speciesEarnsEvolutionPoints() {
        World world = create(9);
        run(world, 0, 60 * Time.TICKS_PER_SECOND);
        float expectedFromPopulation = species.evolution().populationPointsPerMinute() * (float) Math.log1p(1000);
        assertTrue(world.species().pointsEarned() >= expectedFromPopulation * 0.9f,
                "earned " + world.species().pointsEarned() + ", expected at least ~" + expectedFromPopulation);
        assertTrue(world.evolutionSystem().pointsPerMinute() > 0);
    }

    @Test
    void creaturesWithMemoryRememberWhereTheyDrank() {
        World withMemory = create(42);
        unlock(withMemory, "mind_instincts", "mind_memory");
        World without = create(42);
        for (World world : new World[]{withMemory, without}) {
            ComponentStore<Needs> needs = world.ecs().store(Needs.class);
            for (int i = 0; i < needs.size(); i++) {
                needs.componentAt(i).thirst = 0.9f;
            }
            run(world, 0, 60 * Time.TICKS_PER_SECOND);
        }
        assertTrue(countKnowingWater(withMemory) > 100, "creatures with memory should remember water");
        assertEquals(0, countKnowingWater(without), "without the memory ability nothing is remembered");
    }

    private static int countKnowingWater(World world) {
        ComponentStore<Memory> memories = world.ecs().store(Memory.class);
        int count = 0;
        for (int i = 0; i < memories.size(); i++) {
            if (memories.componentAt(i).knowsWater) {
                count++;
            }
        }
        return count;
    }

    @Test
    void creaturesStillUseEveryActionAfterEvolving() {
        World world = create(3);
        unlock(world, "body_strong_legs", "body_keen_eyes", "diet_omnivore", "adapt_swim");
        run(world, 0, 2 * 60 * Time.TICKS_PER_SECOND);
        ComponentStore<AiState> ai = world.ecs().store(AiState.class);
        int acting = 0;
        for (int i = 0; i < ai.size(); i++) {
            if (ai.componentAt(i).action != null) {
                acting++;
            }
        }
        assertTrue(acting > ai.size() * 0.9, "most creatures should be doing something");
    }
}

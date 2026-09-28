package evolvia.world;

import evolvia.ai.ActionType;
import evolvia.ai.Navigation;
import evolvia.components.AiState;
import evolvia.components.Needs;
import evolvia.components.PrevTransform;
import evolvia.components.ResourceNode;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.SpeciesDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSimulationTest {

    private static WorldConfig config;
    private static BiomeTable biomes;
    private static SpeciesDefinition species;
    private static ResourceTable resources;

    @BeforeAll
    static void loadData() {
        config = DataLoader.loadWorldConfig();
        biomes = DataLoader.loadBiomes();
        // These tests look at individual behaviour: many creatures spread over the whole map, capped at
        // the starting count so reproduction does not grow the population (and the test time).
        species = DataLoader.loadSpecies().withPopulation(new SpeciesDefinition.Population(1000, 0f, 1000));
        resources = DataLoader.loadResources();
    }

    private static World create(long seed) {
        return World.create(config, biomes, species, resources, seed);
    }

    /** Runs {@code ticks} ticks starting at {@code from}; returns the next tick index. */
    private static int run(World world, int from, int ticks) {
        for (int tick = from; tick < from + ticks; tick++) {
            world.tick(tick);
        }
        return from + ticks;
    }

    private static void assertAllCreaturesOnLandAtTerrainHeight(World world) {
        Terrain terrain = world.terrain();
        ComponentStore<SpeciesRef> creatures = world.ecs().store(SpeciesRef.class);
        for (int i = 0; i < creatures.size(); i++) {
            Transform t = world.ecs().get(creatures.entityAt(i), Transform.class);
            int tx = (int) Math.floor(t.position.x);
            int tz = (int) Math.floor(t.position.z);
            assertTrue(terrain.isPassable(tx, tz), "creature on impassable tile " + tx + "," + tz);
            assertEquals(Navigation.groundHeight(terrain, t.position.x, t.position.z), t.position.y, 1e-4f, "creature not on the ground");
        }
    }

    /** A creature that has a node of {@code kind} within its sense radius, or -1. */
    private static int creatureNear(World world, ResourceKind kind) {
        ComponentStore<SpeciesRef> creatures = world.ecs().store(SpeciesRef.class);
        ComponentStore<ResourceNode> nodes = world.ecs().store(ResourceNode.class);
        for (int i = 0; i < creatures.size(); i++) {
            Transform t = world.ecs().get(creatures.entityAt(i), Transform.class);
            int node = world.resourceGrid(kind).nearest(t.position.x, t.position.z, species.senseRadius() * 0.5f,
                    n -> nodes.get(n).type.kind() == kind && (kind == ResourceKind.WATER || nodes.get(n).amount >= 1f));
            if (node >= 0) {
                return creatures.entityAt(i);
            }
        }
        return -1;
    }

    @Test
    void spawnsPopulationAndResourcesOnLand() {
        World world = create(42);
        assertEquals(species.population().starting(), world.creatureCount());
        assertAllCreaturesOnLandAtTerrainHeight(world);

        ComponentStore<ResourceNode> nodes = world.ecs().store(ResourceNode.class);
        int food = 0;
        int water = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Transform t = world.ecs().get(nodes.entityAt(i), Transform.class);
            assertTrue(world.terrain().isPassable((int) Math.floor(t.position.x), (int) Math.floor(t.position.z)),
                    "resource on impassable tile");
            if (nodes.componentAt(i).type.kind() == ResourceKind.FOOD) {
                food++;
            } else {
                water++;
            }
        }
        assertTrue(food > 100, "too few food nodes: " + food);
        assertTrue(water > 100, "too few water nodes: " + water);
        assertEquals(nodes.size(), world.resourceNodeCount());
    }

    @Test
    void creaturesStayOnLandAndOnTheGround() {
        World world = create(7);
        int tick = 0;
        for (int round = 0; round < 10; round++) {
            tick = run(world, tick, 200);
            assertAllCreaturesOnLandAtTerrainHeight(world);
        }
    }

    @Test
    void creaturesUseEveryNeedAction() {
        World world = create(3);
        Set<ActionType> seen = EnumSet.noneOf(ActionType.class);
        ComponentStore<AiState> ai = world.ecs().store(AiState.class);
        int tick = 0;
        for (int second = 0; second < 180; second++) {
            tick = run(world, tick, 20);
            for (int i = 0; i < ai.size(); i++) {
                if (ai.componentAt(i).action != null) {
                    seen.add(ai.componentAt(i).action);
                }
            }
        }
        // SeekMate is covered by ReproductionTest (population is capped here, so nobody may reproduce),
        // Flee by GodPowersTest (only lightning scares creatures), FollowLeader by GroupsTest (no herds here).
        assertTrue(seen.containsAll(EnumSet.of(ActionType.WANDER, ActionType.SEEK_FOOD, ActionType.EAT, ActionType.SEEK_WATER,
                ActionType.DRINK, ActionType.SLEEP)), "used: " + seen);
    }

    @Test
    void thirstyCreatureFindsWaterAndDrinks() {
        World world = create(42);
        int creature = creatureNear(world, ResourceKind.WATER);
        assertTrue(creature >= 0);
        Needs needs = world.ecs().get(creature, Needs.class);
        needs.thirst = 0.8f;
        needs.hunger = 0f;
        needs.energy = 1f;
        assertTrue(lowestWithinMinute(world, () -> needs.thirst) < 0.1f, "never drank");
    }

    /** Runs one minute of game time and returns the lowest value the need reached (it grows again after). */
    private static float lowestWithinMinute(World world, java.util.function.Supplier<Float> need) {
        float lowest = need.get();
        for (int tick = 0; tick < 60 * 20; tick++) {
            world.tick(tick);
            lowest = Math.min(lowest, need.get());
        }
        return lowest;
    }

    @Test
    void hungryCreatureFindsFoodAndEats() {
        World world = create(42);
        int creature = creatureNear(world, ResourceKind.FOOD);
        assertTrue(creature >= 0);
        Needs needs = world.ecs().get(creature, Needs.class);
        needs.hunger = 0.8f;
        needs.thirst = 0f;
        needs.energy = 1f;
        assertTrue(lowestWithinMinute(world, () -> needs.hunger) < 0.1f, "never ate");
    }

    @Test
    void populationStarvesWhenFoodRunsOut() {
        World world = create(1234);
        world.emptyAllFood();
        run(world, 0, 5 * 60 * 20); // five minutes: starting hunger 0-0.3 reaches 1 after ~3 minutes
        assertTrue(world.deaths().count(DeathStats.Cause.STARVATION) > 20,
                "starvation deaths: " + world.deaths().count(DeathStats.Cause.STARVATION));
    }

    @Test
    void sameSeedGivesSameSimulation() {
        World a = create(1234);
        World b = create(1234);
        run(a, 0, 1000);
        run(b, 0, 1000);
        ComponentStore<Transform> ta = a.ecs().store(Transform.class);
        ComponentStore<Transform> tb = b.ecs().store(Transform.class);
        assertEquals(ta.size(), tb.size());
        for (int i = 0; i < ta.size(); i++) {
            assertEquals(ta.entityAt(i), tb.entityAt(i));
            assertEquals(ta.componentAt(i).position, tb.componentAt(i).position);
            assertEquals(ta.componentAt(i).yaw, tb.componentAt(i).yaw);
        }
        assertEquals(a.totalFood(), b.totalFood());
    }

    @Test
    void previousTransformLagsOneTickBehind() {
        World world = create(11);
        run(world, 0, 100);
        ComponentStore<PrevTransform> previous = world.ecs().store(PrevTransform.class);
        for (int i = 0; i < previous.size(); i++) {
            SpeciesDefinition own = world.ecs().get(previous.entityAt(i), SpeciesRef.class).stats();
            float maxStep = own.speedPerTick() * (1f + own.genome().variation()) + 1e-4f; // its species, genome speed
            PrevTransform prev = previous.componentAt(i);
            Transform current = world.ecs().get(previous.entityAt(i), Transform.class);
            float dx = current.position.x - prev.position.x;
            float dz = current.position.z - prev.position.z;
            assertTrue(Math.sqrt(dx * dx + dz * dz) <= maxStep, "moved more than one step per tick");
        }
    }
}

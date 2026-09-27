package evolvia.world;

import evolvia.components.PrevTransform;
import evolvia.components.Transform;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.SpeciesDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSimulationTest {

    private static WorldConfig config;
    private static BiomeTable biomes;
    private static SpeciesDefinition species;

    @BeforeAll
    static void loadData() {
        config = DataLoader.loadWorldConfig();
        biomes = DataLoader.loadBiomes();
        species = DataLoader.loadSpecies();
    }

    private static void run(World world, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            world.tick(tick);
        }
    }

    private static void assertAllOnLandAtTerrainHeight(World world) {
        Terrain terrain = world.terrain();
        ComponentStore<Transform> transforms = world.ecs().store(Transform.class);
        for (int i = 0; i < transforms.size(); i++) {
            Transform t = transforms.componentAt(i);
            int tx = (int) Math.floor(t.position.x);
            int tz = (int) Math.floor(t.position.z);
            assertTrue(terrain.isPassable(tx, tz), "entity on impassable tile " + tx + "," + tz);
            assertEquals(terrain.heightAt(t.position.x, t.position.z), t.position.y, 1e-4f, "entity not on the ground");
        }
    }

    @Test
    void spawnsStartingPopulationOnLand() {
        World world = World.create(config, biomes, species, 42);
        assertEquals(species.startingPopulation(), world.ecs().entityCount());
        assertEquals(species.startingPopulation(), world.ecs().store(Transform.class).size());
        assertAllOnLandAtTerrainHeight(world);
    }

    @Test
    void creaturesStayOnLandAndOnTheGround() {
        World world = World.create(config, biomes, species, 7);
        for (int round = 0; round < 10; round++) {
            run(world, 200);
            assertAllOnLandAtTerrainHeight(world);
        }
    }

    @Test
    void creaturesActuallyMove() {
        World world = World.create(config, biomes, species, 3);
        ComponentStore<Transform> transforms = world.ecs().store(Transform.class);
        float[] startX = new float[transforms.size()];
        float[] startZ = new float[transforms.size()];
        for (int i = 0; i < transforms.size(); i++) {
            startX[i] = transforms.componentAt(i).position.x;
            startZ[i] = transforms.componentAt(i).position.z;
        }
        run(world, 60 * 20); // one minute of game time
        int moved = 0;
        for (int i = 0; i < transforms.size(); i++) {
            float dx = transforms.componentAt(i).position.x - startX[i];
            float dz = transforms.componentAt(i).position.z - startZ[i];
            if (dx * dx + dz * dz > 1f) {
                moved++;
            }
        }
        assertTrue(moved > transforms.size() * 0.9, "only " + moved + " creatures moved more than one tile");
    }

    @Test
    void sameSeedGivesSameSimulation() {
        World a = World.create(config, biomes, species, 1234);
        World b = World.create(config, biomes, species, 1234);
        run(a, 500);
        run(b, 500);
        ComponentStore<Transform> ta = a.ecs().store(Transform.class);
        ComponentStore<Transform> tb = b.ecs().store(Transform.class);
        assertEquals(ta.size(), tb.size());
        for (int i = 0; i < ta.size(); i++) {
            assertEquals(ta.entityAt(i), tb.entityAt(i));
            assertEquals(ta.componentAt(i).position, tb.componentAt(i).position);
            assertEquals(ta.componentAt(i).yaw, tb.componentAt(i).yaw);
        }
    }

    @Test
    void previousTransformLagsOneTickBehind() {
        World world = World.create(config, biomes, species, 11);
        run(world, 100);
        ComponentStore<Transform> transforms = world.ecs().store(Transform.class);
        ComponentStore<PrevTransform> previous = world.ecs().store(PrevTransform.class);
        float maxStep = species.speedPerTick() + 1e-4f;
        for (int i = 0; i < transforms.size(); i++) {
            Transform current = transforms.componentAt(i);
            PrevTransform prev = previous.get(transforms.entityAt(i));
            float dx = current.position.x - prev.position.x;
            float dz = current.position.z - prev.position.z;
            assertTrue(Math.sqrt(dx * dx + dz * dz) <= maxStep, "moved more than one step per tick");
        }
    }
}

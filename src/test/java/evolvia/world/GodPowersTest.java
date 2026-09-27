package evolvia.world;

import evolvia.ai.ActionType;
import evolvia.components.AiState;
import evolvia.components.Believer;
import evolvia.components.Fear;
import evolvia.components.Needs;
import evolvia.components.ResourceNode;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.DivinePower;
import evolvia.god.Faith;
import evolvia.god.GodConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 7 DoD: god powers visibly change the world and the population; without faith they are blocked. */
class GodPowersTest {

    private static WorldConfig config;
    private static BiomeTable biomes;
    private static SpeciesDefinition species;
    private static ResourceTable resources;
    private static GodConfig god;

    @BeforeAll
    static void loadData() {
        config = DataLoader.loadWorldConfig();
        biomes = DataLoader.loadBiomes();
        species = DataLoader.loadSpecies();
        resources = DataLoader.loadResources();
        god = DataLoader.loadGodConfig();
    }

    private static World create(long seed) {
        return World.create(config, biomes, species, new evolvia.evolution.EvolutionTree(List.of(), "none"), resources, god, seed);
    }

    private static int run(World world, int from, int ticks) {
        for (int tick = from; tick < from + ticks; tick++) {
            world.tick(tick);
        }
        return from + ticks;
    }

    private static int firstCreature(World world) {
        return world.ecs().store(SpeciesRef.class).entityAt(0);
    }

    private static Transform position(World world, int entity) {
        return world.ecs().get(entity, Transform.class);
    }

    @Test
    void powersNeedFaith() {
        World world = create(3);
        Faith faith = world.godPowers().faith();
        float cost = god.lightning().cost();
        int queued = 0;
        while (world.godPowers().request(DivinePower.LIGHTNING, 10f, 10f)) {
            queued++;
        }
        assertEquals((int) (god.faith().starting() / cost), queued, "queued powers are limited by faith");
        assertFalse(world.godPowers().canAfford(DivinePower.LIGHTNING));
        world.tick(0);
        assertEquals(god.faith().starting() - queued * cost, faith.points(), 0.5f);
        assertFalse(world.godPowers().canAfford(DivinePower.ABUNDANCE), "not enough faith left");
    }

    @Test
    void faithGrowsWithBelievers() {
        World world = create(3);
        run(world, 1, Time.TICKS_PER_SECOND);
        assertEquals(god.faith().basePerMinute(), world.godPowers().faith().perMinute(), 1e-3f, "no believers yet");

        ComponentStore<SpeciesRef> creatures = world.ecs().store(SpeciesRef.class);
        for (int i = 0; i < 100; i++) {
            world.ecs().add(creatures.entityAt(i), new Believer());
        }
        float before = world.godPowers().faith().points();
        run(world, Time.TICKS_PER_SECOND + 1, 60 * Time.TICKS_PER_SECOND);
        // Children of believers believe too, so count the believers now.
        float expected = god.faith().basePerMinute() + world.believers() * god.faith().perBelieverPerMinute();
        assertTrue(world.believers() >= 100);
        assertEquals(expected, world.godPowers().faith().perMinute(), 1e-3f);
        float minimum = god.faith().basePerMinute() + 100 * god.faith().perBelieverPerMinute();
        assertTrue(world.godPowers().faith().points() - before >= minimum * 0.95f);
    }

    @Test
    void abundanceFeedsCreaturesWhoThenBelieve() {
        World world = create(5);
        int creature = firstCreature(world);
        Transform t = position(world, creature);
        world.emptyAllFood();
        ComponentStore<Needs> needs = world.ecs().store(Needs.class);
        for (int i = 0; i < needs.size(); i++) {
            needs.componentAt(i).hunger = 0.8f;
        }
        int foodBefore = world.resourceGrid(ResourceKind.FOOD).size();
        assertTrue(world.godPowers().request(DivinePower.ABUNDANCE, t.position.x, t.position.z));
        world.tick(0);
        assertTrue(world.resourceGrid(ResourceKind.FOOD).size() > foodBefore, "new bushes");
        assertTrue(countDivine(world) >= god.abundance().newNodes());
        assertEquals(0, world.believers());

        run(world, 1, 30 * Time.TICKS_PER_SECOND);
        assertTrue(world.believers() >= 3, "hungry creatures ate divine food and believe: " + world.believers());
        assertTrue(world.godPowers().faith().alignment() > 0f, "a kind act");
        assertEquals(1, world.godPowers().faith().kindActs());
    }

    private static int countDivine(World world) {
        ComponentStore<ResourceNode> nodes = world.ecs().store(ResourceNode.class);
        int divine = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.componentAt(i).divine) {
                divine++;
            }
        }
        return divine;
    }

    @Test
    void lightningKillsScaresAndConvertsOutOfFear() {
        World world = create(7);
        int victim = firstCreature(world);
        Transform t = position(world, victim);
        float x = t.position.x;
        float z = t.position.z;
        List<Integer> witnesses = new ArrayList<>();
        world.creatureGrid().forEachWithin(x, z, god.lightning().scareRadius() * 0.8f, witnesses::add);
        int carcassesBefore = countCarcasses(world);
        int populationBefore = world.population();

        assertTrue(world.godPowers().request(DivinePower.LIGHTNING, x, z));
        world.tick(0);
        int killed = world.deaths().count(DeathStats.Cause.LIGHTNING);
        assertTrue(killed >= 1 && killed <= god.lightning().maxKills(), "killed " + killed);
        assertEquals(carcassesBefore + killed, countCarcasses(world), "the dead leave carcasses");
        assertEquals(populationBefore - killed, world.population());
        assertTrue(world.godPowers().faith().alignment() < 0f, "a cruel act");
        assertEquals(1, world.godPowers().faith().cruelActs());

        List<Integer> survivors = witnesses.stream().filter(e -> world.ecs().get(e, SpeciesRef.class) != null).toList();
        assertFalse(survivors.isEmpty());
        float before = averageDistance(world, survivors, x, z);
        for (int e : survivors) {
            assertNotNull(world.ecs().get(e, Fear.class), "witness is scared");
            assertNotNull(world.ecs().get(e, Believer.class), "witness believes out of fear");
        }
        world.tick(1);
        assertTrue(survivors.stream().allMatch(e -> world.ecs().get(e, AiState.class).action == ActionType.FLEE),
                "every witness drops what it does and flees");
        run(world, 2, 4 * Time.TICKS_PER_SECOND);
        float after = averageDistance(world, survivors, x, z);
        assertTrue(after > before + 2f, "witnesses ran away: " + before + " -> " + after);
    }

    private static int countCarcasses(World world) {
        ComponentStore<ResourceNode> nodes = world.ecs().store(ResourceNode.class);
        int count = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.componentAt(i).type.decays()) {
                count++;
            }
        }
        return count;
    }

    private static float averageDistance(World world, List<Integer> creatures, float x, float z) {
        double sum = 0;
        int n = 0;
        for (int e : creatures) {
            Transform t = world.ecs().get(e, Transform.class);
            if (t != null && world.ecs().get(e, SpeciesRef.class) != null) {
                sum += Math.hypot(t.position.x - x, t.position.z - z);
                n++;
            }
        }
        return (float) (sum / n);
    }

    @Test
    void rainMakesFoodRegrowFasterForAWhile() {
        World world = create(9);
        world.emptyAllFood();
        int creature = firstCreature(world);
        Transform t = position(world, creature);
        assertTrue(world.godPowers().request(DivinePower.RAIN, t.position.x, t.position.z));
        world.tick(0);
        assertEquals(1, world.godPowers().rains().size());
        assertEquals(1, world.godPowers().recentStrikes().size());

        run(world, 1, 20 * Time.TICKS_PER_SECOND);
        ComponentStore<ResourceNode> nodes = world.ecs().store(ResourceNode.class);
        float inRain = 0;
        float expectedWithoutRain = 0;
        for (int i = 0; i < nodes.size(); i++) {
            ResourceNode node = nodes.componentAt(i);
            Transform n = world.ecs().get(nodes.entityAt(i), Transform.class);
            if (node.type.kind() == ResourceKind.FOOD && !node.type.decays()
                    && Math.hypot(n.position.x - t.position.x, n.position.z - t.position.z) < god.rain().radius() - 1f) {
                inRain += node.amount;
                expectedWithoutRain += node.regrowPerTick * 20 * Time.TICKS_PER_SECOND;
            }
        }
        assertTrue(inRain > expectedWithoutRain * 2f, "rain boosts regrowth: " + inRain + " vs " + expectedWithoutRain);
        assertTrue(countDivine(world) > 0);

        run(world, 20 * Time.TICKS_PER_SECOND + 1, SpeciesDefinition.secondsToTicks(god.rain().durationSeconds()));
        assertTrue(world.godPowers().rains().isEmpty(), "the rain stops");
    }

    @Test
    void loweringTheGroundMakesALakeAndRaisingItMakesLand() {
        World world = create(11);
        Terrain terrain = world.terrain();
        int[] tile = landTileFarFromWater(terrain);
        float x = tile[0] + 0.5f;
        float z = tile[1] + 0.5f;
        int revision = terrain.blockRevision(tile[0], tile[1]);

        int tick = 0;
        for (int i = 0; i < 60 && terrain.isPassable(tile[0], tile[1]); i++) {
            world.godPowers().faith().add(100f);
            world.godPowers().request(DivinePower.LOWER, x, z);
            world.tick(tick++);
        }
        assertTrue(terrain.biome(tile[0], tile[1]).water(), "a lake");
        assertTrue(terrain.blockRevision(tile[0], tile[1]) > revision, "renderers see the change");
        assertEquals(-1, world.navigation().land().region(tile[0], tile[1]));
        assertNoFoodOrCreaturesInWater(world);
        assertTrue(world.resourceGrid(ResourceKind.WATER).nearest(x, z, 8f, e -> true) >= 0, "the new shore has drinking water");
        run(world, tick, 5 * Time.TICKS_PER_SECOND);
        tick += 5 * Time.TICKS_PER_SECOND;
        assertNoFoodOrCreaturesInWater(world);

        for (int i = 0; i < 60 && !terrain.isPassable(tile[0], tile[1]); i++) {
            world.godPowers().faith().add(100f);
            world.godPowers().request(DivinePower.RAISE, x, z);
            world.tick(tick++);
        }
        assertTrue(terrain.isPassable(tile[0], tile[1]), "land again");
        assertTrue(world.navigation().land().region(tile[0], tile[1]) >= 0);
        int waterNearCentre = world.resourceGrid(ResourceKind.WATER).nearest(x, z, 1f, e -> true);
        assertEquals(-1, waterNearCentre, "no drinking water left in the middle of the new land");
    }

    private static int[] landTileFarFromWater(Terrain terrain) {
        for (int tz = 20; tz < terrain.depth() - 20; tz++) {
            for (int tx = 20; tx < terrain.width() - 20; tx++) {
                boolean dry = true;
                for (int dz = -8; dz <= 8 && dry; dz++) {
                    for (int dx = -8; dx <= 8 && dry; dx++) {
                        dry = terrain.isPassable(tx + dx, tz + dz);
                    }
                }
                if (dry && terrain.tileHeight(tx, tz) < terrain.seaLevel() + 6f) {
                    return new int[]{tx, tz};
                }
            }
        }
        throw new IllegalStateException("no suitable tile");
    }

    private static void assertNoFoodOrCreaturesInWater(World world) {
        Terrain terrain = world.terrain();
        ComponentStore<ResourceNode> nodes = world.ecs().store(ResourceNode.class);
        for (int i = 0; i < nodes.size(); i++) {
            Transform t = world.ecs().get(nodes.entityAt(i), Transform.class);
            if (nodes.componentAt(i).type.kind() == ResourceKind.FOOD) {
                assertTrue(terrain.isPassable((int) Math.floor(t.position.x), (int) Math.floor(t.position.z)), "food in water");
            }
        }
        ComponentStore<SpeciesRef> creatures = world.ecs().store(SpeciesRef.class);
        for (int i = 0; i < creatures.size(); i++) {
            Transform t = world.ecs().get(creatures.entityAt(i), Transform.class);
            assertTrue(terrain.isPassable((int) Math.floor(t.position.x), (int) Math.floor(t.position.z)), "creature in water");
        }
    }

    @Test
    void childrenOfBelieversBelieve() {
        World world = create(13);
        ComponentStore<SpeciesRef> creatures = world.ecs().store(SpeciesRef.class);
        for (int i = 0; i < creatures.size(); i++) {
            world.ecs().add(creatures.entityAt(i), new Believer());
        }
        int tick = 0;
        while (world.births().total() == 0 && tick < 10 * 60 * Time.TICKS_PER_SECOND) {
            world.tick(tick++);
        }
        assertTrue(world.births().total() > 0, "no births within 10 minutes");
        assertEquals(world.population(), world.believers(), "every child of believers believes");
    }

    @Test
    void samePowersOnTheSameSeedGiveTheSameWorld() {
        long[] results = new long[2];
        for (int run = 0; run < 2; run++) {
            World world = create(17);
            Transform t = position(world, firstCreature(world));
            for (int tick = 0; tick < 600; tick++) {
                if (tick == 10) {
                    world.godPowers().request(DivinePower.LIGHTNING, t.position.x, t.position.z);
                    world.godPowers().request(DivinePower.ABUNDANCE, t.position.x + 5f, t.position.z);
                }
                if (tick == 200) {
                    world.godPowers().request(DivinePower.LOWER, t.position.x + 8f, t.position.z + 8f);
                }
                world.tick(tick);
            }
            long hash = world.population();
            ComponentStore<Transform> transforms = world.ecs().store(Transform.class);
            for (int i = 0; i < transforms.size(); i++) {
                hash = hash * 31 + Float.floatToIntBits(transforms.componentAt(i).position.x);
                hash = hash * 31 + Float.floatToIntBits(transforms.componentAt(i).position.z);
            }
            results[run] = hash;
        }
        assertEquals(results[0], results[1]);
    }

    @Test
    void invalidPowerDataIsRejected() {
        String json = """
                {"faith": {"starting": 10, "basePerMinute": 1, "perBelieverPerMinute": 1},
                 "rain": {"name": "D", "description": "", "cost": 0, "radius": 5, "alignment": 0,
                          "durationSeconds": 10, "regrowMultiplier": 2, "thirstReliefPerSecond": 0}}""";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DataLoader.parseGodConfig(json, "p.json"));
        assertTrue(e.getMessage().startsWith("p.json"), e.getMessage());
    }
}

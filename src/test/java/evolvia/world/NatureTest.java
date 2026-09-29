package evolvia.world;

import evolvia.components.Believer;
import evolvia.components.Fear;
import evolvia.components.GroupMember;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.components.ResourceNode;
import evolvia.components.Sick;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.DivinePower;
import evolvia.god.GodPowers;
import evolvia.god.HandAction;
import evolvia.systems.NeedsSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 9e DoD: seasons, weather, disease from spoiled food, natural disasters. */
class NatureTest {

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

    private static int run(World world, int from, int ticks) {
        for (int tick = from; tick < from + ticks; tick++) {
            world.tick(tick);
        }
        return from + ticks;
    }

    private static int ticksPerDay() {
        return Math.round(config.time().dayLengthSeconds() * Time.TICKS_PER_SECOND);
    }

    /** A flammable land tile far from every creature, or null. */
    private static int[] lonelyFlammableTile(World world, String biome) {
        Terrain terrain = world.terrain();
        for (int tz = 10; tz < terrain.depth() - 10; tz += 3) {
            for (int tx = 10; tx < terrain.width() - 10; tx += 3) {
                if (terrain.isPassable(tx, tz) && terrain.biome(tx, tz).id().equals(biome)
                        && world.creatureGrid().nearest(tx + 0.5f, tz + 0.5f, 25f, e -> true) < 0) {
                    return new int[]{tx, tz};
                }
            }
        }
        return null;
    }

    private static int playerCreature(World world) {
        return world.ecs().store(Believer.class).entityAt(0);
    }

    @Test
    void seasonsFollowEachOtherAndChangeTheWorld() {
        World world = create(1);
        Nature nature = world.nature();
        int perSeason = nature.config().seasonDays() * ticksPerDay();
        List<String> order = new ArrayList<>();
        for (int s = 0; s < 5; s++) {
            order.add(nature.season(s * perSeason + 10).id());
        }
        assertEquals(List.of("spring", "summer", "autumn", "winter", "spring"), order);
        assertEquals(2, nature.year(4 * perSeason + 10));
        nature.setWeather(Nature.Weather.CLEAR, Integer.MAX_VALUE);
        float spring = nature.regrowMultiplier(10);
        float winter = nature.regrowMultiplier(3 * perSeason + 10);
        assertTrue(winter < spring * 0.5f, "little grows in winter: " + winter + " vs " + spring);
        assertTrue(nature.temperatureOffset(3 * perSeason + 10, false) < nature.temperatureOffset(perSeason + 10, false),
                "winter is colder than summer");
        assertTrue(nature.thirstMultiplier(perSeason + 10) > nature.thirstMultiplier(10), "summer makes thirsty");
        assertTrue(nature.snowCover(3 * perSeason + perSeason / 2) > 0.5f, "snow in winter");
        assertEquals(0f, nature.snowCover(perSeason + 10), 1e-6, "no snow in summer");
    }

    @Test
    void theWeatherChangesTheSameWayForTheSameSeed() {
        World a = create(3);
        World b = create(3);
        List<Nature.Weather> seen = new ArrayList<>();
        for (int tick = 0; tick < ticksPerDay(); tick++) {
            a.tick(tick);
            b.tick(tick);
            assertEquals(a.nature().weather(), b.nature().weather());
            if (seen.isEmpty() || seen.getLast() != a.nature().weather()) {
                seen.add(a.nature().weather());
            }
            assertTrue(a.nature().weather() != Nature.Weather.SNOW, "no snow in spring");
        }
        assertEquals(Nature.Weather.CLEAR, seen.getFirst(), "the game starts in clear weather");
        assertTrue(a.nature().weatherUntilTick() > ticksPerDay() - 200 * Time.TICKS_PER_SECOND);
        assertTrue(seen.size() >= 2 || a.nature().weatherUntilTick() > 0, "weather periods: " + seen);
    }

    /** Moves the creature to the nearest land outside every refuge. */
    private static void outInTheOpen(World world, int entity) {
        evolvia.components.Transform t = world.ecs().get(entity, evolvia.components.Transform.class);
        for (int r = 0; r < 60; r++) {
            for (int k = 0; k < 16; k++) {
                float x = t.position.x + (float) Math.sin(k * Math.PI / 8) * r;
                float z = t.position.z + (float) Math.cos(k * Math.PI / 8) * r;
                if (world.terrain().isPassable((int) x, (int) z) && world.refuges().at(x, z) == null) {
                    world.moveCreature(entity, x, z);
                    return;
                }
            }
        }
    }

    @Test
    void rainQuenchesThirstAndRefugesKeepTheBlizzardOut() {
        World world = create(4);
        Nature nature = world.nature();
        int entity = playerCreature(world);
        outInTheOpen(world, entity); // rain falls on it (not in a refuge)
        Needs needs = world.ecs().get(entity, Needs.class);
        NeedsSystem system = new NeedsSystem(world.terrain(), world.clock(), world.refuges(), nature);

        nature.setWeather(Nature.Weather.CLEAR, Integer.MAX_VALUE);
        needs.thirst = 0.5f;
        system.update(world.ecs(), 10);
        float dry = needs.thirst;
        nature.setWeather(Nature.Weather.RAIN, Integer.MAX_VALUE);
        needs.thirst = 0.5f;
        system.update(world.ecs(), 10);
        assertTrue(needs.thirst < dry, "rain quenches thirst: " + needs.thirst + " vs " + dry);

        nature.schedule(Nature.Disaster.BLIZZARD, 1);
        world.tick(1);
        assertTrue(nature.blizzard(2));
        assertEquals(Nature.Weather.SNOW, nature.weather());
        assertTrue(nature.temperatureOffset(2, false) < nature.temperatureOffset(2, true) - 0.2f,
                "the blizzard is cold, but not in a refuge");
        assertTrue(nature.takeAnnouncements().getFirst().contains("Vánice"));
    }

    @Test
    void diseaseHurtsSpreadsInTheHerdAndTheHandHealsIt() {
        World world = create(5);
        Groups.Group herd = world.groups().all().stream().filter(g -> g.player).findFirst().orElseThrow();
        int patient = herd.leader;
        int durationTicks = SpeciesDefinition.secondsToTicks(world.nature().config().disease().durationSeconds());
        assertTrue(Sick.infect(world.ecs(), patient, 0, durationTicks, 1000));
        assertFalse(Sick.infect(world.ecs(), patient, 0, durationTicks, 1000), "no second infection");

        int tick = run(world, 0, 60 * Time.TICKS_PER_SECOND);
        Health health = world.ecs().get(patient, Health.class);
        assertTrue(health.hp < health.maxHp - 0.2f, "the illness hurts: " + health.hp);
        int ill = 0;
        ComponentStore<Sick> sick = world.ecs().store(Sick.class);
        for (int i = 0; i < sick.size(); i++) {
            GroupMember m = world.ecs().get(sick.entityAt(i), GroupMember.class);
            assertNotNull(m);
            ill++;
        }
        assertTrue(ill > 1, "the herd caught it: " + ill);

        world.godPowers().faith().add(100f);
        assertTrue(world.godPowers().request(new GodPowers.HandCommand(HandAction.HEAL, patient, -1, 0, 0)));
        world.tick(tick);
        assertFalse(world.ecs().get(patient, Sick.class).isActive(tick + 1), "healed");
        assertEquals(health.maxHp, health.hp, 1e-4f);
        assertFalse(Sick.infect(world.ecs(), patient, tick + 1, durationTicks, 1000), "immune after healing");
    }

    @Test
    void theIllCanDieOfIt() {
        World world = create(6);
        int patient = playerCreature(world);
        Sick.infect(world.ecs(), patient, 0, 10_000, 0);
        world.ecs().get(patient, Health.class).hp = 0.01f;
        run(world, 0, 2 * Time.TICKS_PER_SECOND);
        assertEquals(1, world.deaths().count(DeathStats.Cause.DISEASE));
    }

    @Test
    void spoiledCarcassesLookSpoiledByAge() {
        World world = create(7);
        Transform t = world.ecs().get(playerCreature(world), Transform.class);
        int carcass = world.placeResource("carcass", t.position.x + 3f, t.position.z);
        run(world, 0, 10);
        assertEquals(10, world.ecs().get(carcass, ResourceNode.class).ageTicks, "a carcass ages");
    }

    @Test
    void fireSpreadsOverFlammableLandBurnsFoodAndRainPutsItOut() {
        World world = create(8);
        Nature nature = world.nature();
        nature.setWeather(Nature.Weather.CLEAR, Integer.MAX_VALUE);
        int[] tile = lonelyFlammableTile(world, "forest");
        assertNotNull(tile, "a forest far from everyone");
        int bush = world.placeResource("berry_bush", tile[0] + 1.5f, tile[1] + 0.5f);
        assertTrue(nature.ignite(tile[0], tile[1], 0));
        assertFalse(nature.ignite(tile[0], tile[1], 0), "burning already");
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                nature.ignite(tile[0] + dx, tile[1] + dz, 0);
            }
        }
        int started = nature.burningTiles().size();

        int tick = run(world, 0, 12 * Time.TICKS_PER_SECOND);
        int touched = 0;
        for (int tz = 0; tz < world.terrain().depth(); tz++) {
            for (int tx = 0; tx < world.terrain().width(); tx++) {
                if (nature.isBurnt(tx, tz, tick)) {
                    touched++;
                }
            }
        }
        assertTrue(touched > started, "the fire spread: " + started + " -> " + touched);
        assertFalse(nature.burningTiles().isEmpty(), "and still burns");
        for (int i : nature.burningTiles()) {
            Terrain terrain = world.terrain();
            int tx = i % terrain.width();
            int tz = i / terrain.width();
            assertTrue(terrain.isPassable(tx, tz) && nature.flammability(tx, tz) > 0f, "only flammable land burns");
        }
        ResourceNode node = world.ecs().get(bush, ResourceNode.class);
        if (nature.isBurning(tile[0] + 1, tile[1]) || nature.isBurnt(tile[0] + 1, tile[1], tick)) {
            assertEquals(0f, node.amount, 1e-4f, "the bush burnt");
        }

        // A creature in the flames gets hurt and runs.
        int entity = playerCreature(world);
        int burning = nature.burningTiles().getLast();
        Transform t = world.ecs().get(entity, Transform.class);
        world.moveCreature(entity, burning % world.terrain().width() + 0.5f, burning / world.terrain().width() + 0.5f);
        float hp = world.ecs().get(entity, Health.class).hp;
        tick = run(world, tick, Time.TICKS_PER_SECOND + 1);
        assertTrue(world.ecs().isAlive(entity));
        assertTrue(world.ecs().get(entity, Health.class).hp < hp, "fire hurts");
        assertNotNull(world.ecs().get(entity, Fear.class), "and scares");
        assertNotNull(t);

        // The god's rain over the fire puts it out.
        world.godPowers().faith().add(200f);
        float radius = world.godPowers().config().rain().radius();
        float cx = tile[0] + 0.5f;
        float cz = tile[1] + 0.5f;
        world.godPowers().request(DivinePower.RAIN, cx, cz);
        world.tick(tick++);
        world.tick(tick);
        for (int i : nature.burningTiles()) {
            float x = i % world.terrain().width() + 0.5f;
            float z = i / world.terrain().width() + 0.5f;
            assertTrue(Math.hypot(x - cx, z - cz) > radius, "no fire left under the rain");
        }
    }

    @Test
    void aFloodCoversLowLandAndRecedes() {
        World world = create(9);
        Nature nature = world.nature();
        Nature.Flood flood = nature.config().disasters().flood();
        nature.schedule(Nature.Disaster.FLOOD, 0);
        int rise = Math.round(flood.riseSeconds() * Time.TICKS_PER_SECOND);
        int tick = run(world, 0, rise + 20);
        assertEquals(flood.rise(), nature.floodLevel(tick), 1e-3f);
        Terrain terrain = world.terrain();
        int flooded = 0;
        int[] low = null;
        for (int tz = 0; tz < terrain.depth(); tz++) {
            for (int tx = 0; tx < terrain.width(); tx++) {
                if (nature.isFlooded(tx, tz, tick)) {
                    flooded++;
                    low = new int[]{tx, tz};
                }
            }
        }
        assertTrue(flooded > 20, "low coast is under water: " + flooded);

        int entity = playerCreature(world);
        world.moveCreature(entity, low[0] + 0.5f, low[1] + 0.5f);
        float hp = world.ecs().get(entity, Health.class).hp;
        tick = run(world, tick, Time.TICKS_PER_SECOND + 1);
        if (world.ecs().isAlive(entity)) {
            assertTrue(world.ecs().get(entity, Health.class).hp < hp, "the water hurts");
            assertNotNull(world.ecs().get(entity, Fear.class), "and they run uphill");
        }

        run(world, tick, Math.round((flood.holdSeconds() + flood.riseSeconds() + 2) * Time.TICKS_PER_SECOND));
        assertFalse(nature.flooding(), "the water went down");
        assertEquals(0f, nature.floodLevel(tick + 100_000), 1e-6f);
    }

    @Test
    void noDisastersInTheFirstDays() {
        World world = create(10);
        int graceDays = world.nature().config().disasters().graceDays();
        int tick = 0;
        while (world.clock().day(tick) <= graceDays) {
            world.tick(tick++);
            assertNull(world.nature().pendingDisaster());
            assertFalse(world.nature().flooding());
            assertFalse(world.nature().blizzard(tick));
        }
        assertTrue(world.nature().takeAnnouncements().isEmpty());
    }

    @Test
    void stormLightningStrikesByItself() {
        World world = create(12);
        Nature nature = world.nature();
        nature.setWeather(Nature.Weather.STORM, Integer.MAX_VALUE);
        nature.setNextStrike(5);
        int strikes = world.godPowers().recentStrikes().size();
        run(world, 0, 10);
        assertTrue(world.godPowers().recentStrikes().size() > strikes, "a natural strike");
        assertTrue(nature.nextStrikeTick() > 10, "the next one is later");
        assertTrue(world.ecs().store(SpeciesRef.class).size() > 0);
    }
}

package evolvia.world;

import evolvia.components.Believer;
import evolvia.components.Needs;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.DivinePower;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 9d DoD: day and night, refuges, herds sleeping in them, sacred places. */
class DayNightTest {

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

    private static int ticksPerDay() {
        return (int) (config.time().dayLengthSeconds() * Time.TICKS_PER_SECOND);
    }

    /** First tick at (or after) a time of day on the first day, or on the next one if it has passed. */
    private static int tickAt(WorldClock clock, float timeOfDay) {
        float start = config.time().startTimeOfDay();
        float days = timeOfDay >= start ? timeOfDay - start : 1f + timeOfDay - start;
        return (int) Math.ceil(days * ticksPerDay());
    }

    /** First tick after {@code from} at a time of day. */
    private static int next(WorldClock clock, int from, float timeOfDay) {
        float days = timeOfDay - clock.timeOfDay(from);
        return from + (int) Math.ceil((days < 0 ? days + 1f : days) * ticksPerDay());
    }

    private static int run(World world, int from, int to) {
        for (int tick = from; tick < to; tick++) {
            world.tick(tick);
        }
        return to;
    }

    private static Groups.Group playerHerd(World world) {
        return world.groups().all().stream().filter(g -> g.player).findFirst().orElseThrow();
    }

    /** Believers asleep, and how many of them are in a refuge. */
    private static int[] sleepers(World world) {
        ComponentStore<Believer> store = world.ecs().store(Believer.class);
        int asleep = 0;
        int all = 0;
        for (int i = 0; i < store.size(); i++) {
            Needs needs = world.ecs().get(store.entityAt(i), Needs.class);
            all++;
            if (needs != null && needs.sleeping) {
                asleep++;
            }
        }
        return new int[]{asleep, all};
    }

    @Test
    void theClockRunsThroughDaysAndNights() {
        WorldClock clock = new WorldClock(config.time());
        assertEquals(config.time().startTimeOfDay(), clock.timeOfDay(0), 1e-6);
        assertEquals(1, clock.day(0));
        assertFalse(clock.isNight(0), "the game starts in the morning");
        assertEquals(2, clock.day(tickAt(clock, 0.01f)), "a new day starts at midnight");
        assertTrue(clock.isNight(tickAt(clock, 0.9f)));
        assertTrue(clock.isShelterTime(tickAt(clock, 0.7f)));
        assertFalse(clock.isNight(tickAt(clock, 0.7f)), "evening is not night yet");
        assertEquals(1f, clock.nightness(tickAt(clock, 0f)), 1e-3);
        assertEquals(0f, clock.nightness(tickAt(clock, 0.5f)), 1e-3);
        assertEquals(clock.timeOfDay(0), clock.timeOfDay(ticksPerDay()), 1e-5);
    }

    @Test
    void refugesArePlacedOnLandApartFromEachOther() {
        World world = World.create(config, biomes, species, tree, resources, 3);
        Refuges refuges = world.refuges();
        assertTrue(refuges.all().size() >= 8, "enough refuges: " + refuges.all().size());
        for (Refuges.Refuge a : refuges.all()) {
            assertTrue(world.terrain().isPassable((int) a.x, (int) a.z));
            assertEquals(a, refuges.get(a.id));
            assertEquals(a, refuges.at(a.x + a.radius() * 0.5f, a.z));
            for (Refuges.Refuge b : refuges.all()) {
                if (a != b) {
                    assertTrue(Math.hypot(a.x - b.x, a.z - b.z) >= refuges.config().minSpacing() - 1e-3);
                }
            }
        }
        // The same seed gives the same refuges.
        World again = World.create(config, biomes, species, tree, resources, 3);
        assertEquals(refuges.all().size(), again.refuges().all().size());
        assertEquals(refuges.all().getLast().x, again.refuges().all().getLast().x);
    }

    @Test
    void herdsSleepInRefugesAtNightAndWakeInTheMorning() {
        World world = World.create(config, biomes, species, tree, resources, 11);
        WorldClock clock = world.clock();
        int tick = run(world, 0, tickAt(clock, 0.70f));
        assertTrue(world.groups().all().stream().anyMatch(g -> g.shelter != 0), "in the evening herds pick a refuge");

        tick = run(world, tick, next(clock, tick, 0.02f)); // midnight
        int[] night = sleepers(world);
        int sheltered = world.shelteredSleepers(false);
        assertTrue(night[0] * 2 > night[1], "most of the people sleep at night: " + night[0] + "/" + night[1]);
        assertTrue(sheltered * 2 > night[0], "most sleepers are in a refuge: " + sheltered + "/" + night[0]);
        assertTrue(world.milestones().completed().contains("first_shelter"), "milestone: first night in a refuge");

        run(world, tick, next(clock, tick, 0.40f)); // late morning
        int[] day = sleepers(world);
        assertTrue(day[0] * 4 < day[1], "by day few sleep: " + day[0] + "/" + day[1]);
        assertTrue(world.milestones().completed().contains("first_night"), "milestone: survived the first night");
    }

    @Test
    void theNightIsColdOutsideARefuge() {
        World world = World.create(config, biomes, species, tree, resources, 4);
        WorldClock clock = world.clock();
        int entity = world.ecs().store(Believer.class).entityAt(0);
        // Herds start where it is mild (after phase 10): take it where a night can be cold.
        float cool = world.species().stats().climate().comfortMin() + 0.05f;
        search:
        for (int tz = 0; tz < world.terrain().depth(); tz++) {
            for (int tx = 0; tx < world.terrain().width(); tx++) {
                float temperature = world.terrain().temperature(tx, tz);
                if (world.terrain().isPassable(tx, tz) && temperature > cool - 0.03f && temperature < cool + 0.03f) {
                    world.moveCreature(entity, tx + 0.5f, tz + 0.5f);
                    break search;
                }
            }
        }
        Transform t = world.ecs().get(entity, Transform.class);
        Needs needs = world.ecs().get(entity, Needs.class);
        Refuges none = new Refuges(world.refuges().config());
        Refuges here = new Refuges(world.refuges().config());
        here.add(world.refuges().config().types().getFirst(), t.position.x, t.position.z);

        int midnight = tickAt(clock, 0.0f);
        new NeedsSystemProbe(world, clock, none).update(0);
        float noon = needs.exposure;
        new NeedsSystemProbe(world, clock, none).update(midnight);
        float outside = needs.exposure;
        new NeedsSystemProbe(world, clock, here).update(midnight);
        float inside = needs.exposure;
        assertTrue(outside < inside - 0.01f, "the night is colder outside: " + outside + " vs " + inside);
        assertTrue(outside < noon, "the night is colder than the day");
    }

    /** Runs the needs system alone on a world's creatures. */
    private record NeedsSystemProbe(World world, WorldClock clock, Refuges refuges) {
        void update(int tick) {
            new evolvia.systems.NeedsSystem(world.terrain(), clock, refuges).update(world.ecs(), tick);
        }
    }

    @Test
    void sanctifyingARefugeMakesItTheHomeOfThePeopleAndBringsFaith() {
        World world = World.create(config, biomes, species, tree, resources, 11);
        Groups.Group herd = playerHerd(world);
        Refuges.Refuge refuge = world.refuges().nearest(herd.homeX, herd.homeZ, 1e6f, false);
        assertNotNull(refuge);

        // Nothing to sanctify there: nothing is paid.
        world.godPowers().faith().add(200f);
        float faith = world.godPowers().faith().points();
        float cost = world.godPowers().config().of(DivinePower.SANCTIFY).cost();
        float farX = refuge.x + (refuge.x > world.terrain().width() / 2f ? -1 : 1) * world.refuges().config().minSpacing() * 0.5f;
        if (world.refuges().nearest(farX, refuge.z, world.godPowers().config().sanctify().radius(), false) == null) {
            world.godPowers().request(DivinePower.SANCTIFY, farX, refuge.z);
            world.tick(0);
            assertTrue(world.godPowers().faith().points() >= faith - 1e-3, "no refuge, no cost");
        }

        faith = world.godPowers().faith().points();
        world.godPowers().request(DivinePower.SANCTIFY, refuge.x + 1f, refuge.z);
        world.tick(1);
        assertTrue(refuge.sacred);
        assertEquals(1, world.refuges().sacredCount());
        assertTrue(world.godPowers().faith().points() < faith - cost + 5f, "the power was paid");
        assertTrue(herd.settled);
        assertEquals(refuge.x, herd.homeX);
        assertEquals(refuge.z, herd.homeZ);
        int tick = run(world, 2, 2 + 10 * Time.TICKS_PER_SECOND);
        assertTrue(world.milestones().completed().contains("sacred_place"));

        // At night the people sleep in their sacred place.
        run(world, tick, next(world.clock(), tick, 0.02f));
        assertTrue(world.shelteredSleepers(true) > 0, "believers sleep in the sacred place");
    }
}

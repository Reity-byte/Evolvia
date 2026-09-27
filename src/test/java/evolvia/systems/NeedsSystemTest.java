package evolvia.systems;

import evolvia.components.Age;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.components.SpeciesRef;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.EcsWorld;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.DeathStats;
import evolvia.world.SpatialGrid;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeedsSystemTest {

    private static SpeciesDefinition species;
    private static SpeciesDefinition.NeedRates rates;

    @BeforeAll
    static void loadSpecies() {
        species = DataLoader.loadSpecies();
        rates = species.needs();
    }

    private static int creature(EcsWorld world) {
        int e = world.createEntity();
        world.add(e, new SpeciesRef(species));
        world.add(e, new Needs());
        world.add(e, new Health(species.maxHealth()));
        Age age = world.add(e, new Age());
        age.maxAgeTicks = 1_000_000;
        return e;
    }

    private static void run(EcsWorld world, NeedsSystem needs, AgingSystem aging, int ticks) {
        for (int t = 0; t < ticks; t++) {
            needs.update(world, t);
            if (aging != null) {
                aging.update(world, t);
            }
            world.flushDestroyed();
        }
    }

    @Test
    void needsGrowAtConfiguredRates() {
        EcsWorld world = new EcsWorld();
        int e = creature(world);
        run(world, new NeedsSystem(), null, 10 * Time.TICKS_PER_SECOND); // 10 s
        Needs needs = world.get(e, Needs.class);
        assertEquals(10 * rates.hungerPerSecond(), needs.hunger, 1e-4f);
        assertEquals(10 * rates.thirstPerSecond(), needs.thirst, 1e-4f);
        assertEquals(1f - 10 * rates.energyDrainPerSecond(), needs.energy, 1e-4f);
    }

    @Test
    void sleepingRestoresEnergyAndSlowsNeeds() {
        EcsWorld world = new EcsWorld();
        int e = creature(world);
        Needs needs = world.get(e, Needs.class);
        needs.energy = 0.2f;
        needs.sleeping = true;
        run(world, new NeedsSystem(), null, 10 * Time.TICKS_PER_SECOND);
        assertEquals(0.2f + 10 * rates.energyRecoverPerSecond(), needs.energy, 1e-4f);
        assertEquals(10 * rates.hungerPerSecond() * rates.sleepingNeedFactor(), needs.hunger, 1e-4f);
    }

    @Test
    void needsAreClampedToOne() {
        EcsWorld world = new EcsWorld();
        int e = creature(world);
        Needs needs = world.get(e, Needs.class);
        needs.hunger = 0.999f;
        needs.energy = 0.0001f;
        run(world, new NeedsSystem(), null, 100);
        assertEquals(1f, needs.hunger);
        assertEquals(0f, needs.energy);
    }

    @Test
    void starvationDamagesHealthAndFoodHeals() {
        EcsWorld world = new EcsWorld();
        int e = creature(world);
        Needs needs = world.get(e, Needs.class);
        Health health = world.get(e, Health.class);
        needs.hunger = 1f;
        run(world, new NeedsSystem(), null, Time.TICKS_PER_SECOND);
        assertEquals(species.maxHealth() - rates.damagePerSecond(), health.hp, 1e-4f);

        needs.hunger = 0f;
        run(world, new NeedsSystem(), null, Time.TICKS_PER_SECOND);
        assertEquals(species.maxHealth() - rates.damagePerSecond() + rates.healthRegenPerSecond(), health.hp, 1e-4f);
    }

    @Test
    void starvingCreatureDiesAndIsCounted() {
        EcsWorld world = new EcsWorld();
        int hungry = creature(world);
        int thirsty = creature(world);
        world.get(hungry, Needs.class).hunger = 1f;
        world.get(thirsty, Needs.class).thirst = 1f;
        DeathStats deaths = new DeathStats();
        int ticksToDie = (int) Math.ceil(species.maxHealth() / rates.damagePerSecond() * Time.TICKS_PER_SECOND) + 2;
        run(world, new NeedsSystem(), new AgingSystem(deaths, new SpatialGrid(64, 64, 16)), ticksToDie);
        assertFalse(world.isAlive(hungry));
        assertFalse(world.isAlive(thirsty));
        assertEquals(1, deaths.count(DeathStats.Cause.STARVATION));
        assertEquals(1, deaths.count(DeathStats.Cause.THIRST));
    }

    @Test
    void creatureDiesOfOldAge() {
        EcsWorld world = new EcsWorld();
        int e = creature(world);
        world.get(e, Age.class).maxAgeTicks = 50;
        DeathStats deaths = new DeathStats();
        run(world, new NeedsSystem(), new AgingSystem(deaths, new SpatialGrid(64, 64, 16)), 49);
        assertTrue(world.isAlive(e));
        run(world, new NeedsSystem(), new AgingSystem(deaths, new SpatialGrid(64, 64, 16)), 1);
        assertFalse(world.isAlive(e));
        assertEquals(1, deaths.count(DeathStats.Cause.OLD_AGE));
        assertEquals(1, deaths.total());
    }
}

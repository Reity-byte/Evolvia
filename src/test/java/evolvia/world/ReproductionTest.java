package evolvia.world;

import evolvia.components.Age;
import evolvia.components.Genome;
import evolvia.components.SpeciesRef;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.evolution.SpeciesDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReproductionTest {

    private static WorldConfig config;
    private static BiomeTable biomes;
    private static SpeciesDefinition species;
    private static ResourceTable resources;

    @BeforeAll
    static void loadData() {
        config = DataLoader.loadWorldConfig();
        biomes = DataLoader.loadBiomes();
        species = DataLoader.loadSpecies();
        resources = DataLoader.loadResources();
    }

    private static int run(World world, int from, int ticks) {
        for (int tick = from; tick < from + ticks; tick++) {
            world.tick(tick);
        }
        return from + ticks;
    }

    @Test
    void offspringAverageParentsWithinVariation() {
        Terrain terrain = TestTerrains.fromAscii("....");
        CreatureFactory factory = new CreatureFactory(new EcsWorld(), terrain, new SpatialGrid(4, 1, 16), new Random(1));
        SpeciesDefinition noMutation = new SpeciesDefinition(species.id(), species.name(), species.rgb(), species.bodySize(),
                species.speed(), species.maxHealth(), species.lifespanMinSeconds(), species.lifespanMaxSeconds(),
                species.senseRadius(), species.needs(), species.eating(), species.ai(), species.wander(),
                species.reproduction(), new SpeciesDefinition.GenomeTuning(0.1f, 0f), species.population(),
                species.diet(), species.climate(), species.evolution(), species.groups(), species.combat());

        Genome a = new Genome();
        a.size = 0.9f;
        a.speed = 1.1f;
        a.generation = 3;
        Genome b = new Genome();
        b.size = 1.0f;
        b.speed = 1.0f;
        b.generation = 5;
        Genome child = factory.inherit(noMutation, a, b);
        assertEquals(0.95f, child.size, 1e-6f);
        assertEquals(1.05f, child.speed, 1e-6f);
        assertEquals(6, child.generation, "one more than the older parent");

        // With mutation, genes wander but never leave the allowed variation.
        Genome current = child;
        for (int i = 0; i < 2000; i++) {
            current = factory.inherit(species, current, current);
            float v = species.genome().variation();
            assertTrue(current.size >= 1 - v - 1e-6f && current.size <= 1 + v + 1e-6f);
            assertTrue(current.speed >= 1 - v - 1e-6f && current.speed <= 1 + v + 1e-6f);
        }
    }

    @Test
    void startingPopulationIsAGroupAroundOnePlace() {
        World world = World.create(config, biomes, species, resources, 7);
        assertEquals(species.population().starting(), world.population(), "the player's people");
        ComponentStore<SpeciesRef> creatures = world.ecs().store(SpeciesRef.class);
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        for (int i = 0; i < creatures.size(); i++) {
            if (world.ecs().get(creatures.entityAt(i), evolvia.components.Believer.class) == null) {
                continue; // wild herds live elsewhere
            }
            float x = world.ecs().get(creatures.entityAt(i), evolvia.components.Transform.class).position.x;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
        }
        assertTrue(maxX - minX <= 2 * species.population().spawnRadius() + 1, "spread " + (maxX - minX));
    }

    @Test
    void populationReproducesAndGenomesStayInRange() {
        SpeciesDefinition many = species.withPopulation(new SpeciesDefinition.Population(200, 25f, 3000));
        World world = World.create(config, biomes, many, resources, 7);
        run(world, 0, 10 * 60 * 20); // ten minutes
        assertTrue(world.births().total() > 50, "births: " + world.births().total());
        assertTrue(world.maxGeneration() >= 1);
        assertTrue(world.creatureCount() > many.population().starting(), "population did not grow: " + world.creatureCount());

        int adultTicks = SpeciesDefinition.secondsToTicks(species.reproduction().adultAgeSeconds());
        int young = 0;
        ComponentStore<Genome> genomes = world.ecs().store(Genome.class);
        for (int i = 0; i < genomes.size(); i++) {
            Genome g = genomes.componentAt(i);
            float v = species.genome().variation();
            assertTrue(g.size >= 1 - v - 1e-6f && g.size <= 1 + v + 1e-6f);
            assertTrue(g.tint >= 1 - v - 1e-6f && g.tint <= 1 + v + 1e-6f);
            if (world.ecs().get(genomes.entityAt(i), Age.class).ageTicks < adultTicks) {
                young++;
            }
        }
        assertTrue(young > 0, "no young creatures after ten minutes");
        assertEquals(world.creatureCount(), world.creatureGrid().size(), "creature grid out of sync");
    }

    @Test
    void populationSettlesOnSmallMap() {
        // A 128x128 map keeps the test fast; carrying capacity is a few hundred creatures there.
        WorldConfig small = new WorldConfig(128, 128, config.heightScale(), config.seaLevel(), config.height(),
                config.heightExponent(), config.edgeFalloff(), config.temperature(), config.altitudeCooling(),
                config.moisture(), config.water());
        SpeciesDefinition few = species.withPopulation(new SpeciesDefinition.Population(60, 15f, 3000));
        World world = World.create(small, biomes, few, resources, 11);
        int tick = run(world, 0, 20 * 60 * 20); // 20 minutes to grow
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int minute = 0; minute < 15; minute++) {
            tick = run(world, tick, 60 * 20);
            min = Math.min(min, world.creatureCount());
            max = Math.max(max, world.creatureCount());
        }
        assertTrue(min > 30, "population collapsed: min " + min);
        assertTrue(max < 3 * min, "population not settled: " + min + " .. " + max);
        assertEquals(world.creatureCount(), world.creatureGrid().size(), "creature grid out of sync");
    }
}

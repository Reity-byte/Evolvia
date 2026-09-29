package evolvia.world;

import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.GodConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 11b DoD: the rival founds its tribe after the player's, gathers and builds its own camp. */
class RivalTribeTest {

    private static WorldConfig config;
    private static BiomeTable biomes;
    private static SpeciesDefinition people;
    private static ResourceTable resources;
    private static EvolutionTree tree;
    private static GodConfig god;

    @BeforeAll
    static void loadData() {
        config = DataLoader.loadWorldConfig();
        biomes = DataLoader.loadBiomes();
        SpeciesDefinition base = DataLoader.loadSpecies();
        SpeciesDefinition.Population p = base.population();
        people = base.withPopulation(new SpeciesDefinition.Population(44, 8f, p.max(), p.wildHerds(), p.wildHerdSize(),
                p.herdSpacing()));
        resources = DataLoader.loadResources();
        tree = DataLoader.loadEvolutionTree(biomes);
        god = DataLoader.loadGodConfig();
    }

    static World create(long seed) {
        return World.create(config, biomes, people, tree, resources, god, seed);
    }

    /** A world whose clock is past the rival's tribe minute and whose people have a tribe. */
    static World withBothTribes(long seed) {
        World world = create(seed);
        int late = Math.round(world.rivals().tribeMinute() * 60 * Time.TICKS_PER_SECOND);
        world.rivalTribeSystem().update(world.ecs(), late);
        assertNull(world.rivalTribe(), "no rival tribe while the player has none");

        world.species().addPoints(5000f);
        for (String node : TribeTest.TRIBE) {
            world.develop(node);
        }
        world.evolveEveryone();
        world.tribeSystem().update(world.ecs(), 0);
        assertNotNull(world.tribeGroup());
        world.rivalTribeSystem().update(world.ecs(), late - 100 * Time.TICKS_PER_SECOND);
        assertNull(world.rivalTribe(), "not before its minute");
        world.rivalTribeSystem().update(world.ecs(), late);
        assertNotNull(world.rivalTribe(), "after the player's and in its minute");
        return world;
    }

    @Test
    void theRivalFoundsItsTribeAfterThePlayersAndLearnsToGather() {
        World world = withBothTribes(3);
        Groups.Group rival = world.rivalTribe();
        assertEquals(world.rivals().species(), rival.species);
        assertTrue(rival.hasCamp);
        assertTrue(world.rivals().species().stage(0).hasAbility("tools"), "the rival gathers with its tribe");
        assertTrue(world.rivalTribeSystem().takeAnnouncements().stream().anyMatch(a -> a.contains("Hrubci")), "announced");
        assertTrue(world.groups().all().stream().filter(g -> g.species == rival.species).count() >= 1);
        assertTrue(world.tribeGroup() != rival);
    }

    @Test
    void theRivalBuildsItsOwnCamp() {
        World world = withBothTribes(4);
        Groups.Group rival = world.rivalTribe();
        int tick = Math.round(world.rivals().tribeMinute() * 60 * Time.TICKS_PER_SECOND);
        world.restoreTick(tick);
        rival.stock.put("wood", 60f);
        rival.stock.put("stone", 40f);
        for (int end = tick + 4 * 60 * Time.TICKS_PER_SECOND; tick < end; tick++) {
            world.tick(tick);
            if (tick % (20 * Time.TICKS_PER_SECOND) == 0) {
                rival.stock.merge("wood", 10f, Float::sum); // enough materials: this tests the building, not the gathering
                rival.stock.merge("stone", 5f, Float::sum);
            }
        }
        Settlement camp = world.rivalSettlement();
        assertTrue(camp.doneTypes().size() >= 2, "the rival built " + camp.doneTypes());
        for (String type : camp.doneTypes()) {
            assertTrue(world.rivals().buildings().contains(type), "only what it knows: " + type);
        }
        for (Settlement.Building building : camp.all()) {
            assertTrue(Math.hypot(building.x - rival.campX, building.z - rival.campZ) < 30, "around its own camp");
            if (building.refuge != 0) {
                assertEquals("rival", world.refuges().get(building.refuge).owner, "its huts are its own");
            }
        }
        assertTrue(world.settlement().all().stream().noneMatch(b -> camp.all().contains(b)), "two settlements");
        assertTrue(List.of("fire", "shelter", "storage").containsAll(camp.doneTypes()));
    }
}

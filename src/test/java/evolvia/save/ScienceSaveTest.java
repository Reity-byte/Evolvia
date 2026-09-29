package evolvia.save;

import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.GodConfig;
import evolvia.world.BiomeTable;
import evolvia.world.ResourceTable;
import evolvia.world.World;
import evolvia.world.WorldConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 10b: science is saved (save version 10) and older saves turn the node Tools into the discovery. */
class ScienceSaveTest {

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
                p.herdSpacing())); // Social groups need 25 people
        resources = DataLoader.loadResources();
        tree = DataLoader.loadEvolutionTree(biomes);
        god = DataLoader.loadGodConfig();
    }

    private static WorldCodec.GameData data() {
        return new WorldCodec.GameData(config.water().shallowDepth(), config.time(), biomes, people, tree, resources, god);
    }

    private static final SaveData.View VIEW = new SaveData.View(0f, 0f, 0f, 1f, 50f);

    private static String json(World world, long tick) {
        SaveData s = WorldCodec.snapshot(world, "t", tick, Time.Speed.NORMAL, VIEW);
        SaveData.Meta m = s.meta();
        return SaveManager.toJson(new SaveData(s.saveVersion(), new SaveData.Meta(m.name(), "", m.speciesName(), m.population(),
                m.generation(), m.tick()), s.seed(), s.tick(), s.speed(), s.view(), s.random(), s.terrain(), s.species(), s.god(),
                s.stats(), s.ecs(), s.pathQueue(), s.groups(), s.milestones(), s.refuges(), s.nature(), s.buildings(), s.science(), s.rival()));
    }

    @Test
    void scienceIsSavedAndContinuesIdentically() {
        World world = World.create(config, biomes, people, tree, resources, god, 5);
        world.species().addPoints(5000f);
        for (String node : List.of("body_strong_legs", "body_upright", "body_bipedal", "body_hands", "mind_instincts",
                "mind_memory", "mind_social_groups", "mind_speech")) {
            world.unlock(node);
        }
        world.evolveEveryone();
        world.science().enqueue("sci_tools", world);
        world.science().enqueue("sci_fire", world);
        world.science().enqueue("sci_construction", world);
        int tick = 0;
        for (; tick < 20 * Time.TICKS_PER_SECOND; tick++) {
            world.tick(tick);
        }
        world.science().add(25f, world); // Tools done, a bit of Fire
        assertTrue(world.science().isDiscovered("sci_tools"));

        String saved = json(world, tick);
        World copy = WorldCodec.restore(SaveManager.fromJson(SaveManager.toJson(
                WorldCodec.snapshot(world, "t", tick, Time.Speed.NORMAL, VIEW))), data()).world();
        assertEquals(saved, json(copy, tick));
        assertEquals(world.science().target(), copy.science().target());
        assertEquals(world.science().queue(), copy.science().queue());
        assertTrue(copy.species().stage(0).hasAbility("tools"), "the discovery's effect is back");
        int end = tick + 30 * Time.TICKS_PER_SECOND;
        for (int t = tick; t < end; t++) {
            world.tick(t);
            copy.tick(t);
        }
        assertEquals(json(world, end), json(copy, end));
    }

    @Test
    void anOldSaveTurnsTheToolsNodeIntoTheDiscovery() {
        World world = World.create(config, biomes, people, tree, resources, god, 6);
        SaveData s = WorldCodec.snapshot(world, "t", 0, Time.Speed.NORMAL, VIEW);
        List<String> unlocked = new ArrayList<>(s.species().unlocked());
        unlocked.add("mind_tools");
        SaveData old = new SaveData(9, s.meta(), s.seed(), s.tick(), s.speed(), s.view(), s.random(), s.terrain(),
                new SaveData.SpeciesData(s.species().id(), s.species().points(), s.species().pointsEarned(), unlocked),
                s.god(), s.stats(), s.ecs(), s.pathQueue(), s.groups(), s.milestones(), s.refuges(), s.nature(), s.buildings());
        WorldCodec.Loaded loaded = WorldCodec.restore(SaveManager.fromJson(SaveManager.toJson(old)), data());
        assertTrue(loaded.world().science().isDiscovered("sci_tools"));
        assertFalse(loaded.skippedNodes().contains("mind_tools"), "not reported as lost");
        assertTrue(loaded.world().science().takeAnnouncements().isEmpty(), "no announcement for a loaded save");
    }
}

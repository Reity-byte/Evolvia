package evolvia.save;

import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.GodConfig;
import evolvia.world.BiomeTable;
import evolvia.world.Groups;
import evolvia.world.ResourceTable;
import evolvia.world.World;
import evolvia.world.WorldConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 11: the rival, its plan, its tribe and its buildings are saved and continue identically. */
class RivalSaveTest {

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

    private static final SaveData.View VIEW = new SaveData.View(0f, 0f, 0f, 1f, 50f);

    private static String json(World world, long tick) {
        SaveData s = WorldCodec.snapshot(world, "t", tick, Time.Speed.NORMAL, VIEW);
        SaveData.Meta m = s.meta();
        return SaveManager.toJson(new SaveData(s.saveVersion(), new SaveData.Meta(m.name(), "", m.speciesName(), m.population(),
                m.generation(), m.tick()), s.seed(), s.tick(), s.speed(), s.view(), s.random(), s.terrain(), s.species(), s.god(),
                s.stats(), s.ecs(), s.pathQueue(), s.groups(), s.milestones(), s.refuges(), s.nature(), s.buildings(), s.science(),
                s.rival()));
    }

    @Test
    void theRivalAndItsTribeAreSavedAndContinueIdentically() {
        World world = World.create(config, biomes, people, tree, resources, god, 8);
        world.species().addPoints(5000f);
        for (String node : List.of("body_strong_legs", "body_upright", "body_bipedal", "body_hands", "sci_tools",
                "mind_instincts", "mind_memory", "mind_social_groups", "mind_speech", "mind_tribe")) {
            world.develop(node);
        }
        world.evolveEveryone();
        int tick = Math.round(world.rivals().tribeMinute() * 60 * Time.TICKS_PER_SECOND);
        world.restoreTick(tick);
        world.tribeSystem().update(world.ecs(), tick);
        for (int end = tick + 20 * Time.TICKS_PER_SECOND; tick < end; tick++) {
            world.tick(tick);
        }
        Groups.Group rival = world.rivalTribe();
        assertNotNull(rival);
        rival.stock.put("wood", 60f);
        rival.stock.put("stone", 40f);
        for (int end = tick + 40 * Time.TICKS_PER_SECOND; tick < end; tick++) {
            world.tick(tick);
        }
        assertFalse(world.rivalSettlement().all().isEmpty(), "the rival is building");
        assertTrue(world.rivalSystem().step() > 0);

        String saved = json(world, tick);
        World copy = WorldCodec.restore(SaveManager.fromJson(SaveManager.toJson(
                WorldCodec.snapshot(world, "t", tick, Time.Speed.NORMAL, VIEW))), new WorldCodec.GameData(
                config.water().shallowDepth(), config.time(), biomes, people, tree, resources, god)).world();
        assertEquals(saved, json(copy, tick));
        assertNotNull(copy.rivalTribe());
        assertEquals(world.rivalSettlement().all().size(), copy.rivalSettlement().all().size());
        assertEquals(world.rivals().species().unlockedNodes(), copy.rivals().species().unlockedNodes());
        int end = tick + 30 * Time.TICKS_PER_SECOND;
        for (int t = tick; t < end; t++) {
            world.tick(t);
            copy.tick(t);
        }
        assertEquals(json(world, end), json(copy, end));
    }
}

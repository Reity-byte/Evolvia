package evolvia.save;

import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.GodConfig;
import evolvia.god.GodPowers;
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

/** Phase 9h: the tribe, roles, buildings and plans are saved and the loaded world continues identically. */
class TribeSaveTest {

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

    private static World create(long seed) {
        return World.create(config, biomes, people, tree, resources, god, seed);
    }

    private static void evolve(World world, String last) {
        world.species().addPoints(2000f);
        for (String node : List.of("body_strong_legs", "body_upright", "body_bipedal", "body_hands", "mind_tools",
                "mind_instincts", "mind_memory", "mind_social_groups", "mind_speech", "mind_tribe")) {
            world.unlock(node);
        }
        world.evolveEveryone();
    }

    private static int run(World world, int from, int ticks) {
        for (int tick = from; tick < from + ticks; tick++) {
            world.tick(tick);
        }
        return from + ticks;
    }

    @Test
    void theTribeIsSavedAndContinuesIdentically() {
        World world = create(7);
        evolve(world, null);
        int tick = run(world, 0, 10 * Time.TICKS_PER_SECOND);
        Groups.Group tribe = world.tribeGroup();
        tribe.stock.put("wood", 40f);
        tribe.stock.put("stone", 40f);
        float[] spot = world.tribeSystem().spot(tribe);
        world.godPowers().faith().add(100f);
        world.godPowers().request(new GodPowers.PlanCommand("storage", spot[0], spot[1], 30f));
        tick = run(world, tick, 40 * Time.TICKS_PER_SECOND);
        assertFalse(world.settlement().all().isEmpty());

        WorldCodec.GameData data = new WorldCodec.GameData(config.water().shallowDepth(), config.time(), biomes, people, tree,
                resources, god);
        SaveData.View view = new SaveData.View(0f, 0f, 0f, 1f, 50f);
        String saved = SaveManager.toJson(timeless(WorldCodec.snapshot(world, "t", tick, Time.Speed.NORMAL, view)));
        World copy = WorldCodec.restore(SaveManager.fromJson(SaveManager.toJson(
                WorldCodec.snapshot(world, "t", tick, Time.Speed.NORMAL, view))), data).world();
        assertEquals(saved, SaveManager.toJson(timeless(WorldCodec.snapshot(copy, "t", tick, Time.Speed.NORMAL, view))));
        int end = run(world, tick, 30 * Time.TICKS_PER_SECOND);
        run(copy, tick, 30 * Time.TICKS_PER_SECOND);
        assertEquals(SaveManager.toJson(timeless(WorldCodec.snapshot(world, "t", end, Time.Speed.NORMAL, view))),
                SaveManager.toJson(timeless(WorldCodec.snapshot(copy, "t", end, Time.Speed.NORMAL, view))));
        assertNotNull(copy.tribeGroup());
        assertEquals(world.settlement().all().size(), copy.settlement().all().size());
    }

    private static SaveData timeless(SaveData save) {
        SaveData.Meta m = save.meta();
        return new SaveData(save.saveVersion(), new SaveData.Meta(m.name(), "", m.speciesName(), m.population(), m.generation(), m.tick()),
                save.seed(), save.tick(), save.speed(), save.view(), save.random(), save.terrain(), save.species(), save.god(),
                save.stats(), save.ecs(), save.pathQueue(), save.groups(), save.milestones(), save.refuges(), save.nature(),
                save.buildings());
    }
}

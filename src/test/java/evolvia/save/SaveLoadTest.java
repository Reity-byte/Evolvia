package evolvia.save;

import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.DivinePower;
import evolvia.god.GodConfig;
import evolvia.world.BiomeTable;
import evolvia.world.Refuges;
import evolvia.world.ResourceTable;
import evolvia.world.World;
import evolvia.world.WorldConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 8 DoD: saving and loading returns the game to an identical state (save → load → compare). */
class SaveLoadTest {

    private static WorldConfig config;
    private static BiomeTable biomes;
    private static SpeciesDefinition species;
    private static ResourceTable resources;
    private static EvolutionTree tree;
    private static GodConfig god;
    private static WorldCodec.GameData data;

    @BeforeAll
    static void loadData() {
        config = DataLoader.loadWorldConfig();
        biomes = DataLoader.loadBiomes();
        species = DataLoader.loadSpecies().withPopulation(new SpeciesDefinition.Population(80, 12f, 3000, 3, 25, 40f));
        resources = DataLoader.loadResources();
        tree = DataLoader.loadEvolutionTree(biomes);
        god = DataLoader.loadGodConfig();
        data = new WorldCodec.GameData(config.water().shallowDepth(), config.time(), biomes, species, tree, resources, god);
    }

    private static final SaveData.View VIEW = new SaveData.View(100f, 120f, 0.5f, 0.9f, 60f);

    /** Snapshot as JSON, without the wall-clock time of saving (the only thing allowed to differ). */
    private static String state(World world, long tick) {
        SaveData save = WorldCodec.snapshot(world, "test", tick, Time.Speed.NORMAL, VIEW);
        SaveData.Meta meta = save.meta();
        SaveData timeless = new SaveData(save.saveVersion(),
                new SaveData.Meta(meta.name(), "", meta.speciesName(), meta.population(), meta.generation(), meta.tick()),
                save.seed(), save.tick(), save.speed(), save.view(), save.random(), save.terrain(), save.species(),
                save.god(), save.stats(), save.ecs(), save.pathQueue(), save.groups(), save.milestones(), save.refuges(), save.nature(), save.buildings(),
                save.science());
        return SaveManager.toJson(timeless);
    }

    /** Some player activity: evolution and every god power, at fixed ticks. */
    private static void play(World world, int tick, float x, float z) {
        switch (tick) {
            case 100 -> {
                world.species().addPoints(400f);
                world.unlock("body_strong_legs");
                world.unlock("mind_instincts");
                world.unlock("mind_memory");
                world.unlock("mind_social_groups"); // herds are part of the saved state
                world.evolveEveryone();
            }
            case 150 -> world.godPowers().request(DivinePower.RAIN, x, z);
            case 200 -> {
                world.species().addPoints(30f);
                world.unlock("body_keen_eyes"); // only newborns get it: mixed stages are saved
            }
            case 300 -> world.godPowers().request(DivinePower.ABUNDANCE, x + 6f, z);
            case 700 -> { // at a creature's current position (herds move away from the start)
                Transform t = world.ecs().get(world.ecs().store(SpeciesRef.class).entityAt(0), Transform.class);
                world.godPowers().request(DivinePower.LIGHTNING, t.position.x, t.position.z);
            }
            case 1300 -> {
                world.godPowers().faith().add(50f);
                world.godPowers().request(DivinePower.LOWER, x + 10f, z + 10f);
            }
            case 1700 -> world.godPowers().request(DivinePower.RAISE, x + 10f, z + 10f);
            case 1200 -> { // phase 9g: gathering (loads, camps, stock are saved)
                world.species().addPoints(1000f);
                for (String node : List.of("body_strong_legs", "body_upright", "body_bipedal", "body_hands", "sci_tools")) {
                    if (!world.species().isUnlocked(node)) {
                        world.develop(node);
                    }
                }
                world.evolveEveryone();
            }
            case 2100 -> { // phase 9e: a storm, a fire and an illness are saved
                world.nature().setWeather(evolvia.world.Nature.Weather.STORM, 4600);
                world.nature().setNextStrike(2200);
                for (int dz = -30; dz <= 30; dz += 3) {
                    for (int dx = -30; dx <= 30; dx += 3) {
                        if (world.nature().burningTiles().isEmpty()) {
                            world.nature().ignite((int) x + dx, (int) z + dz, tick);
                        }
                    }
                }
                int patient = world.ecs().store(SpeciesRef.class).entityAt(1);
                evolvia.components.Sick.infect(world.ecs(), patient, tick, 3000, 3000);
            }
            case 2000 -> { // phase 9d: a sacred place (saved with the refuges)
                world.godPowers().faith().add(100f);
                Refuges.Refuge refuge = world.refuges().nearest(x, z, 1e6f, false);
                world.godPowers().request(DivinePower.SANCTIFY, refuge.x, refuge.z);
            }
            default -> {
            }
        }
    }

    private static int run(World world, int from, int ticks, float x, float z) {
        for (int tick = from; tick < from + ticks; tick++) {
            play(world, tick, x, z);
            world.tick(tick);
        }
        return from + ticks;
    }

    @Test
    void loadedWorldIsIdenticalAndContinuesIdentically() {
        World original = World.create(config, biomes, species, tree, resources, god, 21);
        Transform first = original.ecs().get(original.ecs().store(SpeciesRef.class).entityAt(0), Transform.class);
        float x = first.position.x;
        float z = first.position.z;
        // Saved in the evening (herds have picked their refuges for the night), after every god power.
        int tick = run(original, 0, 3800, x, z);

        String saved = state(original, tick);
        SaveData roundTrip = SaveManager.fromJson(SaveManager.toJson(WorldCodec.snapshot(original, "test", tick, Time.Speed.NORMAL, VIEW)));
        WorldCodec.Loaded loaded = WorldCodec.restore(roundTrip, data);
        World copy = loaded.world();
        assertEquals(tick, loaded.tick());
        assertEquals(VIEW, loaded.view());
        assertTrue(loaded.skippedNodes().isEmpty());
        assertEquals(saved, state(copy, tick), "loaded world differs from the saved one");

        // Both continue (with the same player actions) and must stay identical.
        int end = run(original, tick, 1500, x, z);
        run(copy, tick, 1500, x, z);
        assertEquals(state(original, end), state(copy, end), "loaded world continued differently");
        assertNotEquals(saved, state(original, end), "the simulation should have moved on");
        assertTrue(original.deaths().total() > 0, "a lively world was tested");
        assertTrue(original.groups().count() > 3, "herds were saved and restored");
        assertTrue(copy.refuges().sacredCount() > 0, "the sacred place was saved");
        assertTrue(copy.nature().hasBurntGround(), "the fire was saved");
        assertTrue(copy.groups().all().stream().anyMatch(g -> g.hasCamp && g.stockTotal() > 0), "camps were saved");
        assertEquals(original.nature().weather(), copy.nature().weather());
        assertTrue(copy.ecs().store(evolvia.components.Sick.class).size() > 0, "the illness was saved");
        assertTrue(copy.groups().all().stream().anyMatch(g -> g.shelter != 0), "the refuges of herds were saved");
    }

    @Test
    void savesAreWrittenListedAndReadFromFiles(@TempDir Path folder) throws IOException {
        World world = World.create(config, biomes, species, tree, resources, god, 5);
        run(world, 0, 200, 50f, 50f);
        SaveManager saves = new SaveManager(folder.resolve("saves"));
        SaveData data1 = WorldCodec.snapshot(world, "Můj svět", 200, Time.Speed.FAST, VIEW);
        Path file = saves.saveAsync(data1).join();
        assertTrue(Files.size(file) < 3_000_000, "compressed save is small: " + Files.size(file));
        try (var files = Files.list(folder.resolve("saves"))) {
            assertEquals(List.of(file), files.toList(), "no temporary files left");
        }

        List<SaveManager.SaveInfo> list = saves.list();
        assertEquals(1, list.size());
        assertEquals("Můj svět", list.getFirst().meta().name());
        assertEquals(world.population(), list.getFirst().meta().population());
        assertEquals(WorldCodec.SAVE_VERSION, list.getFirst().saveVersion());

        WorldCodec.Loaded loaded = WorldCodec.restore(saves.load("Můj svět"), SaveLoadTest.data);
        assertEquals(Time.Speed.FAST, loaded.speed());
        assertEquals(state(world, 200), state(loaded.world(), 200));

        saves.delete("Můj svět");
        assertTrue(saves.list().isEmpty());
        assertThrows(SaveException.class, () -> saves.load("Můj svět"));
        saves.close();
    }

    @Test
    void namesAreSafeFileNames() {
        assertEquals("a_b_c", SaveManager.cleanName("a/b\\c"));
        assertEquals("Žluťoučký kůň-1", SaveManager.cleanName(" Žluťoučký kůň-1 "));
        assertEquals("save", SaveManager.cleanName("   "));
    }

    @Test
    void savesFromANewerGameAreRefused() {
        World world = World.create(config, biomes, species, tree, resources, god, 5);
        SaveData save = WorldCodec.snapshot(world, "x", 0, Time.Speed.NORMAL, VIEW);
        SaveData newer = new SaveData(WorldCodec.SAVE_VERSION + 1, save.meta(), save.seed(), save.tick(), save.speed(),
                save.view(), save.random(), save.terrain(), save.species(), save.god(), save.stats(), save.ecs(), save.pathQueue(),
                save.groups(), save.milestones(), save.refuges(), save.nature(), save.buildings());
        SaveException e = assertThrows(SaveException.class, () -> WorldCodec.restore(newer, data));
        assertTrue(e.getMessage().contains("novější"), e.getMessage());
    }

    @Test
    void version1SavesWithoutHerdsStillLoad() {
        World world = World.create(config, biomes, species, tree, resources, god, 5);
        SaveData save = WorldCodec.snapshot(world, "x", 0, Time.Speed.NORMAL, VIEW);
        SaveData.EcsData e = save.ecs();
        SaveData.EcsData oldEcs = new SaveData.EcsData(e.nextId(), e.alive(), e.free(), e.transforms(), e.prevTransforms(),
                e.velocities(), e.creatures(), e.genomes(), e.needs(), e.healths(), e.ages(), e.reproductions(), e.ai(),
                e.memories(), e.resources(), e.believers(), e.fears(), null, null, null, null, null, null, null);
        SaveData v1 = new SaveData(1, save.meta(), save.seed(), save.tick(), save.speed(), save.view(), save.random(),
                save.terrain(), save.species(), save.god(), save.stats(), oldEcs, save.pathQueue(), null, null, null, null, null);
        World loaded = WorldCodec.restore(SaveManager.fromJson(SaveManager.toJson(v1)), data).world();
        assertEquals(loaded.creatureCount(), loaded.population(), "before phase 9c everyone was the player's people");
        assertTrue(loaded.groups().all().stream().noneMatch(g -> g.species == null), "herds of the people form again");
        assertTrue(loaded.animalCount() > 0, "an old save gets wild game");
    }

    @Test
    void evolutionNodesMissingFromTheCurrentTreeAreSkipped() {
        World world = World.create(config, biomes, species, tree, resources, god, 5);
        world.species().addPoints(100f);
        world.unlock("body_strong_legs");
        SaveData save = WorldCodec.snapshot(world, "x", 0, Time.Speed.NORMAL, VIEW);
        SaveData.SpeciesData s = save.species();
        SaveData changed = new SaveData(save.saveVersion(), save.meta(), save.seed(), save.tick(), save.speed(),
                save.view(), save.random(), save.terrain(),
                new SaveData.SpeciesData(s.id(), s.points(), s.pointsEarned(), List.of("body_strong_legs", "removed_node")),
                save.god(), save.stats(), save.ecs(), save.pathQueue(), save.groups(), save.milestones(), save.refuges(), save.nature(), save.buildings());
        WorldCodec.Loaded loaded = WorldCodec.restore(changed, data);
        assertEquals(List.of("removed_node"), loaded.skippedNodes());
        assertTrue(loaded.world().species().isUnlocked("body_strong_legs"));
        assertFalse(loaded.world().species().isUnlocked("removed_node"));
    }
}

package evolvia.world;

import evolvia.components.Age;
import evolvia.components.Believer;
import evolvia.components.SpeciesRef;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.Condition;
import evolvia.evolution.EvolutionNode;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.GodConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 10b DoD: knowledge from speakers, research target and queue, discoveries as culture, buildings need them. */
class ScienceTest {

    static final List<String> SPEECH = List.of("mind_instincts", "mind_memory", "mind_social_groups", "mind_speech");

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

    private static void develop(World world, List<String> ids) {
        world.species().addPoints(5000f);
        for (String id : ids) {
            world.develop(id);
        }
        world.evolveEveryone();
    }

    private static int run(World world, int from, int ticks) {
        for (int tick = from; tick < from + ticks; tick++) {
            world.tick(tick);
        }
        return from + ticks;
    }

    private static int adultSpeakers(World world) {
        ComponentStore<SpeciesRef> refs = world.ecs().store(SpeciesRef.class);
        int count = 0;
        for (int i = 0; i < refs.size(); i++) {
            int e = refs.entityAt(i);
            SpeciesRef ref = refs.componentAt(i);
            Age age = world.ecs().get(e, Age.class);
            if (ref.species == world.species() && world.ecs().get(e, Believer.class) != null && ref.hasAbility("speech")
                    && age.ageTicks >= SpeciesDefinition.secondsToTicks(ref.stats().reproduction().adultAgeSeconds())) {
                count++;
            }
        }
        return count;
    }

    @Test
    void theDataFitTogether() {
        Science.Config science = DataLoader.loadScience();
        EvolutionTree discoveries = science.tree();
        assertTrue(discoveries.size() >= 9);
        assertEquals(List.of("work", "fire", "society"), discoveries.branches());
        for (EvolutionNode node : discoveries.nodes()) {
            if (node.condition() instanceof Condition.Evolved evolved) {
                assertNotNull(tree.node(evolved.id()), node.id() + " needs a known evolution node");
            }
        }
        for (EvolutionNode node : tree.nodes()) {
            if (node.condition() instanceof Condition.All all) {
                for (Condition c : all.conditions()) {
                    if (c instanceof Condition.Discovery d) {
                        assertNotNull(discoveries.node(d.id()), node.id() + " needs a known discovery");
                    }
                }
            }
        }
        for (Tribe.BuildingType type : DataLoader.loadTribe().buildings()) {
            assertNotNull(type.requires(), type.id() + " needs a discovery");
            assertNotNull(discoveries.node(type.requires()), type.id() + ": unknown discovery " + type.requires());
        }
        assertNull(tree.node("mind_tools"), "Tools moved to science");
    }

    @Test
    void knowledgeComesOnlyWithSpeechAndGrowsWithTheSpeakers() {
        World silent = create(1);
        run(silent, 0, 30 * Time.TICKS_PER_SECOND);
        assertFalse(silent.science().isActive());
        assertEquals(0f, silent.science().earned(), 1e-6, "no speech, no science");

        World speaking = create(1);
        develop(speaking, SPEECH);
        assertTrue(speaking.science().isActive());
        int tick = run(speaking, 0, 10 * Time.TICKS_PER_SECOND);
        int speakers = adultSpeakers(speaking);
        assertTrue(speakers > 10, "speakers " + speakers);
        Science.Rules rules = speaking.science().rules();
        assertEquals(rules.basePerMinute() * (float) Math.log1p(speakers), speaking.science().perMinute(), 0.3f);
        float before = speaking.science().earned();
        run(speaking, tick, 60 * Time.TICKS_PER_SECOND);
        float perMinute = speaking.science().earned() - before;
        assertTrue(perMinute > 0.8f * rules.basePerMinute() * Math.log1p(speakers), "earned " + perMinute + " in a minute");
        assertEquals(speaking.science().earned(), speaking.science().stored(), 1e-3, "without a target it is stored");
    }

    @Test
    void theTargetIsResearchedAndItsEffectReachesEveryoneAtOnce() {
        World world = create(2);
        develop(world, List.of("body_strong_legs", "body_upright", "body_bipedal", "body_hands"));
        Science science = world.science();
        EvolutionNode tools = science.tree().node("sci_tools");
        assertEquals(Species.NodeStatus.AVAILABLE, science.availability(tools, world).status());
        assertEquals(Species.NodeStatus.LOCKED, science.availability(science.tree().node("sci_fire"), world).status());

        assertTrue(science.setTarget("sci_tools", world));
        science.add(tools.cost() - 1f, world);
        assertFalse(science.isDiscovered("sci_tools"));
        assertEquals((tools.cost() - 1f) / tools.cost(), science.share(tools), 1e-4);
        science.add(3f, world);
        assertTrue(science.isDiscovered("sci_tools"));
        assertNull(science.target());
        assertEquals(2f, science.stored(), 1e-4, "the rest waits for the next target");
        assertEquals(List.of("Objev: Nástroje"), science.takeAnnouncements());
        assertTrue(world.species().stage(0).hasAbility("tools"), "culture: even the oldest stage has it");

        // Clothing (after Fire) makes everyone bear the cold better at once.
        float cold = world.species().stage(0).stats().climate().comfortMin();
        science.discover("sci_fire");
        assertTrue(science.setTarget("sci_clothing", world));
        science.add(1000f, world);
        assertTrue(science.isDiscovered("sci_clothing"));
        assertEquals(cold - 0.12f, world.species().stage(0).stats().climate().comfortMin(), 1e-4);
        assertEquals(cold - 0.12f + 0f, world.species().latestStage().stats().climate().comfortMin(), 1e-4);
        assertTrue(science.cost(science.tree().node("sci_cooking")) > science.tree().node("sci_cooking").cost(),
                "every discovery makes the next dearer");
    }

    @Test
    void theQueueFollowsTheTargetAndSwitchingKeepsProgress() {
        World world = create(3);
        develop(world, List.of("body_strong_legs", "body_upright", "body_bipedal", "body_hands"));
        Science science = world.science();
        assertFalse(science.enqueue("sci_clothing", world), "its requirement is not planned");
        assertTrue(science.enqueue("sci_tools", world), "an empty plan: the target");
        assertEquals("sci_tools", science.target());
        assertTrue(science.enqueue("sci_fire", world));
        assertTrue(science.enqueue("sci_clothing", world), "Fire is planned before it");
        assertEquals(List.of("sci_fire", "sci_clothing"), science.queue());

        science.add(science.cost(science.tree().node("sci_tools")) + 5f, world);
        assertTrue(science.isDiscovered("sci_tools"));
        assertEquals("sci_fire", science.target(), "the queue moves on");
        assertEquals(5f, science.progress("sci_fire"), 1e-3, "the overflow goes into the next one");

        assertTrue(science.setTarget("sci_construction", world), "switch the target");
        assertEquals(5f, science.progress("sci_fire"), 1e-3, "switching keeps the progress");
        science.cancel("sci_construction", world);
        assertEquals("sci_fire", science.target(), "cancelling takes the next queued one that can be researched");

        for (int i = 0; i < 10; i++) {
            science.enqueue(science.tree().nodes().get(i % science.tree().size()).id(), world);
        }
        assertTrue(science.queue().size() <= science.rules().queueMax());
    }

    @Test
    void theTribeNeedsToolsAndBuildsOnlyWhatItKnows() {
        World world = create(4);
        develop(world, List.of("body_strong_legs", "body_upright", "body_bipedal", "body_hands"));
        develop(world, SPEECH);
        Species.Availability tribe = world.species().availability(tree.node("mind_tribe"), world);
        assertEquals(Species.NodeStatus.LOCKED, tribe.status());
        assertTrue(tribe.reason().contains("Nástroje"), tribe.reason());

        develop(world, List.of("sci_tools", "mind_tribe"));
        int tick = run(world, 0, 10 * Time.TICKS_PER_SECOND);
        Groups.Group herd = world.tribeGroup();
        assertNotNull(herd);
        herd.stock.put("wood", 40f);
        herd.stock.put("stone", 40f);
        float[] spot = world.tribeSystem().spot(herd);
        assertFalse(world.planBuilding("fire", spot[0], spot[1]), "no plan without the discovery");
        tick = run(world, tick, 30 * Time.TICKS_PER_SECOND);
        assertTrue(world.settlement().all().isEmpty(), "nothing is known, nothing is built");

        world.develop("sci_fire");
        herd.stock.put("wood", 40f);
        run(world, tick, 30 * Time.TICKS_PER_SECOND);
        assertEquals(1, world.settlement().all().size());
        assertEquals("fire", world.settlement().all().getFirst().type.id(), "the fire, the only thing it knows");
    }
}

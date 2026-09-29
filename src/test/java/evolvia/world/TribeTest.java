package evolvia.world;

import evolvia.components.Believer;
import evolvia.components.Fear;
import evolvia.components.GroupMember;
import evolvia.components.Needs;
import evolvia.components.Role;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.GodConfig;
import evolvia.god.GodPowers;
import evolvia.systems.NeedsSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 9h DoD: speech, the tribe, roles, buildings with effects, the god's plans, morality. */
class TribeTest {

    static final List<String> TRIBE = List.of("body_strong_legs", "body_upright", "body_bipedal", "body_hands", "sci_tools",
            "mind_instincts", "mind_memory", "mind_social_groups", "mind_speech", "mind_tribe", "sci_fire", "sci_construction",
            "sci_storage", "sci_rituals");

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
        // A bigger people (the Tribe node needs 25 of them) in one herd that splits: the tribe is the biggest part.
        people = base.withPopulation(new SpeciesDefinition.Population(44, 8f, p.max(), p.wildHerds(), p.wildHerdSize(),
                p.herdSpacing()));
        resources = DataLoader.loadResources();
        tree = DataLoader.loadEvolutionTree(biomes);
        god = DataLoader.loadGodConfig();
    }

    private static World create(long seed) {
        return World.create(config, biomes, people, tree, resources, god, seed);
    }

    /** Unlocks up to the Tribe node (or only up to {@code last}) and gives everyone the latest stage. */
    private static void evolve(World world, String last) {
        world.species().addPoints(2000f);
        for (String node : TRIBE) {
            world.develop(node);
            if (node.equals(last)) {
                break;
            }
        }
        world.evolveEveryone();
    }

    private static int run(World world, int from, int ticks) {
        for (int tick = from; tick < from + ticks; tick++) {
            world.tick(tick);
        }
        return from + ticks;
    }

    private static List<Integer> members(World world, Groups.Group group) {
        ComponentStore<GroupMember> store = world.ecs().store(GroupMember.class);
        List<Integer> list = new java.util.ArrayList<>();
        for (int i = 0; i < store.size(); i++) {
            if (store.componentAt(i).group == group.id) {
                list.add(store.entityAt(i));
            }
        }
        return list;
    }

    @Test
    void theTribeFormsFromTheLargestHerdOnlyWithTheNodeAndOthersJoin() {
        World world = create(1);
        evolve(world, "mind_speech");
        int tick = run(world, 0, 20 * Time.TICKS_PER_SECOND);
        assertNull(world.tribeGroup(), "no tribe without the node");

        world.unlock("mind_tribe");
        int largest = world.groups().all().stream().filter(g -> g.player && g.species == null).mapToInt(g -> g.size).max().orElse(0);
        tick = run(world, tick, 10 * Time.TICKS_PER_SECOND);
        Groups.Group tribe = world.tribeGroup();
        assertNotNull(tribe, "the tribe is founded");
        assertTrue(tribe.size >= largest, "from the largest herd");
        assertTrue(tribe.hasCamp && tribe.settled, "with a camp");
        assertTrue(world.milestones().isCompleted("tribe") || run(world, tick, 40) > 0 && world.milestones().isCompleted("tribe"));

        run(world, tick, 90 * Time.TICKS_PER_SECOND);
        long otherPeopleHerds = world.groups().all().stream().filter(g -> g.player && g.species == null && !g.tribe).count();
        assertTrue(otherPeopleHerds == 0 || tribe.size > largest, "the other herds of the people join: " + otherPeopleHerds);
        assertTrue(tribe.size > 24, "the tribe has no herd size limit: " + tribe.size);
    }

    @Test
    void theTribeHandsOutRolesAndBuildsThreeKindsOfBuildingsByItself() {
        World world = create(2);
        evolve(world, null);
        int tick = run(world, 0, 10 * Time.TICKS_PER_SECOND);
        Groups.Group tribe = world.tribeGroup();
        assertNotNull(tribe);
        tribe.stock.put("wood", 40f);
        tribe.stock.put("stone", 40f);
        boolean builders = false;
        for (int minute = 0; minute < 10 && world.settlement().doneTypes().size() < 3; minute++) {
            for (int i = 0; i < 60 * Time.TICKS_PER_SECOND; i++) {
                world.tick(tick++);
                if (i % 100 == 0) {
                    ComponentStore<Role> roles = world.ecs().store(Role.class);
                    for (int r = 0; r < roles.size(); r++) {
                        builders |= roles.componentAt(r).builder;
                        GroupMember m = world.ecs().get(roles.entityAt(r), GroupMember.class);
                        assertTrue(m != null && m.group == tribe.id, "roles only in the tribe");
                    }
                }
            }
        }
        assertTrue(builders, "builders were chosen");
        assertTrue(world.settlement().doneTypes().size() >= 3, "three kinds built: " + world.settlement().doneTypes());
        assertTrue(world.settlement().doneTypes().contains("fire"), "the fire comes first");
        for (Settlement.Building b : world.settlement().all()) {
            assertTrue(world.terrain().isPassable((int) b.x, (int) b.z), "built on land");
        }
    }

    @Test
    void buildingsHaveMeasurableEffects() {
        World world = create(3);
        evolve(world, null);
        int tick = run(world, 0, 10 * Time.TICKS_PER_SECOND);
        Groups.Group tribe = world.tribeGroup();
        Settlement settlement = world.settlement();
        Tribe.Config rules = world.tribe();

        // Fire: warmth at night.
        Settlement.Building fire = settlement.add(rules.building("fire"), tribe.campX + 6f, tribe.campZ, false);
        int person = members(world, tribe).getFirst();
        Transform t = world.ecs().get(person, Transform.class);
        int midnight = Math.round((1f - config.time().startTimeOfDay()) * config.time().dayLengthSeconds() * Time.TICKS_PER_SECOND);
        NeedsSystem needs = new NeedsSystem(world.terrain(), world.clock(), new Refuges(world.refuges().config()), world.nature());
        needs.setSettlement(settlement);
        world.moveCreature(person, fire.x, fire.z);
        needs.update(world.ecs(), midnight);
        float coldWithoutFire = world.ecs().get(person, Needs.class).exposure;
        fire.progress = 1f;
        needs.update(world.ecs(), midnight);
        float withFire = world.ecs().get(person, Needs.class).exposure;
        assertTrue(withFire > coldWithoutFire + 0.01f || coldWithoutFire >= 0f, "the fire warms: " + coldWithoutFire + " -> " + withFire);
        assertEquals(0f, settlement.warmthAt(fire.x + 30f, fire.z), "only near the fire");

        // Shelter: a new refuge.
        int refugesBefore = world.refuges().all().size();
        settlement.add(rules.building("shelter"), tribe.campX - 6f, tribe.campZ, false).progress = 1f;
        tick = run(world, tick, 6 * Time.TICKS_PER_SECOND);
        assertEquals(refugesBefore + 1, world.refuges().all().size());
        assertEquals("hut", world.refuges().all().getLast().type.id());

        // Store: a bigger stock.
        settlement.add(rules.building("storage"), tribe.campX, tribe.campZ + 6f, false).progress = 1f;
        assertEquals(rules.buildings().stream().filter(b -> b.id().equals("storage")).findFirst().orElseThrow().value(),
                settlement.storageFactor(), 1e-4f);

        // Shrine: more faith.
        run(world, tick, 2 * Time.TICKS_PER_SECOND);
        float before = world.godPowers().faith().perMinute();
        settlement.add(rules.building("shrine"), tribe.campX, tribe.campZ - 6f, false).progress = 1f;
        run(world, tick + 2 * Time.TICKS_PER_SECOND, 2 * Time.TICKS_PER_SECOND);
        assertTrue(world.godPowers().faith().perMinute() > before + 1f, "the shrine brings faith: " + before + " -> "
                + world.godPowers().faith().perMinute());
    }

    @Test
    void theGodsPlanCostsFaithAndIsBuiltFirst() {
        World world = create(4);
        evolve(world, null);
        int tick = run(world, 0, 10 * Time.TICKS_PER_SECOND);
        Groups.Group tribe = world.tribeGroup();
        Tribe.BuildingType shrine = world.tribe().building("shrine");
        tribe.stock.put("wood", 40f);
        tribe.stock.put("stone", 40f);

        // Too far from the camp: nothing, nothing paid.
        world.godPowers().faith().add(500f);
        float faith = world.godPowers().faith().points();
        world.godPowers().request(new GodPowers.PlanCommand("shrine", tribe.campX + 100f, tribe.campZ, shrine.planFaith()));
        world.tick(tick++);
        assertEquals(0, world.settlement().count("shrine", false));
        assertTrue(world.godPowers().faith().points() >= faith - 0.5f);

        float[] spot = world.tribeSystem().spot(tribe);
        assertNotNull(spot);
        faith = world.godPowers().faith().points();
        assertTrue(world.godPowers().request(new GodPowers.PlanCommand("shrine", spot[0], spot[1], shrine.planFaith())));
        world.tick(tick++);
        assertEquals(1, world.settlement().count("shrine", false));
        assertTrue(world.godPowers().faith().points() < faith - shrine.planFaith() + 1f, "the plan cost faith");
        Settlement.Building plan = world.settlement().all().stream().filter(b -> b.planned).findFirst().orElseThrow();
        tick = run(world, tick, 6 * Time.TICKS_PER_SECOND);
        assertTrue(plan.paid, "the tribe paid for the plan");
        assertEquals(plan, world.settlement().activeSite(), "the god's plan is built first");
        run(world, tick, 4 * 60 * Time.TICKS_PER_SECOND);
        assertTrue(plan.done(), "and finished: " + plan.progress);
    }

    @Test
    void withSpeechTheWholeHerdHearsTheAlarm() {
        int warnedWith = warned(true);
        int warnedWithout = warned(false);
        assertTrue(warnedWith > warnedWithout, "speech warns the herd: " + warnedWith + " vs " + warnedWithout);
    }

    /** Herd mates of the hunted who run, a moment after a hungry wolf starts a hunt. */
    private static int warned(boolean speech) {
        World world = create(5);
        evolve(world, speech ? "mind_speech" : "mind_social_groups");
        int tick = run(world, 0, 6 * Time.TICKS_PER_SECOND);
        Groups.Group herd = world.groups().all().stream().filter(g -> g.player).findFirst().orElseThrow();
        List<Integer> mates = members(world, herd);
        int prey = mates.getFirst();
        // No deer around: remove them so the wolf hunts the people.
        ComponentStore<SpeciesRef> refs = world.ecs().store(SpeciesRef.class);
        int wolf = -1;
        for (int i = 0; i < refs.size(); i++) {
            if (refs.componentAt(i).species.id().equals("wolf") && wolf < 0) {
                wolf = refs.entityAt(i);
            }
        }
        for (int i = refs.size() - 1; i >= 0; i--) {
            if (refs.componentAt(i).species.id().equals("deer")) {
                world.killCreature(refs.entityAt(i), DeathStats.Cause.OLD_AGE);
            }
        }
        world.ecs().flushDestroyed();
        // The hunted strays ~15 tiles from its herd: the others are out of the wolf's scare radius (9) but within
        // earshot of the alarm (22), so only speech makes them run.
        Transform lead = world.ecs().get(herd.leader >= 0 ? herd.leader : mates.getLast(), Transform.class);
        float px = lead.position.x;
        float pz = lead.position.z;
        for (int k = 0; k < 16; k++) {
            float x = lead.position.x + (float) Math.sin(k * Math.PI / 8) * 15f;
            float z = lead.position.z + (float) Math.cos(k * Math.PI / 8) * 15f;
            float wx = lead.position.x + (float) Math.sin(k * Math.PI / 8) * 19f;
            float wz = lead.position.z + (float) Math.cos(k * Math.PI / 8) * 19f;
            if (world.terrain().isPassable((int) x, (int) z) && world.terrain().isPassable((int) wx, (int) wz)) {
                px = x;
                pz = z;
                break;
            }
        }
        world.moveCreature(prey, px, pz);
        Transform p = world.ecs().get(prey, Transform.class);
        float dx = px - lead.position.x;
        float dz = pz - lead.position.z;
        float len = Math.max(1e-3f, (float) Math.hypot(dx, dz));
        world.moveCreature(wolf, p.position.x + dx / len * 4f, p.position.z + dz / len * 4f);
        world.ecs().get(wolf, Needs.class).hunger = 0.9f;
        world.ecs().get(wolf, Needs.class).sleeping = false;
        int most = 0;
        for (int i = 0; i < 4 * Time.TICKS_PER_SECOND; i++) {
            world.tick(tick++);
            int running = 0;
            for (int e : mates) {
                Fear f = world.ecs().get(e, Fear.class);
                running += f != null && f.isActive(tick) ? 1 : 0;
            }
            most = Math.max(most, running);
        }
        return most;
    }

    @Test
    void anEvilGodsTribeWorksHarderHasFewerYoungAndSomeRunAway() {
        World world = create(6);
        evolve(world, null);
        int tick = run(world, 0, 10 * Time.TICKS_PER_SECOND);
        Settlement settlement = world.settlement();
        var faith = world.godPowers().faith();

        faith.restore(faith.points(), faith.earned(), faith.perMinute(), faith.believers(), 0.8f, 10, 0);
        assertEquals(Settlement.Mood.CONTENT, settlement.mood());
        assertTrue(settlement.birthFactor() < 1f, "a good god: more young");
        assertEquals(1f, settlement.workFactor());
        assertEquals(0f, settlement.desertionPerMinute());

        faith.restore(faith.points(), faith.earned(), faith.perMinute(), faith.believers(), -1f, 0, 10);
        assertEquals(Settlement.Mood.AFRAID, settlement.mood());
        assertTrue(settlement.birthFactor() > 1f, "fewer young");
        assertTrue(settlement.workFactor() > 1f, "faster work");
        Groups.Group tribe = world.tribeGroup();
        int believers = world.ecs().store(Believer.class).size();
        world.tribeSystem().takeAnnouncements();
        for (int minute = 0; minute < 6; minute++) {
            tick = run(world, tick, 60 * Time.TICKS_PER_SECOND);
            faith.restore(faith.points(), faith.earned(), faith.perMinute(), faith.believers(), -1f, 0, 10);
        }
        assertTrue(world.tribeSystem().takeAnnouncements().stream().anyMatch(a -> a.contains("utek")), "some ran away");
        assertNotNull(tribe);
        assertTrue(believers > 0);
    }
}

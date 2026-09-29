package evolvia.world;

import evolvia.components.Believer;
import evolvia.components.GroupMember;
import evolvia.components.Health;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.components.UnderAttack;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.GodPowers;
import evolvia.god.HandAction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 11a DoD: the rival people live far away, evolve by a plan, keep the peace until attacked. */
class RivalTest {

    private static WorldConfig config;
    private static BiomeTable biomes;
    private static SpeciesDefinition people;
    private static ResourceTable resources;
    private static EvolutionTree tree;

    @BeforeAll
    static void loadData() {
        config = DataLoader.loadWorldConfig();
        biomes = DataLoader.loadBiomes();
        people = DataLoader.loadSpecies();
        resources = DataLoader.loadResources();
        tree = DataLoader.loadEvolutionTree(biomes);
    }

    private static World create(long seed) {
        return World.create(config, biomes, people, tree, resources, seed);
    }

    private static List<Integer> rivals(World world) {
        List<Integer> list = new ArrayList<>();
        ComponentStore<SpeciesRef> refs = world.ecs().store(SpeciesRef.class);
        for (int i = 0; i < refs.size(); i++) {
            if (refs.componentAt(i).species.isRival()) {
                list.add(refs.entityAt(i));
            }
        }
        return list;
    }

    private static Groups.Group playerHerd(World world) {
        return world.groups().all().stream().filter(g -> g.player).findFirst().orElseThrow();
    }

    @Test
    void theRivalLivesFarAwayInItsOwnHerdsAndHasNoFaith() {
        for (long seed : new long[]{1, 2, 3}) {
            World world = create(seed);
            Species rival = world.rivals().species();
            assertTrue(rival.isRival() && !rival.canBelieve() && !rival.isAnimal());
            assertEquals(rival, world.speciesById("rival"));
            List<Integer> all = rivals(world);
            int[] size = world.rivals().herdSize();
            assertTrue(all.size() >= world.rivals().herds() * size[0] && all.size() <= world.rivals().herds() * size[1]);
            assertEquals(all.size(), world.rivalCount());
            Groups.Group player = playerHerd(world);
            List<Groups.Group> herds = world.groups().all().stream().filter(g -> g.species == rival).toList();
            assertEquals(world.rivals().herds(), herds.size());
            for (Groups.Group herd : herds) {
                assertFalse(herd.player);
                assertTrue(Math.hypot(herd.homeX - player.homeX, herd.homeZ - player.homeZ) > 60, "seed " + seed + ": too close");
            }
            for (int e : all) {
                assertNull(world.ecs().get(e, Believer.class));
                assertEquals(rival.latestStage().index(), world.ecs().get(e, SpeciesRef.class).stage);
            }
            assertEquals("thick", rival.visuals().get("fur"), "its own look from the start");
            assertEquals(people.population().starting(), world.population(), "the player's people are unchanged");
        }
    }

    @Test
    void theRivalEvolvesByItsPlanAndEveryoneShowsIt() {
        World world = create(4);
        Species rival = world.rivals().species();
        assertFalse(rival.isUnlocked("body_upright"));
        Rivals.Step upright = world.rivals().plan().stream().filter(s -> s.node().equals("body_upright")).findFirst().orElseThrow();
        int tick = Math.round(upright.minute() * 60 * Time.TICKS_PER_SECOND);
        world.rivalSystem().update(world.ecs(), tick);
        assertTrue(rival.isUnlocked("body_upright"));
        assertEquals("semi", rival.visuals().get("posture"));
        for (int e : rivals(world)) {
            assertEquals(rival.latestStage().index(), world.ecs().get(e, SpeciesRef.class).stage, "the new look at once");
        }
        assertFalse(world.species().isUnlocked("body_upright"), "the player's species is another one");
    }

    @Test
    void theRivalKeepsThePeaceUntilAttacked() {
        World world = create(5);
        Groups.Group player = playerHerd(world);
        Transform home = world.ecs().get(player.leader, Transform.class);
        List<Integer> rivals = rivals(world);
        for (int e : rivals) { // the rival wanders into the player's herd
            world.moveCreature(e, home.position.x + 3f, home.position.z + 2f);
        }
        int tick = 0;
        for (; tick < 30 * Time.TICKS_PER_SECOND; tick++) {
            world.tick(tick);
            for (int e : rivals) {
                UnderAttack attack = world.ecs().get(e, UnderAttack.class);
                if (world.ecs().isAlive(e) && attack != null && attack.isActive(tick)) {
                    throw new AssertionError("a rival was attacked without an order at " + tick);
                }
            }
        }
    }

    @Test
    void anOrderedAttackOnTheRivalIsAVictory() {
        World world = create(5);
        Groups.Group player = playerHerd(world);
        int tick = 0;
        // The god orders an attack on a rival herd: a victory when one of them falls.
        int target = rivals(world).stream().filter(e -> world.ecs().get(e, GroupMember.class) != null).findFirst().orElseThrow();
        Transform leader = world.ecs().get(player.leader, Transform.class);
        world.moveCreature(target, leader.position.x + 1f, leader.position.z);
        world.ecs().get(target, Health.class).hp = 0.01f;
        world.godPowers().faith().add(100f);
        assertTrue(world.godPowers().request(new GodPowers.HandCommand(HandAction.ATTACK, player.leader, target, 0, 0)));
        int victories = world.groups().playerVictories();
        for (int end = tick + 20 * Time.TICKS_PER_SECOND; tick < end && world.groups().playerVictories() == victories; tick++) {
            world.tick(tick);
        }
        assertTrue(world.groups().playerVictories() > victories, "killing a rival is a victory");
    }

    @Test
    void theRivalCannotBeConverted() {
        World world = create(6);
        int rival = rivals(world).getFirst();
        world.godPowers().faith().add(100f);
        world.godPowers().request(new GodPowers.HandCommand(HandAction.BLESS, rival, -1, 0, 0));
        world.tick(0);
        assertNull(world.ecs().get(rival, Believer.class));
        assertNotNull(world.rivalHerd());
    }
}

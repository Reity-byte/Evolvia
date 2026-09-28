package evolvia.world;

import evolvia.components.Believer;
import evolvia.components.GroupMember;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.EvolutionTree;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 9c DoD: a small start with rival wild herds, territory fights, the god's hand, milestones, game over. */
class EarlyGameTest {

    private static WorldConfig config;
    private static BiomeTable biomes;
    private static SpeciesDefinition species;
    private static ResourceTable resources;
    private static EvolutionTree tree;

    @BeforeAll
    static void loadData() {
        config = DataLoader.loadWorldConfig();
        biomes = DataLoader.loadBiomes();
        species = DataLoader.loadSpecies();
        resources = DataLoader.loadResources();
        tree = DataLoader.loadEvolutionTree(biomes);
    }

    private static World create(long seed) {
        return World.create(config, biomes, species, tree, resources, seed);
    }

    private static int run(World world, int from, int ticks) {
        for (int tick = from; tick < from + ticks; tick++) {
            world.tick(tick);
        }
        return from + ticks;
    }

    private static Groups.Group playerHerd(World world) {
        return world.groups().all().stream().filter(g -> g.player).findFirst().orElseThrow();
    }

    private static List<Groups.Group> wildHerds(World world) {
        return world.groups().all().stream().filter(g -> !g.player).toList();
    }

    private static List<Integer> members(World world, Groups.Group group) {
        List<Integer> list = new ArrayList<>();
        ComponentStore<GroupMember> store = world.ecs().store(GroupMember.class);
        for (int i = 0; i < store.size(); i++) {
            if (store.componentAt(i).group == group.id) {
                list.add(store.entityAt(i));
            }
        }
        return list;
    }

    private static Transform at(World world, int entity) {
        return world.ecs().get(entity, Transform.class);
    }

    private static boolean hand(World world, HandAction action, int entity, int target, float x, float z, int tick) {
        world.godPowers().faith().add(100f);
        assertTrue(world.godPowers().request(new GodPowers.HandCommand(action, entity, target, x, z)));
        float before = world.godPowers().faith().points();
        world.tick(tick);
        return world.godPowers().faith().points() < before; // paid = applied
    }

    @Test
    void theGameStartsWithASmallPeopleAndWildHerdsNearby() {
        World world = create(7);
        SpeciesDefinition.Population population = species.population();
        assertEquals(population.starting(), world.believers(), "the player's people");
        assertEquals(1, world.groups().playerCount());
        assertEquals(population.wildHerds(), wildHerds(world).size());
        assertEquals(population.starting() + population.wildHerds() * population.wildHerdSize(), world.creatureCount());
        Groups.Group player = playerHerd(world);
        for (Groups.Group wild : wildHerds(world)) {
            double distance = Math.hypot(wild.homeX - player.homeX, wild.homeZ - player.homeZ);
            assertTrue(distance >= population.herdSpacing() - 0.01, "wild herd too close: " + distance);
            assertEquals(population.wildHerdSize(), members(world, wild).size());
            for (int e : members(world, wild)) {
                assertTrue(world.ecs().get(e, Believer.class) == null, "wild creatures do not believe");
            }
        }
    }

    @Test
    void thePopulationGrowsSlowly() {
        World world = create(1);
        run(world, 0, 10 * 60 * Time.TICKS_PER_SECOND);
        assertTrue(world.creatureCount() < 150, "ten minutes in, the world is still small: " + world.creatureCount());
        assertTrue(world.births().total() > 10, "but creatures are born: " + world.births().total());
    }

    @Test
    void wildHerdsFightIntrudersAndTheBeatenJoinTheWinner() {
        World world = create(5);
        Groups.Group player = playerHerd(world);
        Groups.Group wild = wildHerds(world).getFirst();
        // The player's herd walks into the wild herd's territory.
        int tick = 0;
        for (int e : members(world, player)) {
            world.moveCreature(e, wild.homeX + 3f, wild.homeZ + 3f);
        }
        int wildBefore = members(world, wild).size() + members(world, player).size();
        tick = run(world, tick, 60 * Time.TICKS_PER_SECOND);
        long attacked = world.deaths().count(DeathStats.Cause.FIGHT) + world.groups().playerVictories();
        int switched = 0;
        ComponentStore<GroupMember> store = world.ecs().store(GroupMember.class);
        for (int i = 0; i < store.size(); i++) {
            Groups.Group g = world.groups().get(store.componentAt(i).group);
            boolean believer = world.ecs().get(store.entityAt(i), Believer.class) != null;
            if (g != null && g.player != believer) {
                switched++;
            }
        }
        assertEquals(0, switched, "believing and belonging to the player's herd go together");
        assertTrue(attacked > 0 || members(world, wild).size() + members(world, player).size() != wildBefore
                        || world.ecs().store(evolvia.components.UnderAttack.class).size() > 0 || fightsHappened(world),
                "the herds fought");
    }

    private static boolean fightsHappened(World world) {
        ComponentStore<Health> healths = world.ecs().store(Health.class);
        for (int i = 0; i < healths.size(); i++) {
            if (healths.componentAt(i).hp < healths.componentAt(i).maxHp * 0.9f) {
                return true;
            }
        }
        return false;
    }

    @Test
    void anOrderedAttackMakesTheHerdGoAndFight() {
        World world = create(2);
        Groups.Group player = playerHerd(world);
        Groups.Group wild = wildHerds(world).getFirst();
        int target = members(world, wild).getFirst();
        assertTrue(hand(world, HandAction.ATTACK, player.leader, target, 0, 0, 0));
        assertTrue(player.attackOrdered(1));
        assertEquals(wild.id, player.attackGroup);
        double before = distance(world, player, wild);
        run(world, 1, 45 * Time.TICKS_PER_SECOND);
        assertTrue(distance(world, player, wild) < before * 0.6, "the herd went to fight");
        assertTrue(world.godPowers().faith().alignment() < 0f, "ordering an attack is cruel");
    }

    private static double distance(World world, Groups.Group a, Groups.Group b) {
        Transform ta = at(world, a.leader);
        double sum = 0;
        List<Integer> list = members(world, b);
        for (int e : list) {
            Transform t = at(world, e);
            sum += Math.hypot(t.position.x - ta.position.x, t.position.z - ta.position.z);
        }
        return sum / Math.max(1, list.size());
    }

    @Test
    void theHandCarriesTheLeaderAndTheHerdFollows() {
        World world = create(3);
        Groups.Group player = playerHerd(world);
        Transform leader = at(world, player.leader);
        float startX = leader.position.x;
        float startZ = leader.position.z;
        float x = leader.position.x + 25f;
        float z = leader.position.z;
        assertTrue(hand(world, HandAction.MOVE, player.leader, -1, x, z, 0));
        leader = at(world, player.leader);
        assertTrue(Math.hypot(leader.position.x - x, leader.position.z - z) < 5, "the leader was carried");
        run(world, 1, 20 * Time.TICKS_PER_SECOND);
        Transform now = at(world, player.leader);
        int near = 0;
        for (int e : members(world, player)) {
            Transform t = at(world, e);
            if (Math.hypot(t.position.x - now.position.x, t.position.z - now.position.z) < 15) {
                near++;
            }
        }
        assertTrue(near > members(world, player).size() / 2, "the herd followed its leader: " + near);
        assertTrue(Math.hypot(player.homeX - startX, player.homeZ - startZ) > 10, "the herd moved to the new place");
    }

    @Test
    void aSettledHerdStaysHome() {
        World world = create(4);
        Groups.Group player = playerHerd(world);
        Transform leader = at(world, player.leader);
        float homeX = leader.position.x;
        float homeZ = leader.position.z;
        assertTrue(hand(world, HandAction.SETTLE, player.leader, -1, 0, 0, 0));
        assertTrue(player.settled);
        run(world, 1, 3 * 60 * Time.TICKS_PER_SECOND);
        if (world.groups().get(player.id) != null && player.leader >= 0) {
            Transform now = at(world, player.leader);
            assertTrue(Math.hypot(now.position.x - homeX, now.position.z - homeZ) < species.combat().territoryRadius() * 1.5,
                    "the leader stays around home");
            assertEquals(homeX, player.homeX, 1e-4f, "home does not follow the leader");
        }
    }

    @Test
    void theHandHealsAndBlessesAndBlessedWildHerdsConvert() {
        World world = create(6);
        Groups.Group player = playerHerd(world);
        int own = members(world, player).getFirst();
        world.ecs().get(own, Health.class).hp = 0.3f;
        assertTrue(hand(world, HandAction.HEAL, own, -1, 0, 0, 0));
        Health h = world.ecs().get(own, Health.class);
        assertEquals(h.maxHp, h.hp, 0.01f);

        world.ecs().get(own, Needs.class).hunger = 0.6f;
        assertTrue(hand(world, HandAction.BLESS, own, -1, 0, 0, 1));
        assertTrue(world.ecs().get(own, Needs.class).hunger < 0.4f);

        Groups.Group wild = wildHerds(world).getFirst();
        List<Integer> wildMembers = members(world, wild);
        assertFalse(hand(world, HandAction.MOVE, wildMembers.getFirst(), -1, 10, 10, 2), "wild creatures cannot be carried");
        int tick = 3;
        for (int i = 0; i <= wildMembers.size() / 2; i++) {
            assertTrue(hand(world, HandAction.BLESS, wildMembers.get(i), -1, 0, 0, tick++));
        }
        run(world, tick, 10 * Time.TICKS_PER_SECOND);
        Groups.Group converted = world.groups().get(wild.id);
        assertNotNull(converted);
        assertTrue(converted.player, "half of the herd believes: it joins the player's people");
        for (int e : members(world, converted)) {
            assertNotNull(world.ecs().get(e, Believer.class));
        }
    }

    @Test
    void milestonesPayTheirReward() {
        World world = create(8);
        float faith = world.godPowers().faith().points();
        world.species().addPoints(100f);
        world.unlock("body_strong_legs");
        run(world, 1, 2 * Time.TICKS_PER_SECOND);
        assertTrue(world.milestones().isCompleted("first_node"));
        assertTrue(world.godPowers().faith().points() >= faith + 30f - 0.01f, "reward paid");
        assertEquals("first_node", world.milestones().takeAnnouncements().getFirst().id());
    }

    @Test
    void theGameIsLostWhenThePeopleDieOut() {
        World world = create(9);
        assertFalse(world.playerDefeated());
        ComponentStore<Believer> believers = world.ecs().store(Believer.class);
        for (int i = 0; i < believers.size(); i++) {
            world.ecs().get(believers.entityAt(i), Health.class).hp = 0f;
        }
        run(world, 1, 2);
        assertTrue(world.playerDefeated());
        assertTrue(world.creatureCount() > 0, "the wild herds live on");
    }

    @Test
    void creaturesAreTheirOwnPlayersOrWild() {
        World world = create(10);
        run(world, 0, 3 * 60 * Time.TICKS_PER_SECOND);
        ComponentStore<SpeciesRef> creatures = world.ecs().store(SpeciesRef.class);
        int believers = 0;
        for (int i = 0; i < creatures.size(); i++) {
            if (world.ecs().get(creatures.entityAt(i), Believer.class) != null) {
                believers++;
            }
        }
        assertEquals(world.population(), believers);
        assertTrue(world.creatureCount() > world.population(), "wild creatures are not the player's people");
    }
}

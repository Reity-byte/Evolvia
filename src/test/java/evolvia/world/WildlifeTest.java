package evolvia.world;

import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.AiState;
import evolvia.components.Believer;
import evolvia.components.Fear;
import evolvia.components.GroupMember;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.DivinePower;
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

/** Phase 9f DoD: prey and predators, hunting, fleeing, refuges protect, the people hunt with Carnivore. */
class WildlifeTest {

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

    private static List<Integer> ofSpecies(World world, String id) {
        List<Integer> list = new ArrayList<>();
        ComponentStore<SpeciesRef> store = world.ecs().store(SpeciesRef.class);
        for (int i = 0; i < store.size(); i++) {
            if (store.componentAt(i).species.id().equals(id)) {
                list.add(store.entityAt(i));
            }
        }
        return list;
    }

    @Test
    void wildGameIsPlacedFromTheData() {
        World world = create(1);
        for (Species kind : world.wildlife().species()) {
            int min = kind.animal().herds() * kind.animal().herdSize()[0];
            int max = kind.animal().herds() * kind.animal().herdSize()[1];
            int count = world.animalCount(kind.id());
            assertTrue(count >= min && count <= max, kind.id() + ": " + count);
            for (int e : ofSpecies(world, kind.id())) {
                assertTrue(world.terrain().isPassable((int) at(world, e).position.x, (int) at(world, e).position.z));
            }
        }
        assertEquals(world.animalCount("deer") + world.animalCount("wolf"), world.animalCount());
        assertEquals(species.population().starting() + species.population().wildHerds() * species.population().wildHerdSize(),
                world.creatureCount(), "the people and their wild kin are counted apart from the game");
        Species deer = world.speciesById("deer");
        assertTrue(deer.animal().isPrey());
        assertTrue(world.speciesById("wolf").animal().isPredator());
        assertEquals(1, deer.latestStage().index() + 1, "game does not evolve");
    }

    private static Transform at(World world, int entity) {
        return world.ecs().get(entity, Transform.class);
    }

    @Test
    void wildGameHasNoFaith() {
        World world = create(2);
        int deer = ofSpecies(world, "deer").getFirst();
        Transform t = at(world, deer);
        world.godPowers().faith().add(500f);
        world.godPowers().request(DivinePower.LIGHTNING, t.position.x + 5f, t.position.z);
        world.godPowers().request(DivinePower.RAIN, t.position.x, t.position.z);
        world.ecs().get(deer, Needs.class).thirst = 0.9f;
        world.godPowers().request(new GodPowers.HandCommand(HandAction.BLESS, deer, -1, 0, 0));
        run(world, 0, 40);
        for (String id : List.of("deer", "wolf")) {
            for (int e : ofSpecies(world, id)) {
                assertNull(world.ecs().get(e, Believer.class), id + " does not believe");
            }
        }
    }

    @Test
    void herdsAreOfOneSpecies() {
        World world = create(3);
        run(world, 0, 60 * Time.TICKS_PER_SECOND);
        ComponentStore<GroupMember> members = world.ecs().store(GroupMember.class);
        for (int i = 0; i < members.size(); i++) {
            Groups.Group group = world.groups().get(members.componentAt(i).group);
            Species kind = world.ecs().get(members.entityAt(i), SpeciesRef.class).species;
            assertEquals(kind.isAnimal() ? kind : null, group.species, "a herd has one species");
            if (kind.isAnimal()) {
                assertFalse(group.player);
            }
        }
        assertTrue(world.groups().all().stream().anyMatch(g -> g.species != null && g.species.id().equals("wolf")), "wolf packs");
    }

    @Test
    void aHungryWolfHuntsCatchesAndEatsItsPrey() {
        World world = create(4);
        int wolf = ofSpecies(world, "wolf").getFirst();
        int deer = ofSpecies(world, "deer").getFirst();
        world.moveCreature(wolf, at(world, deer).position.x + 6f, at(world, deer).position.z);
        Needs needs = world.ecs().get(wolf, Needs.class);
        needs.hunger = 0.8f;
        needs.sleeping = false;
        needs.energy = 1f;
        int deerBefore = world.animalCount("deer");
        int fights = world.deaths().count(DeathStats.Cause.FIGHT);
        boolean hunted = false;
        boolean fled = false;
        int tick = 0;
        for (; tick < 90 * Time.TICKS_PER_SECOND && world.deaths().count(DeathStats.Cause.FIGHT) == fights; tick++) {
            world.tick(tick);
            if (!world.ecs().isAlive(wolf) || world.ecs().get(wolf, AiState.class) == null) {
                throw new AssertionError("wolf died at " + tick + ": " + java.util.Arrays.stream(DeathStats.Cause.values())
                        .map(c -> c + "=" + world.deaths().count(c)).toList());
            }
            needs.hunger = 0.8f; // stays hungry
            hunted |= world.ecs().get(wolf, AiState.class).action == ActionType.HUNT;
            for (int e : ofSpecies(world, "deer")) {
                Fear fear = world.ecs().get(e, Fear.class);
                fled |= fear != null && fear.isActive(tick);
            }
        }
        assertTrue(hunted, "the wolf hunted");
        assertTrue(fled, "the prey ran");
        assertTrue(world.deaths().count(DeathStats.Cause.FIGHT) > fights, "something was caught");
        assertTrue(world.animalCount("deer") < deerBefore || world.creatureCount() < 42, "the prey died");
        // The catch is a carcass: the wolf eats it.
        float before = needs.hunger;
        for (int end = tick + 30 * Time.TICKS_PER_SECOND; tick < end; tick++) {
            world.tick(tick);
        }
        assertTrue(needs.hunger < before - 0.2f, "the wolf ate: " + before + " -> " + needs.hunger);
        assertEquals(0, world.groups().playerVictories(), "a wolf's catch is no victory of the people");
    }

    @Test
    void wolvesSleepByDayAndHuntAtNight() {
        World world = create(5);
        WorldClock clock = world.clock();
        int dayTicks = Math.round(config.time().dayLengthSeconds() * Time.TICKS_PER_SECOND);
        int noon = Math.round((0.5f - config.time().startTimeOfDay()) * dayTicks);
        run(world, 0, noon);
        assertFalse(clock.isNight(noon));
        int asleep = 0;
        List<Integer> wolves = ofSpecies(world, "wolf");
        for (int e : wolves) {
            asleep += world.ecs().get(e, Needs.class).sleeping ? 1 : 0;
        }
        assertTrue(asleep * 2 > wolves.size(), "wolves sleep at noon: " + asleep + "/" + wolves.size());

        int midnight = Math.round((1f - config.time().startTimeOfDay()) * dayTicks);
        run(world, noon, midnight - noon);
        assertTrue(clock.isNight(midnight));
        int awake = 0;
        wolves = ofSpecies(world, "wolf");
        for (int e : wolves) {
            awake += world.ecs().get(e, Needs.class).sleeping ? 0 : 1;
        }
        assertTrue(awake * 2 > wolves.size(), "wolves are up at night: " + awake + "/" + wolves.size());
    }

    @Test
    void sleepersInARefugeAreNotHunted() {
        World world = create(6);
        Species wolfKind = world.speciesById("wolf");
        Species people = world.species();
        assertTrue(ActionContext.hunts(wolfKind, people), "wolves hunt the people");
        assertTrue(ActionContext.hunts(wolfKind, world.speciesById("deer")));
        assertFalse(ActionContext.hunts(world.speciesById("deer"), wolfKind), "prey hunts nothing");
        assertFalse(ActionContext.hunts(people, world.speciesById("deer")), "the people eat no meat yet");

        // A sleeping person in a refuge, a hungry wolf next to it and nothing else around.
        Refuges.Refuge refuge = world.refuges().all().getFirst();
        int person = world.ecs().store(Believer.class).entityAt(0);
        int wolf = ofSpecies(world, "wolf").getFirst();
        world.moveCreature(person, refuge.x, refuge.z);
        world.moveCreature(wolf, refuge.x + 3f, refuge.z);
        Needs sleeper = world.ecs().get(person, Needs.class);
        for (int tick = 0; tick < 5 * Time.TICKS_PER_SECOND; tick++) {
            sleeper.sleeping = true;
            sleeper.hunger = 0f;
            sleeper.thirst = 0f;
            sleeper.energy = 0.2f;
            world.ecs().get(wolf, Needs.class).hunger = 0.8f;
            world.tick(tick);
            AiState ai = world.ecs().get(wolf, AiState.class);
            if (ai.action == ActionType.HUNT) {
                assertTrue(ai.targetEntity != person, "the sleeper in the refuge is safe");
            }
        }
    }

    @Test
    void thePeopleHuntOnceTheyEatMeatAndTheGodCanOrderAHunt() {
        World world = create(7);
        Species deer = world.speciesById("deer");
        world.species().addPoints(500f);
        for (String node : List.of("diet_omnivore", "diet_carnivore")) {
            if (world.species().tree().node(node) != null) {
                try {
                    world.unlock(node);
                } catch (IllegalStateException e) {
                    // conditions (e.g. population) not met: the ordered hunt below still works
                }
            }
        }
        boolean meat = world.species().stats().diet().meatNutrition() > 0f;
        assertEquals(meat, ActionContext.hunts(world.species(), deer));

        // The god orders the people's leader to attack a deer herd.
        Groups.Group herd = world.groups().all().stream().filter(g -> g.player).findFirst().orElseThrow();
        Groups.Group deerHerd = world.groups().all().stream().filter(g -> g.species == deer).findFirst().orElseThrow();
        int target = ofSpecies(world, "deer").stream()
                .filter(e -> world.ecs().get(e, GroupMember.class) != null
                        && world.ecs().get(e, GroupMember.class).group == deerHerd.id).findFirst().orElseThrow();
        world.godPowers().faith().add(100f);
        assertTrue(world.godPowers().request(new GodPowers.HandCommand(HandAction.ATTACK, herd.leader, target, 0, 0)));
        world.tick(0);
        assertEquals(deerHerd.id, herd.attackGroup, "a hunt on the god's order");
        assertNotNull(deerHerd.species);
    }

    @Test
    void killingGameOrAPredatorIsAVictory() {
        for (String id : List.of("deer", "wolf")) {
            World world = create(8);
            Groups.Group herd = world.groups().all().stream().filter(g -> g.player).findFirst().orElseThrow();
            int target = ofSpecies(world, id).getFirst();
            Groups.Group theirs = world.groups().get(world.ecs().get(target, GroupMember.class).group);
            Transform leader = at(world, herd.leader);
            world.moveCreature(target, leader.position.x + 1f, leader.position.z);
            world.ecs().get(target, Health.class).hp = 0.01f; // one blow is enough
            world.godPowers().faith().add(100f);
            assertTrue(world.godPowers().request(new GodPowers.HandCommand(HandAction.ATTACK, herd.leader, target, 0, 0)));
            for (int tick = 0; tick < 20 * Time.TICKS_PER_SECOND && world.groups().playerVictories() == 0; tick++) {
                world.tick(tick);
            }
            assertEquals(1, world.groups().playerVictories(), id + " killed by the people is a victory (herd " + theirs.id + ")");
        }
    }
}

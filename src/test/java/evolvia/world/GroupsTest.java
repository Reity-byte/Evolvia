package evolvia.world;

import evolvia.components.Age;
import evolvia.components.Genome;
import evolvia.components.GroupMember;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 9a DoD: after unlocking, the population splits into herds that move together. */
class GroupsTest {

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

    private static World create(SpeciesDefinition kind, long seed, boolean herds) {
        World world = World.create(config, biomes, kind, tree, resources, seed);
        if (herds) {
            world.species().addPoints(500f);
            world.unlock("mind_instincts");
            world.unlock("mind_memory");
            world.unlock("mind_social_groups");
            world.evolveEveryone();
        }
        return world;
    }

    private static int run(World world, int from, int ticks) {
        for (int tick = from; tick < from + ticks; tick++) {
            world.tick(tick);
        }
        return from + ticks;
    }

    private static int members(World world) {
        return world.ecs().store(GroupMember.class).size();
    }

    @Test
    void noHerdsWithoutTheNode() {
        World world = create(species, 3, false);
        run(world, 0, 60 * Time.TICKS_PER_SECOND);
        assertEquals(0, members(world));
        assertEquals(0, world.groups().count());
    }

    @Test
    void herdsFormWithinLimitsAndHaveLeaders() {
        World world = create(species, 3, true);
        run(world, 0, 60 * Time.TICKS_PER_SECOND);
        SpeciesDefinition.Groups rules = species.groups();
        assertTrue(members(world) > world.population() * 0.8, members(world) + " of " + world.population() + " in herds");
        assertTrue(world.groups().count() >= world.population() / rules.maxSize());
        Map<Integer, Integer> sizes = sizes(world);
        for (Groups.Group group : world.groups().all()) {
            int size = sizes.getOrDefault(group.id, 0);
            assertTrue(size >= rules.minSize() && size <= rules.maxSize() + 5, "herd " + group.id + " has " + size);
            GroupMember leader = world.ecs().get(group.leader, GroupMember.class);
            assertNotNull(leader, "leader is alive");
            assertEquals(group.id, leader.group, "leader belongs to its herd");
        }
    }

    private static Map<Integer, Integer> sizes(World world) {
        Map<Integer, Integer> sizes = new HashMap<>();
        ComponentStore<GroupMember> store = world.ecs().store(GroupMember.class);
        for (int i = 0; i < store.size(); i++) {
            sizes.merge(store.componentAt(i).group, 1, Integer::sum);
        }
        return sizes;
    }

    @Test
    void herdsMoveTogether() {
        World world = create(species, 5, true);
        int tick = run(world, 0, 60 * Time.TICKS_PER_SECOND);
        Map<Integer, float[]> start = leaderPositions(world);
        double own = 0;
        double others = 0;
        double near = 0;
        for (int minute = 0; minute < 4; minute++) {
            tick = run(world, tick, 60 * Time.TICKS_PER_SECOND);
            float[] d = distancesToLeaders(world);
            own += d[0] / 4;
            others += d[1] / 4;
            near += d[2] / 4;
        }
        double travelled = 0;
        int leaders = 0;
        for (Map.Entry<Integer, float[]> e : leaderPositions(world).entrySet()) {
            float[] from = start.get(e.getKey());
            if (from != null) {
                travelled += Math.hypot(e.getValue()[0] - from[0], e.getValue()[1] - from[1]);
                leaders++;
            }
        }
        travelled /= Math.max(1, leaders);
        assertTrue(own < others * 0.5, "members stay with their own leader: " + own + " vs other leaders " + others);
        assertTrue(near > 0.5, "most members are close to their leader: " + near);
        assertTrue(travelled > own, "herds move (" + travelled + ") while staying together (" + own + ")");
    }

    /** Average distance of members to their own leader, to the other herds' leaders, and the share within 2x follow distance. */
    private static float[] distancesToLeaders(World world) {
        ComponentStore<GroupMember> store = world.ecs().store(GroupMember.class);
        double own = 0;
        double others = 0;
        int n = 0;
        int otherCount = 0;
        int near = 0;
        float nearDistance = species.groups().followDistance() * 2f;
        for (int i = 0; i < store.size(); i++) {
            Groups.Group group = world.groups().get(store.componentAt(i).group);
            if (group == null || group.leader < 0 || group.leader == store.entityAt(i)) {
                continue;
            }
            Transform member = world.ecs().get(store.entityAt(i), Transform.class);
            for (Groups.Group other : world.groups().all()) {
                Transform leader = world.ecs().get(other.leader, Transform.class);
                if (leader == null) {
                    continue;
                }
                double d = Math.hypot(member.position.x - leader.position.x, member.position.z - leader.position.z);
                if (other == group) {
                    own += d;
                    n++;
                    if (d <= nearDistance) {
                        near++;
                    }
                } else {
                    others += d;
                    otherCount++;
                }
            }
        }
        return new float[]{(float) (own / n), (float) (others / otherCount), near / (float) n};
    }

    private static Map<Integer, float[]> leaderPositions(World world) {
        Map<Integer, float[]> positions = new HashMap<>();
        for (Groups.Group group : world.groups().all()) {
            Transform t = world.ecs().get(group.leader, Transform.class);
            if (t != null) {
                positions.put(group.id * 100_000 + group.leader, new float[]{t.position.x, t.position.z});
            }
        }
        return positions;
    }

    @Test
    void aDeadLeaderIsReplacedByAMember() {
        World world = create(species, 7, true);
        int tick = run(world, 0, 30 * Time.TICKS_PER_SECOND);
        Groups.Group group = world.groups().all().iterator().next();
        int oldLeader = group.leader;
        Transform t = world.ecs().get(oldLeader, Transform.class);
        world.lightning(t.position.x, t.position.z, 0.01f, 1, 0.02f, 1, 1f, tick); // kills exactly the leader
        assertEquals(1, world.deaths().count(DeathStats.Cause.LIGHTNING));
        run(world, tick, 6 * Time.TICKS_PER_SECOND);
        if (world.groups().get(group.id) != null) {
            // The dead leader's ID may already belong to a newborn, so check the new leader is a grown-up member.
            assertNotEquals(-1, group.leader);
            assertEquals(group.id, world.ecs().get(group.leader, GroupMember.class).group);
            int adult = SpeciesDefinition.secondsToTicks(species.reproduction().adultAgeSeconds());
            assertTrue(world.ecs().get(group.leader, Age.class).ageTicks >= adult, "the new leader is an adult");
        }
    }

    @Test
    void largeHerdsSplit() {
        SpeciesDefinition.Groups g = species.groups();
        SpeciesDefinition small = species.withGroups(new SpeciesDefinition.Groups(g.updateSeconds(), g.joinRadius(),
                5, 3, 8, g.followDistance(), g.leaveDistance(), g.leaveSeconds(), g.followScore(),
                g.forageRadius(), g.urgentNeed()));
        World world = create(small, 3, true);
        run(world, 0, 90 * Time.TICKS_PER_SECOND);
        for (Map.Entry<Integer, Integer> e : sizes(world).entrySet()) {
            assertTrue(e.getValue() <= 8 + 3, "herd " + e.getKey() + " has " + e.getValue()); // + births since the last update
        }
        assertTrue(world.groups().count() >= world.population() / 12);
    }

    @Test
    void youngAreBornIntoTheirParentsHerd() {
        World world = create(species, 9, true);
        int tick = run(world, 0, 30 * Time.TICKS_PER_SECOND);
        ComponentStore<GroupMember> store = world.ecs().store(GroupMember.class);
        int parentA = store.entityAt(0);
        int parentB = store.entityAt(1);
        Transform t = world.ecs().get(parentA, Transform.class);
        int before = world.population();
        world.births().add(parentA, parentB, t.position.x, t.position.z);
        world.tick(tick + 1); // not a herd update tick
        assertTrue(world.population() > before);
        int child = -1;
        ComponentStore<Genome> genomes = world.ecs().store(Genome.class);
        for (int i = 0; i < genomes.size(); i++) {
            if (genomes.componentAt(i).generation > 0) {
                child = genomes.entityAt(i);
            }
        }
        assertEquals(store.get(parentA).group, world.ecs().get(child, GroupMember.class).group);
    }

    @Test
    void herdsShareWhereTheyDrank() {
        World world = create(species, 11, true);
        run(world, 0, 4 * 60 * Time.TICKS_PER_SECOND);
        long knowing = world.groups().all().stream().filter(g -> g.knowsWater).count();
        assertTrue(knowing >= world.groups().count() / 2, knowing + " of " + world.groups().count() + " herds know water");
    }
}

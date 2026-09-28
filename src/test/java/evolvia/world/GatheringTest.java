package evolvia.world;

import evolvia.components.Carrying;
import evolvia.components.ResourceNode;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 9g DoD: trees and rocks, the Tools node, gathering into the herd's camp. */
class GatheringTest {

    /** The Tools node and the chain leading to it. */
    static final List<String> TOOLS = List.of("body_strong_legs", "body_upright", "body_bipedal", "body_hands", "mind_tools");

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

    /** Unlocks the Tools chain and gives everyone the latest stage. */
    static void giveTools(World world) {
        world.species().addPoints(1000f);
        for (String node : TOOLS) {
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

    private static Groups.Group playerHerd(World world) {
        return world.groups().all().stream().filter(g -> g.player).findFirst().orElseThrow();
    }

    @Test
    void treesGrowInForestsAndRocksLieOnLand() {
        World world = create(1);
        ComponentStore<ResourceNode> nodes = world.ecs().store(ResourceNode.class);
        int forestTrees = 0;
        int trees = 0;
        int rocks = 0;
        for (int i = 0; i < nodes.size(); i++) {
            ResourceNode node = nodes.componentAt(i);
            if (node.type.kind() != ResourceKind.MATERIAL) {
                continue;
            }
            Transform t = world.ecs().get(nodes.entityAt(i), Transform.class);
            int tx = (int) t.position.x;
            int tz = (int) t.position.z;
            assertTrue(world.terrain().isPassable(tx, tz), "on land");
            if ("wood".equals(node.type.material())) {
                trees++;
                forestTrees += world.terrain().biome(tx, tz).id().equals("forest") ? 1 : 0;
            } else {
                rocks++;
            }
        }
        assertTrue(trees > 100 && rocks > 10, "trees " + trees + ", rocks " + rocks);
        assertTrue(forestTrees * 2 > trees, "most trees are in forests: " + forestTrees + "/" + trees);
        assertEquals(trees + rocks, world.resourceGrid(ResourceKind.MATERIAL).size());
    }

    @Test
    void withoutToolsNobodyGathers() {
        World world = create(2);
        run(world, 0, 90 * Time.TICKS_PER_SECOND);
        assertEquals(0, world.ecs().store(Carrying.class).size());
        assertTrue(world.groups().all().stream().noneMatch(g -> g.hasCamp));
    }

    @Test
    void withToolsTheHerdFillsItsCamp() {
        World world = create(3);
        giveTools(world);
        Groups.Group herd = playerHerd(world);
        boolean seenCarrying = false;
        int tick = 0;
        for (; tick < 4 * 60 * Time.TICKS_PER_SECOND; tick++) {
            world.tick(tick);
            seenCarrying |= world.ecs().store(Carrying.class).size() > 0;
        }
        assertTrue(seenCarrying, "loads are carried (and drawn on the carrier's back)");
        assertTrue(herd.hasCamp, "the first delivery founds the camp");
        assertTrue(herd.settled, "the herd settles at its camp");
        assertEquals(herd.campX, herd.homeX, 1e-4f);
        assertTrue(herd.stockTotal() >= 5f, "the stock grows: " + herd.stock);
        assertTrue(herd.stock("wood") > 0f, "wood: " + herd.stock);
    }

    @Test
    void aFullCampStopsTheGathering() {
        World world = create(4);
        giveTools(world);
        Groups.Group herd = playerHerd(world);
        herd.hasCamp = true;
        herd.campX = herd.homeX;
        herd.campZ = herd.homeZ;
        herd.settled = true;
        float cap = world.tribe().gathering().stockCap();
        herd.stock.put("wood", cap);
        herd.stock.put("stone", cap);
        run(world, 0, 60 * Time.TICKS_PER_SECOND);
        assertEquals(cap, herd.stock("wood"), 1e-4f, "no more wood");
        assertEquals(cap, herd.stock("stone"), 1e-4f, "no more stone");
    }

    @Test
    void fireBurnsTreesButNotRocks() {
        World world = create(5);
        ComponentStore<ResourceNode> nodes = world.ecs().store(ResourceNode.class);
        int treeEntity = -1;
        for (int i = 0; i < nodes.size() && treeEntity < 0; i++) {
            ResourceNode node = nodes.componentAt(i);
            Transform t = world.ecs().get(nodes.entityAt(i), Transform.class);
            if ("wood".equals(node.type.material())
                    && world.nature().flammability((int) t.position.x, (int) t.position.z) > 0f) {
                treeEntity = nodes.entityAt(i);
            }
        }
        assertTrue(treeEntity >= 0);
        Transform t = world.ecs().get(treeEntity, Transform.class);
        assertTrue(world.nature().ignite((int) t.position.x, (int) t.position.z, 0));
        run(world, 0, 2 * Time.TICKS_PER_SECOND);
        assertEquals(0f, world.ecs().get(treeEntity, ResourceNode.class).amount, 1e-4f, "the tree burnt");
    }
}

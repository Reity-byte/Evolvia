package evolvia.world;

import evolvia.components.Carrying;
import evolvia.components.GroupMember;
import evolvia.components.SpeciesRef;
import evolvia.core.Time;
import evolvia.ecs.ComponentStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 11c DoD: raids come after both tribes exist, steal and go home; lightning razes; the rival can be destroyed. */
class RaidTest {

    @BeforeAll
    static void loadData() {
        RivalTribeTest.loadData();
    }

    private static List<Integer> members(World world, int group) {
        List<Integer> list = new ArrayList<>();
        ComponentStore<GroupMember> store = world.ecs().store(GroupMember.class);
        for (int i = 0; i < store.size(); i++) {
            if (store.componentAt(i).group == group) {
                list.add(store.entityAt(i));
            }
        }
        return list;
    }

    /** Both tribes, the clock at the rival's tribe minute, the raid timer started; returns the tick. */
    private static int ready(World world) {
        int tick = Math.round(world.rivals().tribeMinute() * 60 * Time.TICKS_PER_SECOND);
        world.restoreTick(tick);
        world.rivalTribeSystem().update(world.ecs(), tick); // its other herd joins the tribe
        for (int e : members(world, world.rivalTribe().id)) { // a fresh world: after half an hour they are grown up
            evolvia.components.Age age = world.ecs().get(e, evolvia.components.Age.class);
            age.ageTicks = Math.max(age.ageTicks, 8000);
        }
        world.raidSystem().update(world.ecs(), tick);
        assertTrue(world.raidSystem().nextRaidTick() > tick, "the first raid is planned");
        assertEquals(0, world.raidSystem().party(), "not yet");
        return tick;
    }

    @Test
    void aRaidComesAfterBothTribesExist() {
        World world = RivalTribeTest.create(11);
        world.raidSystem().update(world.ecs(), 20 * Time.TICKS_PER_SECOND);
        assertEquals(0, world.raidSystem().nextRaidTick(), "no raids without tribes");

        World both = RivalTribeTest.withBothTribes(12);
        int tick = ready(both);
        int start = both.raidSystem().nextRaidTick();
        both.raidSystem().update(both.ecs(), start);
        int party = both.raidSystem().party();
        assertTrue(party != 0, "the raid started");
        Groups.Group band = both.groups().get(party);
        assertTrue(band.raid && band.settled);
        assertEquals(both.rivals().species(), band.species);
        assertEquals(both.tribeGroup().id, band.attackGroup, "it attacks the player's tribe");
        assertEquals(both.tribeGroup().campX, band.homeX, 1e-4);
        assertTrue(members(both, party).size() >= both.rivals().raids().minRaiders());
        assertTrue(both.takeRivalAnnouncements().stream().anyMatch(a -> a.startsWith("Nájezd!")));
        assertTrue(tick < start);
    }

    @Test
    void raidersStealAtTheCampAndGoHomeWithTheLoot() {
        World world = RivalTribeTest.withBothTribes(13);
        ready(world);
        int tick = world.raidSystem().nextRaidTick();
        world.raidSystem().update(world.ecs(), tick);
        Groups.Group band = world.groups().get(world.raidSystem().party());
        Groups.Group player = world.tribeGroup();
        player.stock.put("wood", 30f);
        List<Integer> raiders = members(world, band.id);
        for (int raider : raiders) {
            world.moveCreature(raider, player.campX + 1f, player.campZ);
        }
        tick += Time.TICKS_PER_SECOND;
        world.raidSystem().update(world.ecs(), tick);
        for (int raider : raiders) {
            assertNotNull(world.ecs().get(raider, Carrying.class), "every raider at the camp took a load");
        }
        assertTrue(player.stock("wood") < 30f, "stolen");

        // Everyone carries loot: the party turns home.
        tick += Time.TICKS_PER_SECOND;
        world.raidSystem().update(world.ecs(), tick);
        Groups.Group rival = world.rivalTribe();
        assertEquals(0, band.attackGroup, "no more fighting");
        assertEquals(rival.campX, band.homeX, 1e-4, "home");
        float before = rival.stockTotal();
        for (int raider : raiders) {
            world.moveCreature(raider, rival.campX + 1f, rival.campZ);
        }
        for (int end = tick + 30 * Time.TICKS_PER_SECOND; tick < end; tick++) {
            world.tick(tick);
        }
        assertNull(world.groups().get(band.id), "the party joined its tribe again");
        assertEquals(0, world.raidSystem().party());
        assertTrue(world.raidSystem().nextRaidTick() > tick - 30 * Time.TICKS_PER_SECOND, "the next raid is planned");
        assertTrue(rival.stockTotal() > before, "the loot is in its camp: " + before + " -> " + rival.stockTotal());
    }

    @Test
    void lightningRazesTheRivalsBuildingsAndTheRivalCanBeDestroyed() {
        World world = RivalTribeTest.withBothTribes(14);
        Groups.Group rival = world.rivalTribe();
        Tribe.BuildingType fire = world.tribe().building("fire");
        Settlement.Building building = world.rivalSettlement().add(fire, rival.campX + 5f, rival.campZ, false);
        building.paid = true;
        building.progress = 1f;
        world.lightning(building.x, building.z, 3f, 3, 8f, 20, 10f, 100);
        assertTrue(world.rivalSettlement().all().isEmpty(), "the building burnt down");
        assertTrue(world.takeRivalAnnouncements().stream().anyMatch(a -> a.contains("Blesk")));

        ComponentStore<SpeciesRef> refs = world.ecs().store(SpeciesRef.class);
        for (int i = refs.size() - 1; i >= 0; i--) {
            if (refs.componentAt(i).species.isRival()) {
                world.killCreature(refs.entityAt(i), DeathStats.Cause.FIGHT);
            }
        }
        world.ecs().flushDestroyed();
        int tick = 200 * Time.TICKS_PER_SECOND;
        world.raidSystem().update(world.ecs(), tick);
        assertTrue(world.raidSystem().defeated());
        assertEquals(0, world.rivalCount());
        assertTrue(world.takeRivalAnnouncements().stream().anyMatch(a -> a.contains("vyhlazeni")));
        world.tick(tick);
        world.tick(tick + 20);
        assertTrue(world.milestones().isCompleted("rival_defeated"), "milestone");
        assertFalse(world.playerDefeated(), "the game goes on");
    }
}

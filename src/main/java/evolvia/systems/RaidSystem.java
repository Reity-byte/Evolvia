package evolvia.systems;

import evolvia.components.Age;
import evolvia.components.Carrying;
import evolvia.components.GroupMember;
import evolvia.components.Health;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Groups;
import evolvia.world.Settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Raids of the rival (phase 11c), once per game second. Once both peoples have a tribe, the rival sends a war
 * party after {@code firstRaidMinutes} and then every {@code raidMinutes}: a share of its healthy adults (growing
 * with every raid) becomes its own herd whose home is the player's camp and which is ordered to attack the
 * player's tribe. Raiders who reach the camp take a load of its stock. The party turns home after
 * {@code maxSeconds}, when it lost {@code retreatShare} of its members or when everyone carries loot; back at its
 * camp it joins its tribe again (and delivers the loot). When no rival is left, the rival is destroyed.
 */
public final class RaidSystem implements GameSystem {

    /**
     * @param firstRaidMinutes the first raid this long after both tribes exist
     * @param raidMinutes      then a raid every this many minutes
     * @param share            share of the rival tribe's healthy adults in the first raid
     * @param shareGrowth      more with every raid
     * @param maxShare         at most this share
     * @param minRaiders       fewer adults than this plus two: no raid (it waits)
     * @param maxSeconds       a raid lasts at most this long
     * @param stealPerRaider   how much a raider carries off
     * @param retreatShare     the party turns home when this share of it has fallen
     * @param stealRadius      raiders this close to the player's camp take loot
     */
    public record Rules(float firstRaidMinutes, float raidMinutes, float share, float shareGrowth, float maxShare,
                        int minRaiders, float maxSeconds, float stealPerRaider, float retreatShare, float stealRadius) {
    }

    /** Saved raid state. */
    public record State(int nextRaidTick, int party, int raidStartTick, int raids, int partySize, int alive,
                        boolean retreating, float stolen, boolean defeated) {
    }

    /** What the raid system needs from the world. */
    public interface Access {
        Groups groups();

        Species rival();

        /** The player's tribe herd, or null. */
        Groups.Group playerTribe();

        /** The rival's tribe herd, or null. */
        Groups.Group rivalTribe();

        Settlement rivalSettlement();

        int rivalCount();

        /** The rival's buildings are gone: its huts are nobody's (phase 11c). */
        void razeRival();
    }

    private final Rules rules;
    private final Access world;
    private final List<String> announcements = new ArrayList<>();
    private int nextRaidTick;
    private int party;
    private int raidStartTick;
    private int raids;
    private int partySize;
    private int alive;
    private boolean retreating;
    private float stolen;
    private boolean defeated;

    public RaidSystem(Rules rules, Access world) {
        this.rules = rules;
        this.world = world;
    }

    public Rules rules() {
        return rules;
    }

    /** The rival is destroyed. */
    public boolean defeated() {
        return defeated;
    }

    /** The war party's herd id, or 0 when there is no raid. */
    public int party() {
        return party;
    }

    public int raids() {
        return raids;
    }

    /** Tick of the next raid (0 = not planned yet). */
    public int nextRaidTick() {
        return nextRaidTick;
    }

    public List<String> takeAnnouncements() {
        List<String> taken = List.copyOf(announcements);
        announcements.clear();
        return taken;
    }

    @Override
    public void update(EcsWorld ecs, int tick) {
        if (defeated || tick % Time.TICKS_PER_SECOND != 0) {
            return;
        }
        if (world.rivalCount() == 0) {
            defeated = true;
            party = 0;
            world.razeRival();
            announcements.add("Hrubci byli vyhlazeni! Krajina patří tvému lidu.");
            return;
        }
        if (party != 0) {
            manage(ecs, tick);
            return;
        }
        Groups.Group player = world.playerTribe();
        Groups.Group rival = world.rivalTribe();
        if (player == null || rival == null || !player.hasCamp || !rival.hasCamp) {
            return;
        }
        if (nextRaidTick == 0) {
            nextRaidTick = tick + minutes(rules.firstRaidMinutes());
        }
        if (tick >= nextRaidTick) {
            start(ecs, tick, player, rival);
        }
    }

    private static int minutes(float minutes) {
        return Math.round(minutes * 60 * Time.TICKS_PER_SECOND);
    }

    private List<Integer> members(EcsWorld ecs, int groupId) {
        List<Integer> list = new ArrayList<>();
        ComponentStore<GroupMember> store = ecs.store(GroupMember.class);
        for (int i = 0; i < store.size(); i++) {
            if (store.componentAt(i).group == groupId) {
                list.add(store.entityAt(i));
            }
        }
        list.sort(null);
        return list;
    }

    private void start(EcsWorld ecs, int tick, Groups.Group player, Groups.Group rival) {
        List<Integer> fit = new ArrayList<>();
        for (int entity : members(ecs, rival.id)) {
            Age age = ecs.get(entity, Age.class);
            Health health = ecs.get(entity, Health.class);
            SpeciesDefinition stats = ecs.get(entity, SpeciesRef.class).stats();
            if (age != null && health != null && age.ageTicks >= SpeciesDefinition.secondsToTicks(stats.reproduction().adultAgeSeconds())
                    && health.hp >= 0.7f * health.maxHp) {
                fit.add(entity);
            }
        }
        float share = Math.min(rules.maxShare(), rules.share() + rules.shareGrowth() * raids);
        int count = Math.min(fit.size() - 2, Math.max(rules.minRaiders(), Math.round(fit.size() * share)));
        if (count < rules.minRaiders()) {
            nextRaidTick = tick + minutes(2f); // too few: it waits
            return;
        }
        Groups.Group band = world.groups().create();
        band.species = world.rival();
        band.raid = true;
        band.settled = true;
        band.homeX = player.campX;
        band.homeZ = player.campZ;
        band.attackGroup = player.id;
        band.attackUntilTick = tick + SpeciesDefinition.secondsToTicks(rules.maxSeconds());
        for (int i = 0; i < count; i++) {
            ecs.get(fit.get(i), GroupMember.class).group = band.id;
        }
        band.leader = fit.getFirst();
        band.size = count;
        rival.size -= count;
        party = band.id;
        raidStartTick = tick;
        partySize = count;
        alive = count;
        retreating = false;
        stolen = 0f;
        raids++;
        announcements.add(String.format(Locale.ROOT, "Nájezd! %d Hrubců táhne na tvůj tábor.", count));
    }

    private void manage(EcsWorld ecs, int tick) {
        Groups.Group band = world.groups().get(party);
        if (band == null) { // back home and joined its tribe, or everyone fell
            end(tick);
            return;
        }
        List<Integer> raiders = members(ecs, band.id);
        alive = raiders.size();
        Groups.Group player = world.playerTribe();
        Groups.Group rival = world.rivalTribe();
        if (!retreating && player != null && player.hasCamp) {
            steal(ecs, raiders, player);
        }
        boolean loaded = !raiders.isEmpty() && raiders.stream().allMatch(e -> ecs.get(e, Carrying.class) != null);
        boolean beaten = alive <= partySize * (1f - rules.retreatShare());
        boolean late = tick - raidStartTick >= SpeciesDefinition.secondsToTicks(rules.maxSeconds());
        if (!retreating && (loaded || beaten || late || player == null)) {
            retreating = true;
            band.attackGroup = 0;
            band.attackUntilTick = 0;
            if (rival != null) {
                band.homeX = rival.campX; // home: its tribe merges it back on arrival
                band.homeZ = rival.campZ;
            } else {
                band.raid = false; // no tribe to return to: an ordinary herd again
                band.settled = false;
            }
        }
        if (retreating && rival == null) {
            band.raid = false;
            end(tick);
        }
    }

    /** Raiders at the player's camp carry off a load of its largest stock. */
    private void steal(EcsWorld ecs, List<Integer> raiders, Groups.Group player) {
        for (int raider : raiders) {
            if (ecs.get(raider, Carrying.class) != null) {
                continue;
            }
            Transform t = ecs.get(raider, Transform.class);
            if (t == null || Math.hypot(t.position.x - player.campX, t.position.z - player.campZ) > rules.stealRadius()) {
                continue;
            }
            String material = null;
            for (Map.Entry<String, Float> entry : player.stock.entrySet()) {
                if (entry.getValue() >= 1f && (material == null || entry.getValue() > player.stock(material))) {
                    material = entry.getKey();
                }
            }
            if (material == null) {
                return; // nothing left to take
            }
            float amount = Math.min(rules.stealPerRaider(), player.stock(material));
            player.stock.put(material, player.stock(material) - amount);
            ecs.add(raider, new Carrying(material, amount));
            stolen += amount;
        }
    }

    private void end(int tick) {
        int fallen = Math.max(0, partySize - alive);
        announcements.add(String.format(Locale.ROOT, "Nájezd skončil: Hrubci odnesli %.0f surovin, padlo jich %d z %d.",
                stolen, fallen, partySize));
        party = 0;
        retreating = false;
        nextRaidTick = tick + minutes(rules.raidMinutes());
    }

    public State state() {
        return new State(nextRaidTick, party, raidStartTick, raids, partySize, alive, retreating, stolen, defeated);
    }

    public void restore(State state) {
        nextRaidTick = state.nextRaidTick();
        party = state.party();
        raidStartTick = state.raidStartTick();
        raids = state.raids();
        partySize = state.partySize();
        alive = state.alive();
        retreating = state.retreating();
        stolen = state.stolen();
        defeated = state.defeated();
    }
}

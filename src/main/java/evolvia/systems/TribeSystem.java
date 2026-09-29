package evolvia.systems;

import evolvia.components.Age;
import evolvia.components.Believer;
import evolvia.components.Fear;
import evolvia.components.GroupMember;
import evolvia.components.Role;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Time;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Groups;
import evolvia.world.Refuges;
import evolvia.world.Settlement;
import evolvia.world.Terrain;
import evolvia.world.Tribe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The tribe (phase 9h), every {@code tribe.roleSeconds}: founds it from the largest herd of the people once the
 * species has the Tribe node, lets other herds of the people join, hands out roles (builders and gatherers),
 * pays for and chooses buildings, turns finished shelters into refuges, and under an evil god lets members run
 * away. Everything in ID order, random numbers from the world's generator.
 */
public final class TribeSystem implements GameSystem {

    /**
     * Whose tribe this is (phase 11b): the player's people or the rival.
     *
     * @param species   the people's species
     * @param player    the player's people (herds of the player, faith, the god's plans, desertion)
     * @param canFound  whether the tribe may be founded at this tick
     * @param known     buildings the tribe knows how to build
     * @param founded   called once when the tribe is founded
     * @param message   announcement when it is founded
     */
    public record People(Species species, boolean player, java.util.function.IntPredicate canFound,
                         java.util.function.Predicate<Tribe.BuildingType> known, Runnable founded, String message) {

        /** A herd of these people. */
        boolean owns(Groups.Group group) {
            return player ? group.player && group.species == null : group.species == species;
        }
    }

    private final Groups groups;
    private final Species people;
    private final People owner;
    private final Settlement settlement;
    private final Refuges refuges;
    private final Terrain terrain;
    private final Random random;
    private final List<String> announcements = new ArrayList<>();
    private boolean known(Tribe.BuildingType type) {
        return owner.known().test(type);
    }

    public TribeSystem(Groups groups, People owner, Settlement settlement, Refuges refuges, Terrain terrain, Random random) {
        this.groups = groups;
        this.people = owner.species();
        this.owner = owner;
        this.settlement = settlement;
        this.refuges = refuges;
        this.terrain = terrain;
        this.random = random;
    }

    /** Messages for the player since the last call. */
    public List<String> takeAnnouncements() {
        List<String> list = List.copyOf(announcements);
        announcements.clear();
        return list;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        Tribe.Rules rules = settlement.config().tribe();
        int interval = Math.max(1, SpeciesDefinition.secondsToTicks(rules.roleSeconds()));
        if (tick % interval != 0) {
            return;
        }
        Groups.Group tribe = tribe();
        if (tribe == null) {
            tribe = found(rules, tick);
            if (tribe == null) {
                clearRoles(world, -1);
                return;
            }
        }
        if (!tribe.hasCamp) {
            tribe.hasCamp = true;
            tribe.campX = tribe.homeX;
            tribe.campZ = tribe.homeZ;
            tribe.settled = true;
        }
        join(world, tribe, rules);
        List<Integer> members = members(world, tribe.id);
        pay(tribe);
        choose(tribe, members.size());
        finish();
        roles(world, tribe, members, rules);
        if (owner.player() && tick % (60 * Time.TICKS_PER_SECOND) == 0) {
            desert(world, members, tick);
        }
    }

    /** The tribe's herd, or null. */
    public Groups.Group tribe() {
        for (Groups.Group group : groups.all()) {
            if (group.tribe && owner.owns(group)) {
                return group;
            }
        }
        return null;
    }

    private Groups.Group found(Tribe.Rules rules, int tick) {
        if (!owner.canFound().test(tick)) {
            return null;
        }
        Groups.Group largest = null;
        for (Groups.Group group : groups.all()) {
            if (owner.owns(group) && (largest == null || group.size > largest.size)) {
                largest = group;
            }
        }
        if (largest != null) {
            largest.tribe = true;
            owner.founded().run();
            announcements.add(owner.message());
        }
        return largest;
    }

    /** Herds of the people whose leader comes close to the camp join the tribe. */
    private void join(EcsWorld world, Groups.Group tribe, Tribe.Rules rules) {
        ComponentStore<Transform> transforms = world.store(Transform.class);
        ComponentStore<GroupMember> members = world.store(GroupMember.class);
        for (Groups.Group other : new ArrayList<>(groups.all())) {
            if (other == tribe || !owner.owns(other) || other.leader < 0 || (other.raid && other.attackGroup != 0)) {
                continue; // a war party joins again only on its way home (phase 11c)
            }
            Transform leader = transforms.get(other.leader);
            if (leader == null || Math.hypot(leader.position.x - tribe.campX, leader.position.z - tribe.campZ) > rules.joinRadius()) {
                continue;
            }
            for (int i = 0; i < members.size(); i++) {
                if (members.componentAt(i).group == other.id) {
                    members.componentAt(i).group = tribe.id;
                }
            }
            for (Map.Entry<String, Float> entry : other.stock.entrySet()) {
                tribe.stock.merge(entry.getKey(), entry.getValue(), Float::sum);
            }
            tribe.size += other.size;
            groups.remove(other.id);
        }
    }

    private static List<Integer> members(EcsWorld world, int groupId) {
        List<Integer> list = new ArrayList<>();
        ComponentStore<GroupMember> store = world.store(GroupMember.class);
        for (int i = 0; i < store.size(); i++) {
            if (store.componentAt(i).group == groupId) {
                list.add(store.entityAt(i));
            }
        }
        list.sort(null);
        return list;
    }

    private static boolean affords(Groups.Group tribe, Tribe.BuildingType type) {
        for (Map.Entry<String, Float> cost : type.cost().entrySet()) {
            if (tribe.stock(cost.getKey()) < cost.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static void payFor(Groups.Group tribe, Tribe.BuildingType type) {
        for (Map.Entry<String, Float> cost : type.cost().entrySet()) {
            tribe.stock.put(cost.getKey(), tribe.stock(cost.getKey()) - cost.getValue());
        }
    }

    /** The first waiting site (the god's plans first) is paid for once the stock allows. */
    private void pay(Groups.Group tribe) {
        List<Settlement.Building> waiting = settlement.unpaid();
        if (!waiting.isEmpty() && affords(tribe, waiting.getFirst().type)) {
            payFor(tribe, waiting.getFirst().type);
            waiting.getFirst().paid = true;
        }
    }

    /** With nothing to build, the tribe starts what it needs most and can pay for. */
    private void choose(Groups.Group tribe, int people) {
        if (settlement.activeSite() != null || !settlement.unpaid().isEmpty()) {
            return;
        }
        Tribe.BuildingType type = need(tribe, people);
        if (type == null || !affords(tribe, type)) {
            return;
        }
        float[] spot = spot(tribe);
        if (spot == null) {
            return;
        }
        payFor(tribe, type);
        settlement.add(type, spot[0], spot[1], false).paid = true;
    }

    /**
     * What the tribe needs most: a fire, then a first shelter, a store once the stock is nearly full, a shrine,
     * and then more shelters (one per {@code value} people).
     */
    private Tribe.BuildingType need(Groups.Group tribe, int people) {
        Tribe.Config config = settlement.config();
        for (int pass = 0; pass < 2; pass++) {
            for (Tribe.BuildingType type : config.buildings()) {
                if (!known(type)) {
                    continue; // the tribe does not know how to build it yet
                }
                int count = settlement.count(type.id(), false);
                boolean wanted = switch (type.effect()) {
                    case "warmth", "faith" -> count == 0;
                    case "refuge" -> pass == 0 ? count == 0
                            : count < Math.max(1, (int) Math.ceil(people / Math.max(1f, type.value())));
                    case "storage" -> count == 0
                            && maxStock(tribe) >= 0.8f * config.gathering().stockCap() * settlement.storageFactor();
                    default -> false;
                };
                if (wanted && (pass == 1 || !"refuge".equals(type.effect()) || count == 0)) {
                    return type;
                }
            }
        }
        return null;
    }

    private static float maxStock(Groups.Group tribe) {
        float max = 0f;
        for (float amount : tribe.stock.values()) {
            max = Math.max(max, amount);
        }
        return max;
    }

    /** A free spot on land around the camp, away from the other buildings; null if there is none. */
    public float[] spot(Groups.Group tribe) {
        float[] radius = settlement.config().tribe().siteRadius();
        int n = settlement.all().size();
        for (int ring = 0; ring < 4; ring++) {
            float r = radius[0] + (radius[1] - radius[0]) * ring / 3f;
            for (int k = 0; k < 12; k++) {
                double angle = n * 2.4 + k * Math.PI / 6;
                float x = tribe.campX + (float) Math.sin(angle) * r;
                float z = tribe.campZ + (float) Math.cos(angle) * r;
                if (free(x, z)) {
                    return new float[]{x, z};
                }
            }
        }
        return null;
    }

    /** Land, not on another building. */
    public boolean free(float x, float z) {
        int tx = (int) Math.floor(x);
        int tz = (int) Math.floor(z);
        if (!terrain.inBounds(tx, tz) || !terrain.isPassable(tx, tz)) {
            return false;
        }
        for (Settlement.Building other : settlement.all()) {
            if (Math.hypot(other.x - x, other.z - z) < 3.5f) {
                return false;
            }
        }
        return true;
    }

    /** A finished shelter becomes a refuge. */
    private void finish() {
        Refuges.Type hut = refuges.type("hut");
        for (Settlement.Building building : settlement.all()) {
            if (building.done() && building.refuge == 0 && "refuge".equals(building.type.effect()) && hut != null) {
                Refuges.Refuge refuge = refuges.add(hut, building.x, building.z);
                refuge.owner = people.id(); // a hut is for its builders (phase 11b)
                building.refuge = refuge.id;
            }
        }
    }

    /** Builders (nearest to the site) while there is one, gatherers otherwise; roles only in the tribe. */
    private void roles(EcsWorld world, Groups.Group tribe, List<Integer> members, Tribe.Rules rules) {
        clearRoles(world, tribe.id);
        ComponentStore<Age> ages = world.store(Age.class);
        ComponentStore<Transform> transforms = world.store(Transform.class);
        List<Integer> adults = new ArrayList<>();
        for (int entity : members) {
            Age age = ages.get(entity);
            SpeciesRef ref = world.get(entity, SpeciesRef.class);
            if (age != null && ref != null
                    && age.ageTicks >= SpeciesDefinition.secondsToTicks(ref.stats().reproduction().adultAgeSeconds())) {
                adults.add(entity);
            }
        }
        Settlement.Building site = settlement.activeSite();
        int builders = site == null ? 0 : Math.min(rules.maxBuilders(), (int) Math.ceil(adults.size() * rules.builderShare()));
        if (site != null) {
            adults.sort(Comparator.<Integer>comparingDouble(e -> {
                Transform t = transforms.get(e);
                return Math.hypot(t.position.x - site.x, t.position.z - site.z);
            }).thenComparingInt(e -> e));
        }
        ComponentStore<Role> roleStore = world.store(Role.class);
        for (int i = 0; i < adults.size(); i++) {
            int entity = adults.get(i);
            Role role = roleStore.get(entity);
            if (role == null) {
                role = world.add(entity, new Role(false));
            }
            role.builder = i < builders;
        }
    }

    /** Removes the roles of everyone of these people who is not in the tribe (any more). */
    private void clearRoles(EcsWorld world, int tribeId) {
        ComponentStore<Role> roles = world.store(Role.class);
        for (int i = roles.size() - 1; i >= 0; i--) {
            SpeciesRef ref = world.get(roles.entityAt(i), SpeciesRef.class);
            if (ref != null && ref.species != people) {
                continue; // the other tribe's
            }
            GroupMember member = world.get(roles.entityAt(i), GroupMember.class);
            if (member == null || member.group != tribeId) {
                roles.remove(roles.entityAt(i));
            }
        }
    }

    /** Under an evil god some run away: they leave the tribe and the faith. */
    private void desert(EcsWorld world, List<Integer> members, int tick) {
        float chance = settlement.desertionPerMinute();
        if (chance <= 0f) {
            return;
        }
        int deserted = 0;
        Groups.Group tribe = tribe();
        for (int entity : members) {
            if (random.nextFloat() < chance) {
                Fear fear = world.get(entity, Fear.class);
                if (fear == null) {
                    fear = world.add(entity, new Fear());
                }
                fear.fromX = tribe.campX; // runs far from the camp
                fear.fromZ = tribe.campZ;
                fear.distance = 40f;
                fear.untilTick = Math.max(fear.untilTick, tick + 20 * Time.TICKS_PER_SECOND);
                world.store(GroupMember.class).remove(entity);
                world.store(Believer.class).remove(entity);
                world.store(Role.class).remove(entity);
                deserted++;
            }
        }
        if (deserted > 0) {
            announcements.add(deserted == 1 ? "Jeden člen kmene ze strachu utekl." : deserted + " členů kmene ze strachu uteklo.");
        }
    }
}

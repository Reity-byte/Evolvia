package evolvia.systems;

import evolvia.components.Age;
import evolvia.components.Believer;
import evolvia.components.GroupMember;
import evolvia.components.Needs;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.components.UnderAttack;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Groups;
import evolvia.world.Refuges;
import evolvia.world.WorldClock;
import evolvia.world.SpatialGrid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Herds (phases 9a, 9c; DESIGN.md §11), updated every {@code groups.updateSeconds}: replaces dead leaders
 * (oldest adult), dissolves too small herds, splits too large ones, lets members who stay too far away
 * leave, and lets free creatures join a nearby herd or found a new one. Also keeps the herd's home (it
 * follows the leader unless the god settled the herd), its average hunger, and its owner: a wild herd in
 * which at least half believe becomes the player's, and every member of the player's herds believes.
 * The {@code groups} ability makes herds bigger. Everything is processed in ID order (deterministic).
 */
public final class GroupSystem implements GameSystem {

    private final Groups groups;
    private final SpatialGrid creatureGrid;
    private final WorldClock clock;
    private final Refuges refuges;
    /** The player's species: herds without a species of their own (old saves) are of it. */
    private final Species people;

    public GroupSystem(Groups groups, SpatialGrid creatureGrid, WorldClock clock, Refuges refuges, Species people) {
        this.groups = groups;
        this.creatureGrid = creatureGrid;
        this.clock = clock;
        this.refuges = refuges;
        this.people = people;
    }

    private Species kind(Groups.Group group) {
        return group.species != null ? group.species : people;
    }

    /** Largest herd of a species: the {@code groups} ability makes herds half as big again. */
    private static int maxSize(Species kind) {
        SpeciesDefinition.Groups rules = kind.stats().groups();
        return kind.hasAbility(Groups.ABILITY) ? rules.maxSize() * 3 / 2 : rules.maxSize();
    }

    private static int adultTicks(Species kind) {
        return SpeciesDefinition.secondsToTicks(kind.stats().reproduction().adultAgeSeconds());
    }

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<SpeciesRef> creatures = world.store(SpeciesRef.class);
        if (creatures.size() == 0) {
            return;
        }
        int interval = Math.max(1, SpeciesDefinition.secondsToTicks(people.stats().groups().updateSeconds()));
        if (tick % interval != 0) {
            return;
        }
        ComponentStore<GroupMember> members = world.store(GroupMember.class);
        ComponentStore<Transform> transforms = world.store(Transform.class);
        ComponentStore<Age> ages = world.store(Age.class);
        ComponentStore<Believer> believers = world.store(Believer.class);
        ComponentStore<Needs> needs = world.store(Needs.class);
        removeOldAttacks(world.store(UnderAttack.class), tick);

        // Current herds and their members (ID order).
        Map<Integer, List<Integer>> byGroup = new TreeMap<>();
        List<Integer> orphans = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            int entity = members.entityAt(i);
            int group = members.componentAt(i).group;
            SpeciesRef ref = creatures.get(entity);
            if (groups.get(group) == null || ref == null) {
                orphans.add(entity);
            } else {
                byGroup.computeIfAbsent(group, g -> new ArrayList<>()).add(entity);
            }
        }
        orphans.sort(null);
        for (int entity : orphans) {
            members.remove(entity);
        }
        for (Groups.Group group : new ArrayList<>(groups.all())) {
            if (!byGroup.containsKey(group.id)) {
                groups.remove(group.id); // everybody died or left
            }
        }

        for (Map.Entry<Integer, List<Integer>> entry : new ArrayList<>(byGroup.entrySet())) {
            Groups.Group group = groups.get(entry.getKey());
            List<Integer> list = entry.getValue();
            list.sort(null);
            Species kind = kind(group);
            SpeciesDefinition.Groups rules = kind.stats().groups();
            int adultTicks = adultTicks(kind);
            int maxSize = maxSize(kind);
            if (list.size() < rules.minSize()) {
                for (int entity : list) {
                    members.remove(entity);
                }
                groups.remove(group.id);
                continue;
            }
            if (group.leader < 0 || !list.contains(group.leader)) {
                group.leader = oldest(list, ages, adultTicks);
            }
            if (list.size() > maxSize && !group.tribe) { // the tribe has no size limit
                split(group, list, members, transforms, ages, adultTicks);
            }
            leave(group, list, members, transforms, rules, interval);
            group.size = list.size();
            allegiance(world, group, list, believers);
            float hunger = 0f;
            for (int entity : list) {
                Needs n = needs.get(entity);
                hunger += n != null ? n.hunger : 0f;
            }
            group.hunger = hunger / list.size();
            if (!group.settled) {
                Transform leader = transforms.get(group.leader);
                group.homeX = leader.position.x;
                group.homeZ = leader.position.z;
            }
            if (group.attackGroup != 0 && (!group.attackOrdered(tick) || groups.get(group.attackGroup) == null)) {
                group.attackGroup = 0;
            }
            if (kind.isAnimal() && kind.animal().nocturnal()) {
                group.shelter = 0; // night hunters rest by day wherever they are
            } else {
                chooseShelter(group, transforms.get(group.leader), tick);
            }
        }

        joinOrFound(creatures, members, transforms, ages, believers);
    }

    /**
     * In the evening the herd picks the refuge nearest to its leader and keeps it through the night; by day
     * it has none. A refuge another herd has taken is used only if there is no other (sharing one means a
     * fight in the morning); the player's people prefer sacred places, wild herds shun them.
     */
    private void chooseShelter(Groups.Group group, Transform leader, int tick) {
        if (!clock.isShelterTime(tick)) {
            group.shelter = 0;
            return;
        }
        if (group.shelter != 0 || leader == null) {
            return;
        }
        Set<Integer> taken = new HashSet<>();
        for (Groups.Group other : groups.all()) {
            if (other != group && other.shelter != 0) {
                taken.add(other.shelter);
            }
        }
        float x = leader.position.x;
        float z = leader.position.z;
        float radius = refuges.config().searchRadius();
        Refuges.Refuge refuge = refuges.nearest(x, z, radius, group.player,
                r -> !taken.contains(r.id) && (group.player || !r.sacred));
        if (refuge == null) {
            refuge = refuges.nearest(x, z, radius, group.player, r -> group.player || !r.sacred);
        }
        group.shelter = refuge != null ? refuge.id : 0;
    }

    /**
     * A wild herd in which at least half believe becomes the player's; all members of the player's
     * herds are believers (they are the player's people).
     */
    private static void allegiance(EcsWorld world, Groups.Group group, List<Integer> list, ComponentStore<Believer> believers) {
        if (group.species != null && !group.species.canBelieve()) {
            return; // wild game and the rival have no faith
        }
        if (!group.player) {
            int believing = 0;
            for (int entity : list) {
                if (believers.has(entity)) {
                    believing++;
                }
            }
            if (believing * 2 < list.size()) {
                return;
            }
            group.player = true;
            group.attackGroup = 0;
        }
        for (int entity : list) {
            if (!believers.has(entity)) {
                world.add(entity, new Believer());
            }
        }
    }

    private static void removeOldAttacks(ComponentStore<UnderAttack> attacks, int tick) {
        for (int i = attacks.size() - 1; i >= 0; i--) {
            if (!attacks.componentAt(i).isActive(tick)) {
                attacks.remove(attacks.entityAt(i));
            }
        }
    }

    /** The farther half from the leader becomes a new herd under its own oldest adult. */
    private void split(Groups.Group group, List<Integer> list, ComponentStore<GroupMember> members,
                       ComponentStore<Transform> transforms, ComponentStore<Age> ages, int adultTicks) {
        Transform leader = transforms.get(group.leader);
        List<Integer> byDistance = new ArrayList<>(list);
        byDistance.remove(Integer.valueOf(group.leader));
        byDistance.sort(Comparator.<Integer>comparingDouble(e -> -distanceSq(transforms.get(e), leader.position.x, leader.position.z))
                .thenComparingInt(e -> e));
        List<Integer> leaving = new ArrayList<>(byDistance.subList(0, list.size() / 2));
        leaving.sort(null);
        Groups.Group split = groups.create();
        for (int entity : leaving) {
            members.get(entity).group = split.id;
            members.get(entity).farTicks = 0;
        }
        split.leader = oldest(leaving, ages, adultTicks);
        split.size = leaving.size();
        split.player = group.player;
        split.species = group.species;
        Transform splitLeader = transforms.get(split.leader);
        split.homeX = splitLeader.position.x;
        split.homeZ = splitLeader.position.z;
        split.knowsWater = group.knowsWater;
        split.waterX = group.waterX;
        split.waterZ = group.waterZ;
        split.knowsFood = group.knowsFood;
        split.foodX = group.foodX;
        split.foodZ = group.foodZ;
        list.removeAll(leaving);
    }

    /** Members that stay too far from the leader for too long leave the herd. */
    private static void leave(Groups.Group group, List<Integer> list, ComponentStore<GroupMember> members,
                              ComponentStore<Transform> transforms, SpeciesDefinition.Groups rules, int interval) {
        Transform leader = transforms.get(group.leader);
        int leaveTicks = SpeciesDefinition.secondsToTicks(rules.leaveSeconds());
        float leaveSq = rules.leaveDistance() * rules.leaveDistance();
        List<Integer> leaving = new ArrayList<>();
        for (int entity : list) {
            GroupMember member = members.get(entity);
            if (entity != group.leader && distanceSq(transforms.get(entity), leader.position.x, leader.position.z) > leaveSq) {
                member.farTicks += interval;
                if (member.farTicks >= leaveTicks) {
                    leaving.add(entity);
                }
            } else {
                member.farTicks = 0;
            }
        }
        for (int entity : leaving) {
            members.remove(entity);
        }
        list.removeAll(leaving);
    }

    private void joinOrFound(ComponentStore<SpeciesRef> creatures, ComponentStore<GroupMember> members,
                             ComponentStore<Transform> transforms, ComponentStore<Age> ages,
                             ComponentStore<Believer> believers) {
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < creatures.size(); i++) {
            int entity = creatures.entityAt(i);
            if (!members.has(entity)) {
                free.add(entity);
            }
        }
        if (free.isEmpty()) {
            return;
        }
        free.sort(null);
        Set<Integer> freeSet = new HashSet<>(free);
        for (int entity : free) {
            if (!freeSet.contains(entity)) {
                continue; // became a founder of a herd this update
            }
            Species kind = creatures.get(entity).species;
            SpeciesDefinition.Groups rules = kind.stats().groups();
            int maxSize = maxSize(kind);
            float joinSq = rules.joinRadius() * rules.joinRadius();
            Transform t = transforms.get(entity);
            Groups.Group best = null;
            double bestSq = Double.MAX_VALUE;
            for (Groups.Group group : groups.all()) {
                if (group.leader < 0 || (group.size >= maxSize && !group.tribe) || kind(group) != kind) {
                    continue; // herds are of one species
                }
                double d = distanceSq(transforms.get(group.leader), t.position.x, t.position.z);
                if (d <= joinSq && d < bestSq) {
                    bestSq = d;
                    best = group;
                }
            }
            if (best != null) {
                members.put(entity, new GroupMember(best.id));
                best.size++;
                freeSet.remove(entity);
                continue;
            }
            // Found a new herd with the free creatures around.
            List<Integer> founders = new ArrayList<>();
            creatureGrid.forEachWithin(t.position.x, t.position.z, rules.joinRadius(), other -> {
                SpeciesRef ref = creatures.get(other);
                if (freeSet.contains(other) && ref != null && ref.species == kind) {
                    founders.add(other);
                }
            });
            if (founders.size() < rules.minFounders()) {
                continue;
            }
            founders.sort(Comparator.<Integer>comparingDouble(e -> distanceSq(transforms.get(e), t.position.x, t.position.z))
                    .thenComparingInt(e -> e));
            List<Integer> herd = new ArrayList<>(founders.subList(0, Math.min(founders.size(), maxSize)));
            herd.sort(null);
            Groups.Group group = groups.create();
            group.species = kind.canBelieve() ? null : kind; // null = the player's species
            int believing = 0;
            for (int founder : herd) {
                members.put(founder, new GroupMember(group.id));
                freeSet.remove(founder);
                if (believers.has(founder)) {
                    believing++;
                }
            }
            group.leader = oldest(herd, ages, adultTicks(kind));
            group.size = herd.size();
            group.player = kind.canBelieve() && believing * 2 >= herd.size();
            Transform leader = transforms.get(group.leader);
            group.homeX = leader.position.x;
            group.homeZ = leader.position.z;
        }
    }

    /** Oldest adult (or oldest at all if there is no adult); ties go to the lower ID. */
    private static int oldest(List<Integer> list, ComponentStore<Age> ages, int adultTicks) {
        int best = -1;
        long bestKey = Long.MIN_VALUE;
        for (int entity : list) {
            Age age = ages.get(entity);
            int ticks = age != null ? age.ageTicks : 0;
            long key = (ticks >= adultTicks ? 1L << 40 : 0L) + ticks;
            if (key > bestKey || (key == bestKey && entity < best)) {
                bestKey = key;
                best = entity;
            }
        }
        return best;
    }

    private static double distanceSq(Transform t, float x, float z) {
        double dx = t.position.x - x;
        double dz = t.position.z - z;
        return dx * dx + dz * dz;
    }
}

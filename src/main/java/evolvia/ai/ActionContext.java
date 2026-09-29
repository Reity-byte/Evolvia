package evolvia.ai;

import evolvia.world.Settlement;
import evolvia.components.Carrying;
import evolvia.world.Tribe;
import evolvia.world.Wildlife;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import evolvia.components.Age;
import evolvia.components.Believer;
import evolvia.components.Fear;
import evolvia.components.Genome;
import evolvia.components.UnderAttack;
import evolvia.core.Time;
import evolvia.components.GroupMember;
import evolvia.components.AiState;
import evolvia.components.Health;
import evolvia.components.Memory;
import evolvia.components.Needs;
import evolvia.components.Reproduction;
import evolvia.components.ResourceNode;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.components.Velocity;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Births;
import evolvia.world.Groups;
import evolvia.world.Nature;
import evolvia.world.Refuges;
import evolvia.world.WorldClock;
import evolvia.world.ResourceKind;
import evolvia.world.SpatialGrid;
import evolvia.world.Terrain;

import java.util.Random;

/**
 * What an {@link Action} can see and use: shared world services plus the components of the
 * creature currently being updated. One instance is reused for all creatures (no allocation).
 */
public final class ActionContext {

    /** A creature can eat or drink from a node this close (tiles). */
    public static final float REACH = 1.0f;

    public final Terrain terrain;
    /** Ability that lets a creature remember where it last drank and ate. */
    public static final String MEMORY = "memory";

    public final Navigation navigation;
    public final PathQueue pathQueue;
    public final SpatialGrid foodGrid;
    public final SpatialGrid waterGrid;
    public final SpatialGrid creatureGrid;
    public final Births births;
    public final Random random;
    public final Groups groups;
    public final WorldClock clock;
    public final Refuges refuges;
    /** Seasons, weather, disease (phase 9e). */
    public final Nature nature;
    /** Rules of hunting (phase 9f). */
    public final Wildlife.Hunting hunting;
    /** Trees and rocks (phase 9g). */
    public final SpatialGrid materialGrid;
    /** Rules of gathering (phase 9g). */
    public final Tribe.Gathering gathering;
    /** The materials there are (wood, stone...), in name order. */
    public final java.util.List<String> materials;
    /** The tribe's buildings and mood (phase 9h). */
    /** The settlement of the current creature's tribe (the player's or the rival's, phase 11b). */
    public Settlement settlement;
    private final Settlement playerSettlement;
    private Settlement rivalSettlement;

    /** The rival's settlement (phase 11b). */
    public void setRivalSettlement(Settlement rivalSettlement) {
        this.rivalSettlement = rivalSettlement;
    }
    /** Rules of the tribe (phase 9h). */
    public final Tribe.Rules tribeRules;
    /** Living creatures of each species this tick (population caps). */
    private final Map<Species, Integer> speciesCounts = new IdentityHashMap<>();

    public EcsWorld ecs;
    public ComponentStore<ResourceNode> resources;
    public ComponentStore<Transform> transforms;
    private ComponentStore<SpeciesRef> creatures;
    private ComponentStore<Needs> needsStore;
    private ComponentStore<Health> healths;
    private ComponentStore<Age> ages;
    private ComponentStore<Reproduction> reproductions;
    private ComponentStore<Memory> memories;
    private ComponentStore<GroupMember> groupMembers;
    private ComponentStore<UnderAttack> underAttacks;
    private ComponentStore<Fear> fears;
    private ComponentStore<Believer> believers;
    public int tick;

    public int entity;
    public Transform transform;
    public Velocity velocity;
    public Needs needs;
    public AiState ai;
    /** Current (evolved) stats of the creature's species. */
    public SpeciesDefinition species;
    /** The creature's species (evolution state). */
    public Species kind;
    /** The creature's species and evolutionary stage (its own abilities). */
    public SpeciesRef ref;

    // Per-creature query caches (reset by bind); -2 = not computed yet.
    private int nearestFood;
    private int nearestWater;
    private int foodInReach;
    private int waterInReach;
    private int nearestMate;
    private int attackTarget;
    private float attackScore;
    private int huntTarget;

    public ActionContext(Terrain terrain, Navigation navigation, PathQueue pathQueue,
                         SpatialGrid foodGrid, SpatialGrid waterGrid, SpatialGrid creatureGrid, Births births,
                         Random random, Groups groups, WorldClock clock, Refuges refuges, Nature nature,
                         Wildlife.Hunting hunting, SpatialGrid materialGrid, Tribe.Gathering gathering,
                         java.util.List<String> materials, Settlement settlement) {
        this.terrain = terrain;
        this.navigation = navigation;
        this.pathQueue = pathQueue;
        this.foodGrid = foodGrid;
        this.waterGrid = waterGrid;
        this.creatureGrid = creatureGrid;
        this.births = births;
        this.random = random;
        this.groups = groups;
        this.clock = clock;
        this.refuges = refuges;
        this.nature = nature;
        this.hunting = hunting;
        this.materialGrid = materialGrid;
        this.gathering = gathering;
        this.materials = java.util.List.copyOf(materials);
        this.settlement = settlement;
        this.playerSettlement = settlement;
        this.tribeRules = settlement.config().tribe();
    }

    // ---------------------------------------------------------------- gathering (phase 9g)

    /** What the current creature carries to the camp, or null. */
    public Carrying carrying() {
        return ecs.get(entity, Carrying.class);
    }

    /** True if the current creature may gather now: a grown member of a herd of the people's kind, able, fed, awake time. */
    public boolean canGather() {
        if (kind.isAnimal() || !ref.hasAbility(gathering.ability()) || carrying() != null || restTime()) {
            return false;
        }
        Age age = ages.get(entity);
        if (age == null || age.ageTicks < SpeciesDefinition.secondsToTicks(species.reproduction().adultAgeSeconds())) {
            return false;
        }
        return group() != null && !group().raid && Math.max(needs.hunger, needs.thirst) < gathering.maxNeed() && needs.energy > 0.25f;
    }

    /** How much of each material the herd's camp stores (more in the tribe's with a store). */
    public float stockCap(Groups.Group group) {
        return gathering.stockCap() * (group.tribe ? settlement.storageFactor() : 1f);
    }

    /** True if the current creature belongs to the tribe (phase 9h). */
    public boolean inTribe() {
        Groups.Group group = group();
        return group != null && group.tribe;
    }

    /** Speed of the current creature's work: the tribe works harder under an evil god. */
    /** The people's science (phase 10b): deliveries and finished buildings add knowledge. */
    public evolvia.world.Science science;
    private evolvia.evolution.EvolutionConditions conditions;

    public void setScience(evolvia.world.Science science, evolvia.evolution.EvolutionConditions conditions) {
        this.science = science;
        this.conditions = conditions;
    }

    /** Knowledge for work of the player's people, once science has begun. */
    public void rewardKnowledge(float amount) {
        Groups.Group group = group();
        if (science != null && science.isActive() && group != null && group.player) {
            science.add(amount, conditions);
        }
    }

    public float workFactor() {
        return (inTribe() ? settlement.workFactor() : 1f) * species.skills().workSpeed(); // Strength (phase 10a)
    }

    /** Where the herd's materials go: its camp, or its home before the first delivery. */
    public float[] campSpot(Groups.Group group) {
        return group.hasCamp ? new float[]{group.campX, group.campZ} : new float[]{group.homeX, group.homeZ};
    }

    /**
     * Nearest tree or rock within the gathering radius of the camp that gives the material the camp has least
     * of (below the cap), reachable from here; or -1.
     */
    public int gatherTarget() {
        Groups.Group group = group();
        if (group == null) {
            return -1;
        }
        float[] camp = campSpot(group);
        int myRegion = pathfinder().regionAt(transform.position.x, transform.position.z);
        java.util.List<String> wanted = new ArrayList<>(materials);
        wanted.removeIf(m -> group.stock(m) >= stockCap(group));
        wanted.sort(java.util.Comparator.<String>comparingDouble(group::stock).thenComparing(m -> m));
        for (String material : wanted) {
            int node = materialGrid.nearest(camp[0], camp[1], gathering.radius(), n -> {
                ResourceNode r = resources.get(n);
                if (r == null || r.amount < 1f || !material.equals(r.type.material())) {
                    return false;
                }
                Transform t = transforms.get(n);
                return pathfinder().regionAt(t.position.x, t.position.z) == myRegion;
            });
            if (node >= 0) {
                return node;
            }
        }
        return -1;
    }

    /** Time to sleep through: the night, or the day for night animals (phase 9f). */
    public boolean restTime() {
        boolean night = clock.isNight(tick);
        return kind.isAnimal() && kind.animal().nocturnal() ? !night : night;
    }

    /** Where the creature should spend the night: its herd's refuge, or (alone) the nearest one it sees; or null. */
    public Refuges.Refuge shelter() {
        Groups.Group group = group();
        if (group != null) {
            return refuges.get(group.shelter);
        }
        return refuges.nearest(transform.position.x, transform.position.z, species.senseRadius(), false, r -> r.usableBy(kind));
    }

    /** Prepares the context for one tick. */
    public void beginTick(EcsWorld ecs, int tick) {
        this.ecs = ecs;
        this.tick = tick;
        this.resources = ecs.store(ResourceNode.class);
        this.transforms = ecs.store(Transform.class);
        this.creatures = ecs.store(SpeciesRef.class);
        this.needsStore = ecs.store(Needs.class);
        this.healths = ecs.store(Health.class);
        this.ages = ecs.store(Age.class);
        this.reproductions = ecs.store(Reproduction.class);
        this.memories = ecs.store(Memory.class);
        this.groupMembers = ecs.store(GroupMember.class);
        this.underAttacks = ecs.store(UnderAttack.class);
        this.fears = ecs.store(Fear.class);
        this.believers = ecs.store(Believer.class);
        speciesCounts.clear();
        for (int i = 0; i < creatures.size(); i++) {
            speciesCounts.merge(creatures.componentAt(i).species, 1, Integer::sum);
        }
    }

    /** Points the context at one creature. */
    public void bind(int entity, Transform transform, Velocity velocity, Needs needs, AiState ai, SpeciesRef ref) {
        this.entity = entity;
        this.transform = transform;
        this.velocity = velocity;
        this.needs = needs;
        this.ai = ai;
        this.ref = ref;
        this.kind = ref.species;
        this.settlement = kind.isRival() && rivalSettlement != null ? rivalSettlement : playerSettlement;
        this.species = ref.stats();
        nearestFood = -2;
        nearestWater = -2;
        foodInReach = -2;
        waterInReach = -2;
        nearestMate = -2;
        attackTarget = -2;
        huntTarget = -2;
    }

    /** Makes the current creature a believer (it used something the god caused). */
    public void makeBeliever() {
        if (kind.canBelieve() && ecs.get(entity, Believer.class) == null) {
            ecs.add(entity, new Believer());
        }
    }

    /** The current creature's fear of a lightning strike, or null. */
    public Fear fear() {
        return ecs.get(entity, Fear.class);
    }

    /** The current creature's herd, or null. */
    public Groups.Group group() {
        GroupMember member = groupMembers.get(entity);
        return member != null ? groups.get(member.group) : null;
    }

    /** Position of the current creature's herd leader, or null (no herd, no leader yet, or it leads itself). */
    public Transform leader() {
        Groups.Group group = group();
        if (group == null || group.leader < 0 || group.leader == entity) {
            return null;
        }
        return transforms.get(group.leader);
    }

    // ---------------------------------------------------------------- fights (phase 9c)

    /** True if {@code other} is a living creature of a herd this creature's herd fights. */
    public boolean isEnemy(int other) {
        if (other < 0 || other == entity || creatures.get(other) == null) {
            return false;
        }
        Health health = healths.get(other);
        if (health == null || health.hp <= 0f) {
            return false;
        }
        GroupMember mine = groupMembers.get(entity);
        GroupMember theirs = groupMembers.get(other);
        Groups.Group myGroup = mine != null ? groups.get(mine.group) : null;
        Groups.Group theirGroup = theirs != null ? groups.get(theirs.group) : null;
        if (myGroup != null && theirGroup != null && myGroup.attackOrdered(tick) && myGroup.attackGroup == theirGroup.id) {
            return true; // the god ordered it (also a hunt)
        }
        if (myGroup != null && theirGroup != null && theirGroup.attackOrdered(tick) && theirGroup.attackGroup == myGroup.id) {
            return true; // they came to fight us
        }
        Species theirKind = creatures.get(other).species;
        if (theirKind != kind) {
            return hunts(kind, theirKind) || hunts(theirKind, kind); // hunter and prey (phase 9f)
        }
        return Groups.enemies(myGroup, theirGroup);
    }

    /**
     * True if {@code hunter} hunts {@code prey}: a predator its listed species; the player's species, once it eats
     * meat, wild game that is prey.
     */
    public static boolean hunts(Species hunter, Species prey) {
        if (hunter.isAnimal()) {
            return hunter.animal().prey().contains(prey.id());
        }
        return hunter.stats().diet().meatNutrition() > 0f && prey.isAnimal() && prey.animal().isPrey();
    }

    // ---------------------------------------------------------------- hunting (phase 9f)

    /** Nearest prey this creature would hunt now (seen, reachable, not asleep in a refuge), or -1. */
    public int huntTarget() {
        if (huntTarget != -2) {
            return huntTarget;
        }
        huntTarget = -1;
        if (species.diet().meatNutrition() <= 0f) {
            return huntTarget;
        }
        float x = transform.position.x;
        float z = transform.position.z;
        int myRegion = pathfinder().regionAt(x, z);
        if (kind.isAnimal()) { // a predator prefers its prey in the order of its list (deer before people)
            for (String preferred : kind.animal().prey()) {
                huntTarget = nearestPrey(x, z, myRegion, preferred);
                if (huntTarget >= 0) {
                    return huntTarget;
                }
            }
            return huntTarget;
        }
        huntTarget = nearestPrey(x, z, myRegion, null);
        return huntTarget;
    }

    private int nearestPrey(float x, float z, int myRegion, String speciesId) {
        return creatureGrid.nearest(x, z, species.senseRadius(), other -> {
            SpeciesRef theirs = creatures.get(other);
            if (other == entity || theirs == null || theirs.species == kind || !hunts(kind, theirs.species)
                    || (speciesId != null && !theirs.species.id().equals(speciesId))) {
                return false;
            }
            Health h = healths.get(other);
            if (h == null || h.hp <= 0f) {
                return false;
            }
            Transform t = transforms.get(other);
            Needs n = needsStore.get(other);
            if (n != null && n.sleeping && refuges.at(t.position.x, t.position.z) != null) {
                return false; // a refuge keeps its sleepers safe
            }
            return reachable(other, myRegion);
        });
    }

    /** True if {@code other} may still be hunted by this creature. */
    public boolean isPrey(int other) {
        SpeciesRef theirs = creatures.get(other);
        Health h = healths.get(other);
        return theirs != null && h != null && h.hp > 0f && theirs.species != kind && hunts(kind, theirs.species);
    }

    /** The hunted and the prey around it (its own species) run from the hunter. */
    public void scarePrey(int target) {
        SpeciesRef prey = creatures.get(target);
        if (prey == null) {
            return;
        }
        float x = transform.position.x;
        float z = transform.position.z;
        int until = tick + SpeciesDefinition.secondsToTicks(hunting.scareSeconds());
        // With speech (phase 9h) the whole herd around is warned, not only those who see the hunter.
        GroupMember preyHerd = groupMembers.get(target);
        boolean alarm = prey.hasAbility(tribeRules.speechAbility()) && preyHerd != null;
        List<Integer> near = new ArrayList<>();
        creatureGrid.forEachWithin(x, z, alarm ? Math.max(hunting.scareRadius(), tribeRules.alarmRadius()) : hunting.scareRadius(),
                near::add);
        near.sort(null);
        for (int other : near) {
            SpeciesRef ref = creatures.get(other);
            if (ref == null || ref.species != prey.species) {
                continue;
            }
            if (alarm && distanceTo(other) > hunting.scareRadius()) {
                GroupMember m = groupMembers.get(other);
                if (m == null || m.group != preyHerd.group) {
                    continue; // only herd mates hear the alarm
                }
            }
            Fear fear = fears.get(other);
            if (fear == null) {
                fear = new Fear();
                ecs.add(other, fear);
            }
            fear.fromX = x;
            fear.fromZ = z;
            fear.distance = hunting.fleeDistance();
            fear.untilTick = Math.max(fear.untilTick, until);
        }
    }

    /** Creature to attack now, or -1 (see {@link evolvia.ai.actions.AttackAction}). */
    public int attackTarget() {
        if (attackTarget == -2) {
            findAttackTarget();
        }
        return attackTarget;
    }

    /** Utility of attacking {@link #attackTarget()}. */
    public float attackScore() {
        attackTarget();
        return attackScore;
    }

    private void findAttackTarget() {
        attackTarget = -1;
        attackScore = 0f;
        SpeciesDefinition.Combat combat = species.combat();
        Health health = healths.get(entity);
        Age age = ages.get(entity);
        Fear fear = fears.get(entity);
        if (kind.isAnimal() && kind.animal().isPrey()) {
            return; // prey runs, it does not fight
        }
        if (health == null || age == null || health.hp < combat.fleeHealth() * health.maxHp
                || age.ageTicks < SpeciesDefinition.secondsToTicks(species.reproduction().adultAgeSeconds())
                || (fear != null && fear.isActive(tick))) {
            return; // the young, the wounded and the scared do not fight
        }
        float x = transform.position.x;
        float z = transform.position.z;
        UnderAttack hit = underAttacks.get(entity);
        if (hit != null && hit.isActive(tick) && isEnemy(hit.attacker)) {
            attackTarget = hit.attacker; // fight back
            attackScore = combat.orderScore();
            return;
        }
        Groups.Group group = group();
        if (group == null) {
            return;
        }
        int myRegion = pathfinder().regionAt(x, z);
        if (group.attackOrdered(tick)) {
            int ordered = group.attackGroup;
            attackTarget = creatureGrid.nearest(x, z, species.senseRadius() * 2f, other -> {
                GroupMember m = groupMembers.get(other);
                return m != null && m.group == ordered && isEnemy(other) && reachable(other, myRegion);
            });
            if (attackTarget >= 0) {
                attackScore = combat.orderScore();
                return;
            }
        }
        if (ref.hasAbility(Groups.ABILITY)) { // herd bonus: help a herd mate that is being attacked
            int helped = creatureGrid.nearest(x, z, species.groups().followDistance() * 2f, other -> {
                GroupMember m = groupMembers.get(other);
                UnderAttack a = underAttacks.get(other);
                return m != null && m.group == group.id && a != null && a.isActive(tick) && isEnemy(a.attacker);
            });
            if (helped >= 0) {
                attackTarget = underAttacks.get(helped).attacker;
                attackScore = combat.attackScore() + 0.2f;
                return;
            }
        }
        // Wild herds always drive intruders off their territory; the player's herds when hungry (or on order).
        if (!group.player || group.hunger >= combat.aggroNeed()) {
            float territorySq = combat.territoryRadius() * combat.territoryRadius();
            attackTarget = creatureGrid.nearest(x, z, species.senseRadius(), other -> {
                Transform t = transforms.get(other);
                float dx = t.position.x - group.homeX;
                float dz = t.position.z - group.homeZ;
                return dx * dx + dz * dz <= territorySq && creatures.get(other).species == kind && isEnemy(other)
                        && reachable(other, myRegion);
            });
            if (attackTarget >= 0) {
                attackScore = combat.attackScore();
            }
        }
    }

    private boolean reachable(int other, int myRegion) {
        Transform t = transforms.get(other);
        return myRegion >= 0 && pathfinder().regionAt(t.position.x, t.position.z) == myRegion;
    }

    /**
     * One tick of hitting {@code target}: takes health, marks it as attacked (it fights back or, when
     * weak, runs). A target that falls below {@code surrenderHealth} gives up and joins this creature's
     * herd (and believes if that is the player's); a killed one dies "in a fight".
     *
     * @return true when this fight is over (the target surrendered or died)
     */
    public boolean hit(int target) {
        return hit(target, 1f);
    }

    /** Like {@link #hit(int)} with {@code factor} times the damage (a hunter's bite, phase 9f). */
    public boolean hit(int target, float factor) {
        SpeciesDefinition.Combat combat = species.combat();
        Health health = healths.get(target);
        health.hp -= SpeciesDefinition.perTick(combat.damagePerSecond()) * factor;
        UnderAttack attacked = underAttacks.get(target);
        if (attacked == null) {
            attacked = new UnderAttack();
            ecs.add(target, attacked);
        }
        attacked.attacker = entity;
        attacked.untilTick = tick + 3 * Time.TICKS_PER_SECOND;
        Groups.Group mine = group();
        boolean sameKind = creatures.get(target) != null && creatures.get(target).species == kind;
        if (health.hp <= 0f) {
            if (mine != null && mine.player && (sameKind || !creatures.get(target).species.canBelieve())) {
                groups.recordPlayerVictory(); // a wild rival, a predator, game (phase 9i) or the rival people (phase 11)
            }
            return true; // the aging system removes it this tick ("in a fight")
        }
        float share = health.hp / health.maxHp;
        if (share <= combat.surrenderHealth() && mine != null && sameKind) {
            GroupMember member = groupMembers.get(target);
            if (member != null) {
                member.group = mine.id;
                member.farTicks = 0;
            } else {
                ecs.add(target, new GroupMember(mine.id));
            }
            if (mine.player) {
                if (!believers.has(target)) {
                    ecs.add(target, new Believer());
                }
                groups.recordPlayerVictory();
            } else {
                believers.remove(target); // joined a wild herd
            }
            underAttacks.remove(target);
            return true;
        }
        Age age = ages.get(target);
        boolean young = age == null || age.ageTicks < SpeciesDefinition.secondsToTicks(species.reproduction().adultAgeSeconds());
        if (share < combat.fleeHealth() || young) {
            Fear fear = fears.get(target);
            if (fear == null) {
                fear = new Fear();
                ecs.add(target, fear);
            }
            fear.fromX = transform.position.x;
            fear.fromZ = transform.position.z;
            fear.distance = 10f;
            fear.untilTick = tick + 4 * Time.TICKS_PER_SECOND;
        }
        return false;
    }

    /** Pathfinder for the current creature's way of moving (walking, or also swimming). */
    public Pathfinder pathfinder() {
        return navigation.forCreature(ref);
    }

    /** The current creature's memory, or null if its species has no memory ability. */
    public Memory memory() {
        return ref.hasAbility(MEMORY) ? memories.get(entity) : null;
    }

    // ---------------------------------------------------------------- resource queries

    /**
     * Nearest usable node of a kind within the sense radius and reachable over land, or -1. Herd members
     * forage near their leader (within {@code groups.forageRadius} of it) unless the need is urgent, so
     * the herd stays together and the leader leads it to food and water.
     */
    public int nearest(ResourceKind kind) {
        if (kind == ResourceKind.FOOD) {
            if (nearestFood == -2) {
                nearestFood = findNode(kind, true, needs.hunger);
            }
            return nearestFood;
        }
        if (nearestWater == -2) {
            nearestWater = findNode(kind, true, needs.thirst);
        }
        return nearestWater;
    }

    private int findNode(ResourceKind kind, boolean sameRegion, float need) {
        Transform leader = leader();
        SpeciesDefinition.Groups rules = species.groups();
        if (leader != null && need < rules.urgentNeed()) {
            return findNode(kind, leader.position.x, leader.position.z, rules.forageRadius(), sameRegion);
        }
        return findNode(kind, transform.position.x, transform.position.z, species.senseRadius(), sameRegion);
    }

    /** Usable node of a kind within {@link #REACH}, or -1. */
    public int inReach(ResourceKind kind) {
        if (kind == ResourceKind.FOOD) {
            if (foodInReach == -2) {
                foodInReach = findNode(kind, transform.position.x, transform.position.z, REACH, false);
            }
            return foodInReach;
        }
        if (waterInReach == -2) {
            waterInReach = findNode(kind, transform.position.x, transform.position.z, REACH, false);
        }
        return waterInReach;
    }

    /** True if the node exists and still has something to take. */
    public boolean isUsable(int node) {
        ResourceNode resource = resources.get(node);
        if (resource == null) {
            return false;
        }
        if (resource.type.kind() == ResourceKind.WATER) {
            return true;
        }
        return resource.amount >= 1f && nutrition(resource) > 0f;
    }

    /** How much one unit of this food nourishes the current creature (0 = its diet does not include it). */
    public float nutrition(ResourceNode food) {
        return food.type.nutrition() * species.diet().nutrition(food.type.foodType());
    }

    /** Nearest usable node within {@code radius} of (x, z), optionally only ones reachable from here. */
    private int findNode(ResourceKind kind, float x, float z, float radius, boolean sameRegion) {
        int myRegion = sameRegion ? pathfinder().regionAt(transform.position.x, transform.position.z) : -1;
        SpatialGrid grid = kind == ResourceKind.FOOD ? foodGrid : waterGrid;
        return grid.nearest(x, z, radius, node -> {
            if (!isUsable(node)) {
                return false;
            }
            if (!sameRegion) {
                return true;
            }
            Transform t = transforms.get(node);
            return pathfinder().regionAt(t.position.x, t.position.z) == myRegion;
        });
    }

    public float distanceTo(int entity) {
        Transform t = transforms.get(entity);
        if (t == null) {
            return Float.POSITIVE_INFINITY;
        }
        float dx = t.position.x - transform.position.x;
        float dz = t.position.z - transform.position.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    // ---------------------------------------------------------------- mates

    /**
     * True if the creature may reproduce now: adult, cooldown over, fed and watered, healthy,
     * awake, and the population is below the species' safety cap.
     */
    public boolean canReproduce(int creature) {
        SpeciesRef ref = creatures.get(creature);
        Age age = ages.get(creature);
        Reproduction reproduction = reproductions.get(creature);
        Needs n = needsStore.get(creature);
        Health health = healths.get(creature);
        if (ref == null || age == null || reproduction == null || n == null || health == null) {
            return false;
        }
        SpeciesDefinition.Reproduction rules = ref.stats().reproduction();
        return age.ageTicks >= SpeciesDefinition.secondsToTicks(rules.adultAgeSeconds())
                && reproduction.readyAtTick <= tick
                && n.hunger < rules.maxNeed() && n.thirst < rules.maxNeed()
                && !n.sleeping
                && health.hp >= rules.minHealth() * health.maxHp
                && speciesCounts.getOrDefault(ref.species, 0) < ref.species.stats().population().max();
    }

    /**
     * Nearest creature of the same species that can reproduce and is reachable over land, or -1.
     * Herd members only mate within their herd.
     */
    public int nearestMate() {
        if (nearestMate == -2) {
            float x = transform.position.x;
            float z = transform.position.z;
            int myRegion = pathfinder().regionAt(x, z);
            nearestMate = creatureGrid.nearest(x, z, species.senseRadius(), other -> {
                if (other == entity || !canReproduce(other) || creatures.get(other).species != kind) {
                    return false;
                }
                GroupMember mine = groupMembers.get(entity);
                if (mine != null) {
                    GroupMember theirs = groupMembers.get(other);
                    if (theirs == null || theirs.group != mine.group) {
                        return false;
                    }
                }
                Transform t = transforms.get(other);
                return pathfinder().regionAt(t.position.x, t.position.z) == myRegion;
            });
        }
        return nearestMate;
    }

    /**
     * Mates the current creature with {@code partner}: records the birth (offspring are spawned by the
     * reproduction system later this tick) and makes both parents pay the food cost and wait the cooldown.
     */
    public void mateWith(int partner) {
        Transform other = transforms.get(partner);
        births.add(entity, partner,
                (transform.position.x + other.position.x) * 0.5f,
                (transform.position.z + other.position.z) * 0.5f);
        payForOffspring(entity);
        payForOffspring(partner);
    }

    private void payForOffspring(int parent) {
        SpeciesDefinition.Reproduction rules = creatures.get(parent).stats().reproduction();
        Needs n = needsStore.get(parent);
        n.hunger = Math.min(1f, n.hunger + rules.hungerCost());
        Reproduction reproduction = reproductions.get(parent);
        GroupMember member = groupMembers.get(parent);
        Groups.Group herd = member != null ? groups.get(member.group) : null;
        float factor = herd != null && herd.tribe ? settlement.birthFactor() : 1f; // the tribe's mood (phase 9h)
        reproduction.readyAtTick = tick + Math.round(SpeciesDefinition.secondsToTicks(rules.cooldownSeconds()) * factor);
        reproduction.offspring += rules.litterSize();
    }

    // ---------------------------------------------------------------- movement

    /** Asks for a path to (x, z); the pathfinding system answers within a few ticks. */
    public void requestPath(float x, float z) {
        ai.targetX = x;
        ai.targetZ = z;
        ai.path = null;
        ai.pathStatus = AiState.PathStatus.PENDING;
        velocity.speed = 0;
        pathQueue.add(entity);
    }

    /**
     * Runs straight at a point without a path (a chase over open ground, phase 9f): no waiting for the
     * pathfinder while the target moves.
     *
     * @return false if the way was blocked last tick (then a path is needed)
     */
    public boolean steerTowards(float x, float z) {
        return steerTowards(x, z, 1f);
    }

    /** Like {@link #steerTowards(float, float)} at {@code speedFactor} times the normal speed (a sprint). */
    public boolean steerTowards(float x, float z, float speedFactor) {
        if (velocity.blocked) {
            velocity.blocked = false;
            return false;
        }
        float dx = x - transform.position.x;
        float dz = z - transform.position.z;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        ai.path = null;
        ai.pathStatus = AiState.PathStatus.NONE;
        if (dist < 1e-4f) {
            velocity.speed = 0;
            return true;
        }
        Genome genome = ecs.get(entity, Genome.class);
        float step = species.speedPerTick() * (genome != null ? genome.speed : 1f) * speedFactor;
        velocity.dirX = dx / dist;
        velocity.dirZ = dz / dist;
        velocity.speed = Math.min(step, dist);
        transform.yaw = (float) Math.atan2(velocity.dirX, velocity.dirZ);
        return true;
    }

    /** Stops walking and forgets the path (a pending request is skipped when served). */
    public void stopMoving() {
        ai.path = null;
        ai.pathStatus = AiState.PathStatus.NONE;
        velocity.speed = 0;
        velocity.blocked = false;
    }

    /** Turns the creature towards a point (e.g. the bush it eats from). */
    public void face(float x, float z) {
        float dx = x - transform.position.x;
        float dz = z - transform.position.z;
        if (dx * dx + dz * dz > 1e-6f) {
            transform.yaw = (float) Math.atan2(dx, dz);
        }
    }

    /** Linear response of a need above a threshold: 0 below it, {@code floor..1} from it up to 1. */
    public static float response(float need, float threshold, float floor) {
        if (need < threshold) {
            return 0f;
        }
        float t = threshold >= 1f ? 1f : (need - threshold) / (1f - threshold);
        return floor + (1f - floor) * Math.min(t, 1f);
    }
}

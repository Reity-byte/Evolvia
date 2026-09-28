package evolvia.ai;

import evolvia.components.Age;
import evolvia.components.Believer;
import evolvia.components.Fear;
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

    public ActionContext(Terrain terrain, Navigation navigation, PathQueue pathQueue,
                         SpatialGrid foodGrid, SpatialGrid waterGrid, SpatialGrid creatureGrid, Births births,
                         Random random, Groups groups, WorldClock clock, Refuges refuges, Nature nature) {
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
    }

    /** Where the creature should spend the night: its herd's refuge, or (alone) the nearest one it sees; or null. */
    public Refuges.Refuge shelter() {
        Groups.Group group = group();
        if (group != null) {
            return refuges.get(group.shelter);
        }
        return refuges.nearest(transform.position.x, transform.position.z, species.senseRadius(), false);
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
        this.species = ref.stats();
        nearestFood = -2;
        nearestWater = -2;
        foodInReach = -2;
        waterInReach = -2;
        nearestMate = -2;
        attackTarget = -2;
    }

    /** Makes the current creature a believer (it used something the god caused). */
    public void makeBeliever() {
        if (ecs.get(entity, Believer.class) == null) {
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
        if (mine == null || theirs == null) {
            return false;
        }
        return Groups.enemies(groups.get(mine.group), groups.get(theirs.group));
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
                return dx * dx + dz * dz <= territorySq && isEnemy(other) && reachable(other, myRegion);
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
        SpeciesDefinition.Combat combat = species.combat();
        Health health = healths.get(target);
        health.hp -= SpeciesDefinition.perTick(combat.damagePerSecond());
        UnderAttack attacked = underAttacks.get(target);
        if (attacked == null) {
            attacked = new UnderAttack();
            ecs.add(target, attacked);
        }
        attacked.attacker = entity;
        attacked.untilTick = tick + 3 * Time.TICKS_PER_SECOND;
        Groups.Group mine = group();
        if (health.hp <= 0f) {
            if (mine != null && mine.player) {
                groups.recordPlayerVictory();
            }
            return true; // the aging system removes it this tick ("in a fight")
        }
        float share = health.hp / health.maxHp;
        if (share <= combat.surrenderHealth() && mine != null) {
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
                && creatures.size() < ref.species.stats().population().max();
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
        reproduction.readyAtTick = tick + SpeciesDefinition.secondsToTicks(rules.cooldownSeconds());
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

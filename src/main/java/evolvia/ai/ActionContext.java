package evolvia.ai;

import evolvia.components.AiState;
import evolvia.components.Needs;
import evolvia.components.ResourceNode;
import evolvia.components.Transform;
import evolvia.components.Velocity;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.evolution.SpeciesDefinition;
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
    public final Pathfinder pathfinder;
    public final PathQueue pathQueue;
    public final SpatialGrid foodGrid;
    public final SpatialGrid waterGrid;
    public final Random random;

    public EcsWorld ecs;
    public ComponentStore<ResourceNode> resources;
    public ComponentStore<Transform> transforms;
    public int tick;

    public int entity;
    public Transform transform;
    public Velocity velocity;
    public Needs needs;
    public AiState ai;
    public SpeciesDefinition species;

    // Per-creature query caches (reset by bind); -2 = not computed yet.
    private int nearestFood;
    private int nearestWater;
    private int foodInReach;
    private int waterInReach;

    public ActionContext(Terrain terrain, Pathfinder pathfinder, PathQueue pathQueue,
                         SpatialGrid foodGrid, SpatialGrid waterGrid, Random random) {
        this.terrain = terrain;
        this.pathfinder = pathfinder;
        this.pathQueue = pathQueue;
        this.foodGrid = foodGrid;
        this.waterGrid = waterGrid;
        this.random = random;
    }

    /** Prepares the context for one tick. */
    public void beginTick(EcsWorld ecs, int tick) {
        this.ecs = ecs;
        this.tick = tick;
        this.resources = ecs.store(ResourceNode.class);
        this.transforms = ecs.store(Transform.class);
    }

    /** Points the context at one creature. */
    public void bind(int entity, Transform transform, Velocity velocity, Needs needs, AiState ai, SpeciesDefinition species) {
        this.entity = entity;
        this.transform = transform;
        this.velocity = velocity;
        this.needs = needs;
        this.ai = ai;
        this.species = species;
        nearestFood = -2;
        nearestWater = -2;
        foodInReach = -2;
        waterInReach = -2;
    }

    // ---------------------------------------------------------------- resource queries

    /** Nearest usable node of a kind within the sense radius and reachable over land, or -1. */
    public int nearest(ResourceKind kind) {
        if (kind == ResourceKind.FOOD) {
            if (nearestFood == -2) {
                nearestFood = findNode(kind, species.senseRadius(), true);
            }
            return nearestFood;
        }
        if (nearestWater == -2) {
            nearestWater = findNode(kind, species.senseRadius(), true);
        }
        return nearestWater;
    }

    /** Usable node of a kind within {@link #REACH}, or -1. */
    public int inReach(ResourceKind kind) {
        if (kind == ResourceKind.FOOD) {
            if (foodInReach == -2) {
                foodInReach = findNode(kind, REACH, false);
            }
            return foodInReach;
        }
        if (waterInReach == -2) {
            waterInReach = findNode(kind, REACH, false);
        }
        return waterInReach;
    }

    /** True if the node exists and still has something to take. */
    public boolean isUsable(int node) {
        ResourceNode resource = resources.get(node);
        return resource != null && (resource.type.kind() == ResourceKind.WATER || resource.amount >= 1f);
    }

    private int findNode(ResourceKind kind, float radius, boolean sameRegion) {
        float x = transform.position.x;
        float z = transform.position.z;
        int myRegion = sameRegion ? pathfinder.regionAt(x, z) : -1;
        SpatialGrid grid = kind == ResourceKind.FOOD ? foodGrid : waterGrid;
        return grid.nearest(x, z, radius, node -> {
            if (!isUsable(node)) {
                return false;
            }
            if (!sameRegion) {
                return true;
            }
            Transform t = transforms.get(node);
            return pathfinder.regionAt(t.position.x, t.position.z) == myRegion;
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

package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.Memory;
import evolvia.components.Transform;
import evolvia.world.Groups;
import evolvia.world.ResourceKind;

/**
 * SeekFood / SeekWater: when hungry (thirsty), walk to the nearest reachable food (water) node
 * that the species can use. Ends when the node is within reach; eating (drinking) is a separate
 * action that then scores high. Species with memory that see nothing walk back to where they last
 * ate (drank) and look again there.
 */
public final class SeekResourceAction implements Action {

    /** Target entity value meaning "going to a remembered place, not to a node". */
    private static final int REMEMBERED_PLACE = -3;

    private final ResourceKind kind;

    public SeekResourceAction(ResourceKind kind) {
        this.kind = kind;
    }

    @Override
    public ActionType type() {
        return kind == ResourceKind.FOOD ? ActionType.SEEK_FOOD : ActionType.SEEK_WATER;
    }

    @Override
    public float score(ActionContext c) {
        float need = kind == ResourceKind.FOOD ? c.needs.hunger : c.needs.thirst;
        float score = ActionContext.response(need, c.species.ai().needThreshold(), 0.2f);
        if (score <= 0f || c.inReach(kind) >= 0) {
            return 0f; // not needed, or already there (then eating / drinking applies)
        }
        if (c.ai.action == type() && (c.ai.targetEntity == REMEMBERED_PLACE || c.isUsable(c.ai.targetEntity))) {
            return score; // keep going without searching again
        }
        if (c.nearest(kind) >= 0 || rememberedPlace(c) != null) {
            return score;
        }
        return 0f;
    }

    @Override
    public void start(ActionContext c) {
        int node = c.nearest(kind);
        if (node >= 0) {
            c.ai.targetEntity = node;
            Transform target = c.transforms.get(node);
            c.requestPath(target.position.x, target.position.z);
            return;
        }
        float[] place = rememberedPlace(c);
        if (place != null) {
            c.ai.targetEntity = REMEMBERED_PLACE;
            c.requestPath(place[0], place[1]);
        } else {
            c.ai.targetEntity = -1; // update() fails
        }
    }

    @Override
    public Status update(ActionContext c) {
        int node = c.ai.targetEntity;
        if (node == REMEMBERED_PLACE) {
            return switch (c.ai.pathStatus) {
                case PENDING, FOLLOWING -> Status.RUNNING;
                case ARRIVED -> {
                    if (c.nearest(kind) < 0 && c.inReach(kind) < 0) {
                        forget(c); // nothing here any more
                    }
                    yield Status.DONE; // re-evaluate: seeking the node now in sight, or eating
                }
                case FAILED, NONE -> {
                    forget(c);
                    yield Status.FAILED;
                }
            };
        }
        if (node < 0) {
            return Status.FAILED;
        }
        if (!c.isUsable(node)) {
            return Status.DONE; // eaten by others: re-evaluate and pick another node
        }
        if (c.distanceTo(node) <= ActionContext.REACH) {
            return Status.DONE;
        }
        return switch (c.ai.pathStatus) {
            case PENDING, FOLLOWING -> Status.RUNNING;
            case ARRIVED -> Status.DONE;
            case FAILED, NONE -> Status.FAILED;
        };
    }

    /**
     * Remembered place for this resource kind (x, z): the creature's own memory, otherwise its herd's
     * shared memory; null without the memory ability. Herd members that are not in urgent need only use
     * the herd's memory, so they do not leave the herd for places only they know.
     */
    private float[] rememberedPlace(ActionContext c) {
        Memory memory = c.memory();
        if (memory == null) {
            return null;
        }
        float need = kind == ResourceKind.FOOD ? c.needs.hunger : c.needs.thirst;
        boolean herdOnly = c.leader() != null && need < c.species.groups().urgentNeed();
        if (!herdOnly) {
            if (kind == ResourceKind.FOOD && memory.knowsFood) {
                return new float[]{memory.foodX, memory.foodZ};
            }
            if (kind == ResourceKind.WATER && memory.knowsWater) {
                return new float[]{memory.waterX, memory.waterZ};
            }
        }
        Groups.Group group = c.group();
        if (group == null) {
            return null;
        }
        if (kind == ResourceKind.FOOD) {
            return group.knowsFood ? new float[]{group.foodX, group.foodZ} : null;
        }
        return group.knowsWater ? new float[]{group.waterX, group.waterZ} : null;
    }

    /** Nothing at the remembered place: forget it (the herd too, if that is where it came from). */
    private void forget(ActionContext c) {
        Memory memory = c.memory();
        if (memory == null) {
            return;
        }
        Groups.Group group = c.group();
        float need = kind == ResourceKind.FOOD ? c.needs.hunger : c.needs.thirst;
        boolean herdOnly = c.leader() != null && need < c.species.groups().urgentNeed();
        if (kind == ResourceKind.FOOD) {
            if (memory.knowsFood && !herdOnly) {
                memory.knowsFood = false;
            } else if (group != null) {
                group.knowsFood = false;
            }
        } else if (memory.knowsWater && !herdOnly) {
            memory.knowsWater = false;
        } else if (group != null) {
            group.knowsWater = false;
        }
    }
}

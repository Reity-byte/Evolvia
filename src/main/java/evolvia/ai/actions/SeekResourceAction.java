package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.Transform;
import evolvia.world.ResourceKind;

/**
 * SeekFood / SeekWater: when hungry (thirsty), walk to the nearest reachable food (water) node.
 * Ends when the node is within reach; eating (drinking) is a separate action that then scores high.
 */
public final class SeekResourceAction implements Action {

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
        if (c.ai.action == type() && c.isUsable(c.ai.targetEntity)) {
            return score; // keep going to the chosen node without searching again
        }
        return c.nearest(kind) >= 0 ? score : 0f;
    }

    @Override
    public void start(ActionContext c) {
        int node = c.nearest(kind);
        c.ai.targetEntity = node;
        if (node < 0) {
            return; // update() fails
        }
        Transform target = c.transforms.get(node);
        c.requestPath(target.position.x, target.position.z);
    }

    @Override
    public Status update(ActionContext c) {
        int node = c.ai.targetEntity;
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
}

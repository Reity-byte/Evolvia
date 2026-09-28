package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.AiState;
import evolvia.components.Carrying;
import evolvia.components.ResourceNode;
import evolvia.components.Transform;
import evolvia.evolution.SpeciesDefinition;

/**
 * Gather (phase 9g): a fed adult with tools walks to the nearest tree or rock the camp needs, works it for
 * {@code gathering.workSeconds} and takes one unit, which it then carries ({@link DeliverAction}).
 */
public final class GatherAction implements Action {

    /** Close enough to work a tree or rock (they are bigger than a bush). */
    private static final float WORK_REACH = ActionContext.REACH + 0.6f;

    @Override
    public ActionType type() {
        return ActionType.GATHER;
    }

    @Override
    public float score(ActionContext c) {
        return c.canGather() && c.gatherTarget() >= 0 ? c.gathering.score() : 0f;
    }

    @Override
    public void start(ActionContext c) {
        int node = c.gatherTarget();
        c.ai.targetEntity = node;
        c.ai.waitTicks = -1; // not working yet
        Transform t = c.transforms.get(node);
        c.requestPath(t.position.x, t.position.z);
    }

    @Override
    public Status update(ActionContext c) {
        int node = c.ai.targetEntity;
        ResourceNode material = node >= 0 ? c.resources.get(node) : null;
        if (material == null || material.amount < 1f) {
            return Status.DONE; // someone else took the last of it
        }
        Transform t = c.transforms.get(node);
        if (c.ai.waitTicks < 0) {
            if (c.distanceTo(node) <= WORK_REACH || c.ai.pathStatus == AiState.PathStatus.ARRIVED) {
                c.stopMoving();
                c.face(t.position.x, t.position.z);
                c.ai.waitTicks = SpeciesDefinition.secondsToTicks(c.gathering.workSeconds());
                return Status.RUNNING;
            }
            return c.ai.pathStatus == AiState.PathStatus.FAILED ? Status.FAILED : Status.RUNNING;
        }
        if (--c.ai.waitTicks > 0) {
            return Status.RUNNING; // chopping, breaking stone
        }
        material.amount -= 1f;
        c.ecs.add(c.entity, new Carrying(material.type.material(), 1f));
        return Status.DONE;
    }
}

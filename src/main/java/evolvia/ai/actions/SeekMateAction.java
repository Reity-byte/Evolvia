package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.AiState.PathStatus;
import evolvia.components.Transform;

/**
 * SeekMate: a creature ready to reproduce walks to the nearest ready partner; when they meet, both
 * become parents (see {@link ActionContext#mateWith}). Scores below urgent needs, so only well-fed
 * creatures reproduce - food limits population growth.
 */
public final class SeekMateAction implements Action {

    /** Re-aim at the (moving) partner this often. */
    private static final int RETARGET_TICKS = 20;
    /** Partner may move this far from where we are heading before we re-aim. */
    private static final float RETARGET_DISTANCE = 3f;
    private static final int MAX_RETARGETS = 8;

    @Override
    public ActionType type() {
        return ActionType.SEEK_MATE;
    }

    @Override
    public float score(ActionContext c) {
        if (!c.canReproduce(c.entity)) {
            return 0f;
        }
        if (c.ai.action == ActionType.SEEK_MATE && c.ai.targetEntity >= 0 && c.canReproduce(c.ai.targetEntity)) {
            return c.species.reproduction().mateScore();
        }
        return c.nearestMate() >= 0 ? c.species.reproduction().mateScore() : 0f;
    }

    @Override
    public void start(ActionContext c) {
        int mate = c.nearestMate();
        c.ai.targetEntity = mate;
        if (mate >= 0) {
            Transform t = c.transforms.get(mate);
            c.requestPath(t.position.x, t.position.z);
        }
    }

    @Override
    public Status update(ActionContext c) {
        int mate = c.ai.targetEntity;
        if (mate < 0) {
            return Status.FAILED;
        }
        if (!c.canReproduce(c.entity) || !c.canReproduce(mate)) {
            return Status.DONE; // the partner mated with someone else (or we did): look again later
        }
        if (c.distanceTo(mate) <= ActionContext.REACH) {
            c.stopMoving();
            c.mateWith(mate);
            return Status.DONE;
        }
        if (c.ai.pathStatus == PathStatus.FAILED) {
            return Status.FAILED;
        }
        Transform t = c.transforms.get(mate);
        boolean arrivedButMoved = c.ai.pathStatus == PathStatus.ARRIVED;
        boolean drifted = c.ai.actionTicks % RETARGET_TICKS == 0 && c.ai.pathStatus == PathStatus.FOLLOWING
                && Math.hypot(t.position.x - c.ai.targetX, t.position.z - c.ai.targetZ) > RETARGET_DISTANCE;
        if (arrivedButMoved || drifted) {
            if (++c.ai.pathRetries > MAX_RETARGETS) {
                return Status.FAILED;
            }
            c.requestPath(t.position.x, t.position.z);
        }
        return Status.RUNNING;
    }
}

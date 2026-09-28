package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.Transform;
import evolvia.evolution.SpeciesDefinition;

/**
 * Attack (phase 9c): walks to an enemy (another herd; the player's herds never fight each other) and
 * hits it until it runs, gives up or dies. Chosen when the herd defends its territory while hungry,
 * when the creature itself is hit (it fights back while healthy), when a herd mate is attacked (with the
 * herd bonus) or when the god ordered the herd to attack. The hits themselves are in
 * {@link ActionContext#hit(int)}.
 */
public final class AttackAction implements Action {

    /** How often the path is renewed while the target moves (ticks). */
    private static final int REPATH_TICKS = 10;
    /** An attack gives up after this long (ticks). */
    private static final int MAX_TICKS = 30 * 20;

    @Override
    public ActionType type() {
        return ActionType.ATTACK;
    }

    @Override
    public float score(ActionContext c) {
        return c.attackTarget() >= 0 ? c.attackScore() : 0f;
    }

    @Override
    public void start(ActionContext c) {
        int target = c.attackTarget();
        c.ai.targetEntity = target;
        Transform t = c.transforms.get(target);
        c.requestPath(t.position.x, t.position.z);
    }

    @Override
    public Status update(ActionContext c) {
        int target = c.ai.targetEntity;
        if (!c.isEnemy(target) || c.ai.actionTicks > MAX_TICKS) {
            return Status.DONE; // dead, fled out of reach, surrendered (now an ally) or took too long
        }
        Transform t = c.transforms.get(target);
        SpeciesDefinition.Combat combat = c.species.combat();
        if (c.distanceTo(target) <= combat.attackRange()) {
            c.stopMoving();
            c.face(t.position.x, t.position.z);
            return c.hit(target) ? Status.DONE : Status.RUNNING;
        }
        if (c.ai.pathStatus == evolvia.components.AiState.PathStatus.FAILED) {
            return Status.FAILED;
        }
        if (c.ai.actionTicks % REPATH_TICKS == 0 || c.ai.pathStatus == evolvia.components.AiState.PathStatus.ARRIVED) {
            c.requestPath(t.position.x, t.position.z); // the target moves
        }
        return Status.RUNNING;
    }
}

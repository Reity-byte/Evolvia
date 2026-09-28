package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.AiState;
import evolvia.components.Role;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Settlement;

/**
 * Build (phase 9h): a tribe member with the builder role walks to the paid site and works on it; every builder
 * adds {@code 1 / buildSeconds} of progress per second (faster under an evil god).
 */
public final class BuildAction implements Action {

    /** Close enough to the site to work on it. */
    private static final float SITE_REACH = 2.2f;

    @Override
    public ActionType type() {
        return ActionType.BUILD;
    }

    @Override
    public float score(ActionContext c) {
        Role role = c.ecs.get(c.entity, Role.class);
        if (role == null || !role.builder || c.restTime() || c.carrying() != null
                || Math.max(c.needs.hunger, c.needs.thirst) >= c.gathering.maxNeed()) {
            return 0f;
        }
        return c.settlement.activeSite() != null ? c.tribeRules.buildScore() : 0f;
    }

    @Override
    public void start(ActionContext c) {
        Settlement.Building site = c.settlement.activeSite();
        c.ai.targetEntity = site.id; // a building id, not an entity
        c.requestPath(site.x, site.z);
    }

    @Override
    public Status update(ActionContext c) {
        Settlement.Building site = c.settlement.get(c.ai.targetEntity);
        if (site == null || site.done() || site != c.settlement.activeSite()) {
            return Status.DONE;
        }
        float dx = site.x - c.transform.position.x;
        float dz = site.z - c.transform.position.z;
        if (dx * dx + dz * dz <= SITE_REACH * SITE_REACH || c.ai.pathStatus == AiState.PathStatus.ARRIVED) {
            if (c.ai.pathStatus != AiState.PathStatus.NONE) {
                c.stopMoving();
            }
            c.face(site.x, site.z);
            boolean wasDone = site.done();
            site.progress = Math.min(1f, site.progress
                    + c.workFactor() / SpeciesDefinition.secondsToTicks(site.type.buildSeconds()));
            if (site.done() && !wasDone && c.science != null) {
                c.rewardKnowledge(c.science.rules().perBuilding()); // a finished building teaches (phase 10b)
            }
            return site.done() ? Status.DONE : Status.RUNNING;
        }
        return c.ai.pathStatus == AiState.PathStatus.FAILED ? Status.FAILED : Status.RUNNING;
    }
}

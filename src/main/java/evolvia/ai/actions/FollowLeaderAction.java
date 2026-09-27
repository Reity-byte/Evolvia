package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.Transform;
import evolvia.evolution.SpeciesDefinition;

/**
 * FollowLeader (phase 9a): a herd member that is too far from its leader walks back to it. Scores
 * above wandering but below needs, so hunger, thirst and sleep still come first.
 */
public final class FollowLeaderAction implements Action {

    private static final float TWO_PI = (float) (Math.PI * 2);

    @Override
    public ActionType type() {
        return ActionType.FOLLOW_LEADER;
    }

    @Override
    public float score(ActionContext c) {
        Transform leader = c.leader();
        if (leader == null) {
            return 0f;
        }
        SpeciesDefinition.Groups rules = c.species.groups();
        float d = distance(c, leader);
        if (d <= rules.followDistance()) {
            return 0f;
        }
        float far = Math.min(1f, (d - rules.followDistance()) / (rules.leaveDistance() - rules.followDistance()));
        return rules.followScore() * (1f + 0.5f * far);
    }

    @Override
    public void start(ActionContext c) {
        Transform leader = c.leader();
        float radius = c.species.groups().followDistance() * 0.5f;
        int myRegion = c.pathfinder().regionAt(c.transform.position.x, c.transform.position.z);
        for (int attempt = 0; attempt < 4; attempt++) {
            float angle = c.random.nextFloat() * TWO_PI;
            float distance = radius * c.random.nextFloat();
            float x = leader.position.x + (float) Math.sin(angle) * distance;
            float z = leader.position.z + (float) Math.cos(angle) * distance;
            if (myRegion >= 0 && c.pathfinder().regionAt(x, z) == myRegion) {
                c.requestPath(x, z);
                return;
            }
        }
        // Leader unreachable (e.g. across water): update() fails, the cooldown stops retrying at once.
    }

    @Override
    public Status update(ActionContext c) {
        if (c.leader() == null) {
            return Status.DONE;
        }
        return switch (c.ai.pathStatus) {
            case PENDING, FOLLOWING -> Status.RUNNING;
            case ARRIVED -> Status.DONE;
            case FAILED, NONE -> Status.FAILED;
        };
    }

    private static float distance(ActionContext c, Transform leader) {
        float dx = leader.position.x - c.transform.position.x;
        float dz = leader.position.z - c.transform.position.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }
}

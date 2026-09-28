package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.world.Refuges;

/**
 * SeekShelter (phase 9d): in the evening and at night a creature goes to its herd's refuge (or the
 * nearest one it sees) to sleep there sheltered. Scores above following and wandering, below urgent needs.
 */
public final class SeekShelterAction implements Action {

    /** Utility of going to the refuge. */
    public static final float SCORE = 0.55f;
    private static final float TWO_PI = (float) (Math.PI * 2);

    @Override
    public ActionType type() {
        return ActionType.SEEK_SHELTER;
    }

    @Override
    public float score(ActionContext c) {
        if (!c.clock.isShelterTime(c.tick) || Math.max(c.needs.hunger, c.needs.thirst) >= SleepAction.CRITICAL_NEED) {
            return 0f;
        }
        Refuges.Refuge refuge = c.shelter();
        if (refuge == null || refuge.contains(c.transform.position.x, c.transform.position.z)) {
            return 0f; // none known, or already there
        }
        return SCORE;
    }

    @Override
    public void start(ActionContext c) {
        Refuges.Refuge refuge = c.shelter();
        int myRegion = c.pathfinder().regionAt(c.transform.position.x, c.transform.position.z);
        for (int attempt = 0; attempt < 6; attempt++) {
            float angle = c.random.nextFloat() * TWO_PI;
            float distance = refuge.radius() * 0.6f * c.random.nextFloat();
            float x = refuge.x + (float) Math.sin(angle) * distance;
            float z = refuge.z + (float) Math.cos(angle) * distance;
            if (myRegion >= 0 && c.pathfinder().regionAt(x, z) == myRegion) {
                c.requestPath(x, z);
                return;
            }
        }
        // Unreachable refuge: update() fails and the cooldown keeps the creature from retrying at once.
    }

    @Override
    public Status update(ActionContext c) {
        return switch (c.ai.pathStatus) {
            case PENDING, FOLLOWING -> Status.RUNNING;
            case ARRIVED -> Status.DONE;
            case FAILED, NONE -> Status.FAILED;
        };
    }
}

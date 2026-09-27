package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.Fear;

/**
 * Runs away from a lightning strike (DESIGN.md §8): walks to a reachable point in the direction away
 * from the danger while the fear lasts. Scores above every need, so it interrupts anything.
 */
public final class FleeAction implements Action {

    /** Above the 0..1 range of the needs: fear beats hunger, thirst and sleep. */
    public static final float SCORE = 1.5f;
    /** Directions tried, as angle offsets from straight away from the danger (radians). */
    private static final float[] ANGLES = {0f, 0.5f, -0.5f, 1.0f, -1.0f, 1.5f, -1.5f};

    @Override
    public ActionType type() {
        return ActionType.FLEE;
    }

    @Override
    public float score(ActionContext c) {
        Fear fear = c.fear();
        return fear != null && fear.isActive(c.tick) ? SCORE : 0f;
    }

    @Override
    public void start(ActionContext c) {
        Fear fear = c.fear();
        float x = c.transform.position.x;
        float z = c.transform.position.z;
        float away = (float) Math.atan2(x - fear.fromX, z - fear.fromZ);
        if (x == fear.fromX && z == fear.fromZ) {
            away = c.random.nextFloat() * (float) (Math.PI * 2);
        }
        int myRegion = c.pathfinder().regionAt(x, z);
        for (float offset : ANGLES) {
            for (float fraction : new float[]{1f, 0.6f, 0.3f}) {
                float distance = fear.distance * fraction;
                float tx = x + (float) Math.sin(away + offset) * distance;
                float tz = z + (float) Math.cos(away + offset) * distance;
                if (myRegion >= 0 && c.pathfinder().regionAt(tx, tz) == myRegion) {
                    c.requestPath(tx, tz);
                    return;
                }
            }
        }
        // Nowhere to run (e.g. a tiny island): the action fails and the creature stays scared in place.
    }

    @Override
    public Status update(ActionContext c) {
        Fear fear = c.fear();
        if (fear == null || !fear.isActive(c.tick)) {
            return Status.DONE;
        }
        return switch (c.ai.pathStatus) {
            case PENDING, FOLLOWING -> Status.RUNNING;
            case ARRIVED -> Status.DONE; // still scared: re-evaluating picks Flee again from the new spot
            case FAILED, NONE -> Status.FAILED;
        };
    }
}

package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;

/** Sleep in place until rested; needs grow slower meanwhile (see NeedsSystem). */
public final class SleepAction implements Action {

    /** Wake up at this energy. */
    public static final float RESTED = 0.98f;
    /** Hunger or thirst at which sleeping is not an option. */
    public static final float CRITICAL_NEED = 0.85f;

    @Override
    public ActionType type() {
        return ActionType.SLEEP;
    }

    @Override
    public float score(ActionContext c) {
        if (Math.max(c.needs.hunger, c.needs.thirst) >= CRITICAL_NEED) {
            return 0f; // survival first: don't sleep (or keep sleeping) while starving or dying of thirst
        }
        float tiredness = 1f - c.needs.energy;
        if (c.needs.sleeping) {
            return 0.3f + 0.7f * tiredness; // keep sleeping unless something is much more urgent
        }
        return ActionContext.response(tiredness, c.species.ai().sleepThreshold(), 0.2f);
    }

    @Override
    public void start(ActionContext c) {
        c.stopMoving();
        c.needs.sleeping = true;
    }

    @Override
    public Status update(ActionContext c) {
        return c.needs.energy >= RESTED ? Status.DONE : Status.RUNNING;
    }

    @Override
    public void stop(ActionContext c) {
        c.stopMoving();
        c.needs.sleeping = false;
    }
}

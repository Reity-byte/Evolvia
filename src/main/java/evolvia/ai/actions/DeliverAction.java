package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.AiState;
import evolvia.components.Carrying;
import evolvia.world.Groups;

/**
 * Deliver (phase 9g): whoever carries a load takes it to the herd's camp and adds it to the stock. The first
 * delivery founds the camp where the herd's home is and settles the herd there. A creature without a herd
 * drops its load.
 */
public final class DeliverAction implements Action {

    /** Close enough to the camp to put the load down. */
    private static final float CAMP_REACH = 2.5f;

    @Override
    public ActionType type() {
        return ActionType.DELIVER;
    }

    @Override
    public float score(ActionContext c) {
        if (c.carrying() == null || Math.max(c.needs.hunger, c.needs.thirst) >= SleepAction.CRITICAL_NEED) {
            return 0f;
        }
        return c.gathering.deliverScore();
    }

    @Override
    public void start(ActionContext c) {
        Groups.Group group = c.group();
        if (group == null) {
            return;
        }
        float[] camp = c.campSpot(group);
        c.requestPath(camp[0], camp[1]);
    }

    @Override
    public Status update(ActionContext c) {
        Carrying load = c.carrying();
        Groups.Group group = c.group();
        if (load == null) {
            return Status.DONE;
        }
        if (group == null) {
            c.ecs.store(Carrying.class).remove(c.entity); // no camp to go to: dropped
            return Status.DONE;
        }
        float[] camp = c.campSpot(group);
        float dx = camp[0] - c.transform.position.x;
        float dz = camp[1] - c.transform.position.z;
        if (dx * dx + dz * dz <= CAMP_REACH * CAMP_REACH || c.ai.pathStatus == AiState.PathStatus.ARRIVED) {
            c.stopMoving();
            if (!group.hasCamp) {
                group.hasCamp = true; // the first load founds the camp
                group.campX = camp[0];
                group.campZ = camp[1];
                group.settled = true;
                group.homeX = camp[0];
                group.homeZ = camp[1];
            }
            group.stock.merge(load.material, load.amount, Float::sum);
            c.ecs.store(Carrying.class).remove(c.entity);
            if (c.science != null) {
                c.rewardKnowledge(c.science.rules().perDelivery());
            }
            return Status.DONE;
        }
        return c.ai.pathStatus == AiState.PathStatus.FAILED ? Status.FAILED : Status.RUNNING;
    }
}

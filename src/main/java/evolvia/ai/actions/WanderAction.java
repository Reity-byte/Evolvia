package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.Transform;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Groups;

/** Nothing better to do: walk to a random reachable spot nearby, then pause a while. */
public final class WanderAction implements Action {

    private static final int TARGET_ATTEMPTS = 8;
    private static final float TWO_PI = (float) (Math.PI * 2);

    @Override
    public ActionType type() {
        return ActionType.WANDER;
    }

    @Override
    public float score(ActionContext c) {
        return c.species.ai().wanderScore();
    }

    @Override
    public void start(ActionContext c) {
        SpeciesDefinition.Wander wander = c.species.wander();
        // Hungry or thirsty but nothing in sight (otherwise seeking would have won): explore further.
        float threshold = c.species.ai().needThreshold();
        boolean exploring = c.needs.hunger >= threshold || c.needs.thirst >= threshold;
        float radius = wander.radius() * (exploring ? c.species.ai().exploreRadiusFactor() : 1f);
        // Looking for water: of several candidates prefer the lowest one - water collects in low ground.
        boolean seekLowGround = c.needs.thirst >= threshold;
        int myRegion = c.pathfinder().regionAt(c.transform.position.x, c.transform.position.z);
        // Herd members wander around their leader, so the herd stays together.
        Transform leader = c.leader();
        float centerX = leader != null ? leader.position.x : c.transform.position.x;
        float centerZ = leader != null ? leader.position.z : c.transform.position.z;
        Groups.Group herd = c.group();
        if (leader == null && herd != null && herd.settled) { // a settled herd's leader stays around home
            centerX = herd.homeX;
            centerZ = herd.homeZ;
        }
        float bestX = 0f;
        float bestZ = 0f;
        float bestHeight = Float.POSITIVE_INFINITY;
        for (int attempt = 0; attempt < TARGET_ATTEMPTS; attempt++) {
            float angle = c.random.nextFloat() * TWO_PI;
            float distance = radius * (0.3f + 0.7f * c.random.nextFloat());
            float x = centerX + (float) Math.sin(angle) * distance;
            float z = centerZ + (float) Math.cos(angle) * distance;
            if (myRegion < 0 || c.pathfinder().regionAt(x, z) != myRegion) {
                continue;
            }
            if (!seekLowGround) {
                c.requestPath(x, z);
                return;
            }
            float height = c.terrain.heightAt(x, z);
            if (height < bestHeight) {
                bestHeight = height;
                bestX = x;
                bestZ = z;
            }
        }
        if (bestHeight < Float.POSITIVE_INFINITY) {
            c.requestPath(bestX, bestZ);
            return;
        }
        c.ai.waitTicks = pauseTicks(c); // nowhere to go: just stand for a while
    }

    @Override
    public Status update(ActionContext c) {
        switch (c.ai.pathStatus) {
            case PENDING, FOLLOWING -> {
                return Status.RUNNING;
            }
            case FAILED -> {
                return Status.FAILED;
            }
            case ARRIVED -> {
                c.stopMoving();
                c.ai.waitTicks = pauseTicks(c);
            }
            case NONE -> {
            }
        }
        if (c.ai.waitTicks > 0) {
            c.ai.waitTicks--;
            return Status.RUNNING;
        }
        return Status.DONE;
    }

    private static int pauseTicks(ActionContext c) {
        SpeciesDefinition.Wander wander = c.species.wander();
        int min = SpeciesDefinition.secondsToTicks(wander.pauseMinSeconds());
        int max = SpeciesDefinition.secondsToTicks(wander.pauseMaxSeconds());
        return max > min ? min + c.random.nextInt(max - min + 1) : min;
    }
}

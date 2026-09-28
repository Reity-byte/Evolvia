package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.AiState;
import evolvia.components.Transform;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.ResourceKind;

/**
 * Hunt (phase 9f): a hungry meat eater with no meat at hand chases the nearest prey it sees and hits it
 * like in a fight until it dies (the carcass is then eaten by the usual food actions). The prey and its
 * herd run from the hunter; a chase ends after {@code hunting.chaseSeconds}. Night hunters are keener at
 * night.
 */
public final class HuntAction implements Action {

    private static final int REPATH_TICKS = 10;
    private static final int SCARE_TICKS = 20;
    /** Closer than this the hunter runs straight at the prey instead of following a path. */
    private static final float SPRINT_DISTANCE = 14f;
    /** A hunter bites from a little further than a fighter reaches: running bodies do not overlap. */
    private static final float EXTRA_REACH = 0.5f;
    /** A hunter sprints this much faster for the first {@link #SPRINT_SECONDS} of a chase: an ambush works, a long chase does not. */
    private static final float SPRINT_FACTOR = 1.3f;
    private static final float SPRINT_SECONDS = 15f;

    @Override
    public ActionType type() {
        return ActionType.HUNT;
    }

    @Override
    public float score(ActionContext c) {
        float threshold = c.hunting.hungerThreshold();
        if (c.needs.hunger < threshold || c.species.diet().meatNutrition() <= 0f) {
            return 0f;
        }
        if (c.nearest(ResourceKind.FOOD) >= 0) {
            return 0f; // food (a carcass, or plants it eats) is at hand: eat instead
        }
        if (c.huntTarget() < 0) {
            return 0f;
        }
        float score = c.hunting.score() + (c.needs.hunger - threshold) * 0.5f;
        if (c.kind.isAnimal() && c.kind.animal().nocturnal() && c.clock.isNight(c.tick)) {
            score += c.hunting.nightBonus();
        }
        return Math.min(0.97f, score);
    }

    @Override
    public void start(ActionContext c) {
        int target = c.huntTarget();
        c.ai.targetEntity = target;
        Transform t = c.transforms.get(target);
        c.requestPath(t.position.x, t.position.z);
    }

    @Override
    public Status update(ActionContext c) {
        int target = c.ai.targetEntity;
        if (!c.isPrey(target)) {
            return Status.DONE; // caught (dead) or gone
        }
        if (c.ai.actionTicks > SpeciesDefinition.secondsToTicks(c.hunting.chaseSeconds())) {
            return Status.FAILED; // it got away: rest a moment before the next chase
        }
        if (c.ai.actionTicks % SCARE_TICKS == 0 && c.distanceTo(target) <= c.hunting.scareRadius()) {
            c.scarePrey(target);
        }
        Transform t = c.transforms.get(target);
        if (c.distanceTo(target) <= c.species.combat().attackRange() + EXTRA_REACH) {
            c.stopMoving();
            c.face(t.position.x, t.position.z);
            return c.hit(target, c.hunting.biteFactor()) ? Status.DONE : Status.RUNNING;
        }
        float speed = c.ai.actionTicks < SpeciesDefinition.secondsToTicks(SPRINT_SECONDS) ? SPRINT_FACTOR : 1f;
        boolean onPath = c.ai.pathStatus == AiState.PathStatus.PENDING || c.ai.pathStatus == AiState.PathStatus.FOLLOWING;
        if (!onPath && c.distanceTo(target) <= SPRINT_DISTANCE && c.steerTowards(t.position.x, t.position.z, speed)) {
            return Status.RUNNING; // open ground: straight at it (a path is used once something is in the way)
        }
        if (c.ai.pathStatus == AiState.PathStatus.FAILED) {
            return Status.FAILED;
        }
        if (c.ai.pathStatus == AiState.PathStatus.NONE) {
            c.requestPath(t.position.x, t.position.z); // blocked on the way: go round
            return Status.RUNNING;
        }
        if (c.ai.pathStatus == AiState.PathStatus.ARRIVED
                || (c.ai.actionTicks % REPATH_TICKS == 0 && c.distanceTo(target) > SPRINT_DISTANCE)) {
            c.requestPath(t.position.x, t.position.z); // the prey runs
        }
        return Status.RUNNING;
    }
}

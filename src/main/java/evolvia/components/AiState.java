package evolvia.components;

import evolvia.ai.ActionType;
import evolvia.ai.Path;

/** Utility AI state of a creature: current action, its target and the path to it. */
public final class AiState {

    public enum PathStatus {
        /** No path wanted. */
        NONE,
        /** Waiting in the pathfinding queue. */
        PENDING,
        /** Walking along {@link #path}. */
        FOLLOWING,
        /** Reached the end of the path. */
        ARRIVED,
        /** No path found or the way got blocked. */
        FAILED
    }

    /** Current action, or null right after spawning / finishing an action. */
    public ActionType action;
    /** Utility score the current action was chosen with (for display). */
    public float score;
    /** Ticks since the current action started. */
    public int actionTicks;
    /** Target entity of the current action (food / water node), -1 if none. */
    public int targetEntity = -1;
    /** Target point of the current action (path goal). */
    public float targetX;
    public float targetZ;
    /** Ticks to wait (e.g. pause after wandering). */
    public int waitTicks;

    public PathStatus pathStatus = PathStatus.NONE;
    public Path path;
    /** How often the current path was recomputed because the way got blocked. */
    public int pathRetries;

    /** Per action type: tick until which the action is not considered (after it failed). */
    public final int[] cooldownUntilTick = new int[ActionType.values().length];
}

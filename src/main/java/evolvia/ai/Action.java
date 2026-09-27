package evolvia.ai;

/**
 * One utility-AI action (DESIGN.md §8). Actions are stateless: per-creature state lives in the
 * creature's components ({@code AiState}, {@code Needs}, ...), which the {@link ActionContext}
 * points at while the action runs.
 */
public interface Action {

    enum Status { RUNNING, DONE, FAILED }

    ActionType type();

    /** Utility 0..1 of doing this action now; 0 = not possible or not wanted. */
    float score(ActionContext c);

    /** Called when the action is chosen. */
    void start(ActionContext c);

    /** Called every tick while the action is active. */
    Status update(ActionContext c);

    /** Called when the action ends or is interrupted; must leave the creature standing. */
    default void stop(ActionContext c) {
        c.stopMoving();
    }
}

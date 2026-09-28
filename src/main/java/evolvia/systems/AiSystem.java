package evolvia.systems;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.ai.actions.ConsumeAction;
import evolvia.ai.actions.AttackAction;
import evolvia.ai.actions.FleeAction;
import evolvia.ai.actions.FollowLeaderAction;
import evolvia.ai.actions.SeekMateAction;
import evolvia.ai.actions.SeekResourceAction;
import evolvia.ai.actions.SleepAction;
import evolvia.ai.actions.WanderAction;
import evolvia.components.AiState;
import evolvia.components.Fear;
import evolvia.components.UnderAttack;
import evolvia.components.Needs;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.components.Velocity;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.ResourceKind;

/**
 * Utility AI (DESIGN.md §8). Every creature runs its current action each tick. Every
 * {@code evaluateEverySeconds} (staggered by entity ID so creatures don't all think in the same
 * tick), or when the action ends, it scores all actions and picks the best. A running action is
 * only interrupted by one that scores more than {@code switchMargin} higher.
 */
public final class AiSystem implements GameSystem {

    /** An action that failed (e.g. no path) is not considered again for this long. */
    public static final int FAIL_COOLDOWN_TICKS = 60;

    private final Action[] actions = new Action[ActionType.values().length];
    private final ActionContext context;

    public AiSystem(ActionContext context) {
        this.context = context;
        register(new WanderAction());
        register(new SeekResourceAction(ResourceKind.FOOD));
        register(new ConsumeAction(ResourceKind.FOOD));
        register(new SeekResourceAction(ResourceKind.WATER));
        register(new ConsumeAction(ResourceKind.WATER));
        register(new SleepAction());
        register(new SeekMateAction());
        register(new FleeAction());
        register(new FollowLeaderAction());
        register(new AttackAction());
        for (ActionType type : ActionType.values()) {
            if (actions[type.ordinal()] == null) {
                throw new IllegalStateException("No action registered for " + type);
            }
        }
    }

    private void register(Action action) {
        actions[action.type().ordinal()] = action;
    }

    @Override
    public void update(EcsWorld world, int tick) {
        ComponentStore<AiState> aiStore = world.store(AiState.class);
        ComponentStore<Transform> transforms = world.store(Transform.class);
        ComponentStore<Velocity> velocities = world.store(Velocity.class);
        ComponentStore<Needs> needsStore = world.store(Needs.class);
        ComponentStore<SpeciesRef> speciesStore = world.store(SpeciesRef.class);
        ComponentStore<Fear> fears = world.store(Fear.class);
        ComponentStore<UnderAttack> attacks = world.store(UnderAttack.class);
        context.beginTick(world, tick);

        for (int i = 0; i < aiStore.size(); i++) {
            int entity = aiStore.entityAt(i);
            AiState ai = aiStore.componentAt(i);
            Transform transform = transforms.get(entity);
            Velocity velocity = velocities.get(entity);
            Needs needs = needsStore.get(entity);
            SpeciesRef species = speciesStore.get(entity);
            if (transform == null || velocity == null || needs == null || species == null) {
                continue;
            }
            context.bind(entity, transform, velocity, needs, ai, species);

            int interval = Math.max(1, SpeciesDefinition.secondsToTicks(species.stats().ai().evaluateEverySeconds()));
            boolean evaluate = ai.action == null || (tick + entity) % interval == 0
                    || (ai.action != ActionType.FLEE && fears.has(entity) && fears.get(entity).isActive(tick)) // react at once
                    || (ai.action != ActionType.ATTACK && attacks.has(entity) && attacks.get(entity).isActive(tick));

            if (ai.action != null) {
                Action current = actions[ai.action.ordinal()];
                Action.Status status = current.update(context);
                ai.actionTicks++;
                if (status != Action.Status.RUNNING) {
                    if (status == Action.Status.FAILED) {
                        ai.cooldownUntilTick[ai.action.ordinal()] = tick + FAIL_COOLDOWN_TICKS;
                    }
                    current.stop(context);
                    clearAction(ai);
                    context.bind(entity, transform, velocity, needs, ai, species); // fresh query caches
                    evaluate = true;
                }
            }
            if (evaluate) {
                choose(ai, tick, species.stats().ai().switchMargin());
            }
        }
    }

    private void choose(AiState ai, int tick, float switchMargin) {
        Action best = null;
        float bestScore = 0f;
        for (Action action : actions) {
            if (ai.cooldownUntilTick[action.type().ordinal()] > tick) {
                continue;
            }
            float score = action.score(context);
            if (score > bestScore) {
                bestScore = score;
                best = action;
            }
        }
        if (best == null) {
            return; // nothing possible right now; try again next tick
        }
        if (ai.action == null) {
            switchTo(ai, best, bestScore);
            return;
        }
        Action current = actions[ai.action.ordinal()];
        if (best == current) {
            ai.score = bestScore;
            return;
        }
        float currentScore = current.score(context);
        // An action that no longer makes sense (score 0) is replaced at once; otherwise only by a clearly better one.
        if (currentScore <= 0f || bestScore > currentScore + switchMargin) {
            current.stop(context);
            switchTo(ai, best, bestScore);
        } else {
            ai.score = currentScore;
        }
    }

    private void switchTo(AiState ai, Action action, float score) {
        clearAction(ai);
        ai.action = action.type();
        ai.score = score;
        action.start(context);
    }

    private static void clearAction(AiState ai) {
        ai.action = null;
        ai.actionTicks = 0;
        ai.targetEntity = -1;
        ai.waitTicks = 0;
        ai.pathRetries = 0;
    }
}

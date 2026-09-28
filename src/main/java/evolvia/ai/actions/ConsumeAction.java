package evolvia.ai.actions;

import evolvia.ai.Action;
import evolvia.ai.ActionContext;
import evolvia.ai.ActionType;
import evolvia.components.Memory;
import evolvia.components.ResourceNode;
import evolvia.components.Sick;
import evolvia.components.Transform;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.Groups;
import evolvia.world.Nature;
import evolvia.world.ResourceKind;

/**
 * Eat / Drink from a node within reach until satisfied. Eating takes whole food units from the
 * node (one per {@code secondsPerUnit}); drinking is continuous and water never runs out.
 */
public final class ConsumeAction implements Action {

    /** Stop eating / drinking once the need is this low. */
    public static final float SATISFIED = 0.02f;

    private final ResourceKind kind;

    public ConsumeAction(ResourceKind kind) {
        this.kind = kind;
    }

    @Override
    public ActionType type() {
        return kind == ResourceKind.FOOD ? ActionType.EAT : ActionType.DRINK;
    }

    @Override
    public float score(ActionContext c) {
        float need = need(c);
        if (need <= SATISFIED * 2 || c.inReach(kind) < 0) {
            return 0f;
        }
        return 0.3f + 0.7f * need; // being at the food makes eating attractive even when only a bit hungry
    }

    @Override
    public void start(ActionContext c) {
        c.stopMoving();
        c.ai.targetEntity = c.inReach(kind);
    }

    /** Spoiled food (an old carcass) may make the eater ill (phase 9e). */
    private static void spoiled(ActionContext c, ResourceNode food) {
        if (c.nature == null || !food.type.decays()) {
            return;
        }
        Nature.Disease disease = c.nature.config().disease();
        if (food.ageTicks > SpeciesDefinition.secondsToTicks(disease.spoilSeconds())
                && c.random.nextFloat() < disease.infectChance()) {
            Sick.infect(c.ecs, c.entity, c.tick, SpeciesDefinition.secondsToTicks(disease.durationSeconds()),
                    SpeciesDefinition.secondsToTicks(disease.immuneSeconds()));
        }
    }

    @Override
    public Status update(ActionContext c) {
        int node = c.ai.targetEntity;
        if (node < 0 || !c.isUsable(node)) {
            return Status.DONE; // empty now: re-evaluate
        }
        Transform target = c.transforms.get(node);
        c.face(target.position.x, target.position.z);

        SpeciesDefinition.Eating eating = c.species.eating();
        if (kind == ResourceKind.FOOD) {
            int ticksPerUnit = Math.max(1, SpeciesDefinition.secondsToTicks(eating.secondsPerUnit()));
            if (c.ai.actionTicks > 0 && c.ai.actionTicks % ticksPerUnit == 0) {
                ResourceNode food = c.resources.get(node);
                food.amount -= 1f;
                c.needs.hunger = Math.max(0f, c.needs.hunger - eating.hungerPerUnit() * c.nutrition(food));
                spoiled(c, food);
                if (food.divine) {
                    c.makeBeliever(); // ate what the god gave
                    if (food.amount < 1f) {
                        food.divine = false; // eaten to the bottom: whatever grows back is ordinary
                    }
                }
            }
        } else {
            c.needs.thirst = Math.max(0f, c.needs.thirst - SpeciesDefinition.perTick(eating.thirstReliefPerSecond()));
        }
        remember(c, target);
        return need(c) <= SATISFIED ? Status.DONE : Status.RUNNING;
    }

    /** Species with memory remember where they ate / drank; a herd remembers it too (shared memory). */
    private void remember(ActionContext c, Transform source) {
        Groups.Group group = c.ref.hasAbility(Groups.ABILITY) ? c.group() : null; // shared memory is a herd bonus
        if (group != null) {
            if (kind == ResourceKind.FOOD) {
                group.knowsFood = true;
                group.foodX = source.position.x;
                group.foodZ = source.position.z;
            } else {
                group.knowsWater = true;
                group.waterX = source.position.x;
                group.waterZ = source.position.z;
            }
        }
        Memory memory = c.memory();
        if (memory == null) {
            return;
        }
        if (kind == ResourceKind.FOOD) {
            memory.knowsFood = true;
            memory.foodX = source.position.x;
            memory.foodZ = source.position.z;
        } else {
            memory.knowsWater = true;
            memory.waterX = source.position.x;
            memory.waterZ = source.position.z;
        }
    }

    private float need(ActionContext c) {
        return kind == ResourceKind.FOOD ? c.needs.hunger : c.needs.thirst;
    }
}

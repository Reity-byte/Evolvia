package evolvia.systems;

import evolvia.core.Time;
import evolvia.ecs.EcsWorld;
import evolvia.ecs.GameSystem;
import evolvia.world.Groups;
import evolvia.world.Milestones;
import evolvia.world.World;

/** Checks the early game goals once per game second and pays their rewards (EP and faith). */
public final class MilestoneSystem implements GameSystem {

    private final World world;

    public MilestoneSystem(World world) {
        this.world = world;
    }

    @Override
    public void update(EcsWorld ecs, int tick) {
        if (tick % Time.TICKS_PER_SECOND != 0) {
            return;
        }
        Milestones milestones = world.milestones();
        for (Milestones.Milestone milestone : milestones.all()) {
            if (milestones.isCompleted(milestone.id()) || progress(milestone.type()) < milestone.value()) {
                continue;
            }
            milestones.complete(milestone);
            world.species().addPoints(milestone.rewardEp());
            world.godPowers().faith().add(milestone.rewardFaith());
        }
    }

    /** Current value of what a milestone counts. */
    public static int progress(World world, Milestones.Type type) {
        return switch (type) {
            case PEOPLE -> world.believers();
            case NODES -> world.species().unlockedNodes().size();
            case VICTORIES -> world.groups().playerVictories();
            case HERDS -> world.groups().playerCount();
            case SHELTERED -> world.shelteredSleepers(false);
            case DAYS -> world.clock().day(world.tick()) - 1;
            case SACRED -> world.refuges().sacredCount();
            case TRIBE -> world.tribeGroup() != null ? 1 : 0;
            case BUILDINGS -> world.settlement().doneTypes().size();
            case DISCOVERIES -> world.science().discovered().size();
            case RIVAL -> world.raidSystem().defeated() ? 1 : 0;
            case STOCK -> (int) world.groups().all().stream().filter(g -> g.player)
                    .mapToDouble(Groups.Group::stockTotal).max().orElse(0);
        };
    }

    private int progress(Milestones.Type type) {
        return progress(world, type);
    }
}

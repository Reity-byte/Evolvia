package evolvia.ui;

import evolvia.systems.MilestoneSystem;
import evolvia.world.Milestones;
import evolvia.world.World;

import java.util.Locale;

/** Early game goals ("Cíle", DESIGN.md §11, 9c): left below the top bar, can be folded. */
public final class MilestonePanel {

    private static final float WIDTH = 270f;
    private boolean folded;

    public void build(Ui ui, World world, float top) {
        Milestones milestones = world.milestones();
        int done = milestones.completed().size();
        float padding = 10f;
        float line = ui.small.lineHeight() + 4f;
        float x = 10f;
        float y = top + 10f;
        String title = String.format(Locale.ROOT, "Cíle %d / %d", done, milestones.all().size());
        float headerHeight = ui.bold.lineHeight() + 2 * padding - 4f;
        float h = folded ? headerHeight : headerHeight + milestones.all().size() * line + padding - 4f;
        ui.panel(x, y, WIDTH, h);
        ui.text(ui.bold, title, x + padding, y + padding - 2f, Ui.TEXT);
        if (ui.button(folded ? "+" : "–", x + WIDTH - 30f, y + 5f, 22f, 22f, false)) {
            folded = !folded;
        }
        if (folded) {
            return;
        }
        float ty = y + headerHeight;
        for (Milestones.Milestone m : milestones.all()) {
            boolean complete = milestones.isCompleted(m.id());
            ui.draw().rect(x + padding, ty + 3f, 10f, 10f, complete ? 0xFF5FAF6A : 0xFF30343C);
            ui.draw().outline(x + padding, ty + 3f, 10f, 10f, 1f, complete ? 0xFF7FD68A : 0xFF60656F);
            String text = complete ? m.name() : String.format(Locale.ROOT, "%s (%d / %d)", m.name(),
                    Math.min(MilestoneSystem.progress(world, m.type()), m.value()), m.value());
            ui.text(ui.small, text, x + padding + 18f, ty, complete ? Ui.TEXT_DIM : Ui.TEXT);
            if (ui.hovered(x, ty, WIDTH, line)) {
                String reward = String.format(Locale.ROOT, "%s  Odměna: %.0f EP, %.0f Víry", m.description(), m.rewardEp(), m.rewardFaith());
                float w = ui.small.width(reward) + 16f;
                ui.draw().rect(x + WIDTH + 6f, ty - 2f, w, line + 4f, 0xF5181B20);
                ui.text(ui.small, reward, x + WIDTH + 14f, ty, Ui.TEXT);
            }
            ty += line;
        }
    }
}

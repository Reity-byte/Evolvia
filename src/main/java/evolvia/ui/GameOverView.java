package evolvia.ui;

import evolvia.world.DeathStats;
import evolvia.world.World;

import java.util.Locale;

/** "Tvůj lid zanikl" (DESIGN.md §11, 9c): shown when the player's people died out; load a save or start anew. */
public final class GameOverView {

    /** What the player chose. */
    public enum Choice { LOAD, NEW_WORLD }

    private static final float WIDTH = 420f;

    /** Lays out and draws the screen; returns the choice made this frame, or null. */
    public Choice build(Ui ui, World world, float top) {
        ui.draw().rect(0, top, ui.width(), ui.height() - top, 0xB0000000);
        ui.block(0, top, ui.width(), ui.height() - top);
        float h = 190f;
        float x = (ui.width() - WIDTH) / 2f;
        float y = (ui.height() - h) / 2f;
        ui.panel(x, y, WIDTH, h);
        float ty = y + 18f;
        String title = "Tvůj lid zanikl";
        ui.text(ui.title, title, x + (WIDTH - ui.title.width(title)) / 2f, ty, 0xFFE08A7A);
        ty += ui.title.lineHeight() + 10f;
        DeathStats d = world.deaths();
        String[] lines = {
                "Žádná bytost už v tebe nevěří. Divoká stáda žijí dál.",
                String.format(Locale.ROOT, "Úmrtí: v boji %d, hlad %d, žízeň %d, stáří %d, blesk %d",
                        d.count(DeathStats.Cause.FIGHT), d.count(DeathStats.Cause.STARVATION), d.count(DeathStats.Cause.THIRST),
                        d.count(DeathStats.Cause.OLD_AGE), d.count(DeathStats.Cause.LIGHTNING)),
        };
        for (String line : lines) {
            ui.text(ui.small, line, x + (WIDTH - ui.small.width(line)) / 2f, ty, Ui.TEXT_DIM);
            ty += ui.small.lineHeight() + 4f;
        }
        ty += 16f;
        String load = "Načíst hru";
        String fresh = "Nový svět";
        float lw = ui.buttonWidth(load) + 20f;
        float nw = ui.buttonWidth(fresh) + 20f;
        float bx = x + (WIDTH - lw - nw - 12f) / 2f;
        Choice choice = null;
        if (ui.button(load, bx, ty, lw, 32f, false)) {
            choice = Choice.LOAD;
        }
        if (ui.button(fresh, bx + lw + 12f, ty, nw, 32f, false)) {
            choice = Choice.NEW_WORLD;
        }
        ui.clickedAnywhere();
        return choice;
    }
}

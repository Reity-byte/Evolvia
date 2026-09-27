package evolvia.ui;

import evolvia.save.SaveManager;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Game menu ("Hra" in the top bar): save, quick save, the list of saves to load or delete (deleting asks
 * for a second click), new world and quit. Returns what the player chose; the game carries it out.
 */
public final class GameMenu {

    /** What the player picked in the menu this frame. */
    public sealed interface Choice {
        record SaveNew() implements Choice {
        }

        record QuickSave() implements Choice {
        }

        record Load(String name) implements Choice {
        }

        record Delete(String name) implements Choice {
        }

        record NewWorld() implements Choice {
        }

        record Quit() implements Choice {
        }
    }

    private static final float WIDTH = 520f;
    private static final float ROW = 30f;
    private static final int VISIBLE_ROWS = 9;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d. M. yyyy HH:mm");

    private boolean visible;
    private List<SaveManager.SaveInfo> saves = List.of();
    private String confirmDelete;
    private int scroll;

    public boolean isVisible() {
        return visible;
    }

    /** Opens the menu with a fresh list of saves. */
    public void open(List<SaveManager.SaveInfo> saves) {
        this.saves = saves;
        visible = true;
        confirmDelete = null;
        scroll = 0;
    }

    public void close() {
        visible = false;
    }

    /** Refreshes the list while the menu is open (after saving or deleting). */
    public void setSaves(List<SaveManager.SaveInfo> saves) {
        this.saves = saves;
        scroll = Math.clamp(scroll, 0, Math.max(0, saves.size() - VISIBLE_ROWS));
    }

    /**
     * Lays out and draws the menu, handles clicks and the mouse wheel.
     *
     * @return the player's choice this frame, or null
     */
    public Choice build(Ui ui, float top, double scrollY) {
        if (!visible) {
            return null;
        }
        float padding = 16f;
        float listHeight = VISIBLE_ROWS * ROW;
        float h = padding + ui.title.lineHeight() + 12f + 34f + 16f + ui.bold.lineHeight() + 6f + listHeight + 14f + 34f + padding;
        float x = (ui.width() - WIDTH) / 2f;
        float y = Math.max(top + 10f, (ui.height() - h) / 2f);
        ui.draw().rect(0, top, ui.width(), ui.height() - top, 0x80000000);
        ui.block(0, top, ui.width(), ui.height() - top);
        ui.panel(x, y, WIDTH, h);

        Choice choice = null;
        float ty = y + padding;
        ui.text(ui.title, "Hra", x + padding, ty, Ui.TEXT);
        if (ui.button("×", x + WIDTH - 36f, y + 10f, 26f, 26f, false)) {
            visible = false;
            return null;
        }
        ty += ui.title.lineHeight() + 12f;

        float bx = x + padding;
        String[] labels = {"Uložit jako novou pozici", "Rychlé uložení (F5)"};
        for (int i = 0; i < labels.length; i++) {
            float bw = ui.buttonWidth(labels[i]);
            if (ui.button(labels[i], bx, ty, bw, 30f, false)) {
                choice = i == 0 ? new Choice.SaveNew() : new Choice.QuickSave();
            }
            bx += bw + 8f;
        }
        ty += 34f + 16f;

        ui.text(ui.bold, "Uložené hry", x + padding, ty, Ui.TEXT);
        ui.text(ui.small, "F9 načte rychlé uložení", x + WIDTH - padding - ui.small.width("F9 načte rychlé uložení"), ty + 2f, Ui.TEXT_DIM);
        ty += ui.bold.lineHeight() + 6f;

        float listX = x + padding;
        float listW = WIDTH - 2 * padding;
        ui.draw().rect(listX, ty, listW, listHeight, 0xFF15181D);
        if (ui.hovered(listX, ty, listW, listHeight) && scrollY != 0) {
            scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, saves.size() - VISIBLE_ROWS));
        }
        if (saves.isEmpty()) {
            ui.text(ui.regular, "Zatím žádné uložené hry.", listX + 10f, ty + 8f, Ui.TEXT_DIM);
        }
        for (int i = scroll; i < Math.min(saves.size(), scroll + VISIBLE_ROWS); i++) {
            SaveManager.SaveInfo save = saves.get(i);
            float ry = ty + (i - scroll) * ROW;
            if (ui.hovered(listX, ry, listW, ROW)) {
                ui.draw().rect(listX, ry, listW, ROW, 0xFF222730);
            }
            String title = EvolutionTreeView.fit(ui.bold, save.name(), 170f);
            ui.text(ui.bold, title, listX + 8f, ry + 6f, Ui.TEXT);
            String info = String.format("%s · %d bytostí", date(save.meta().savedAt()), save.meta().population());
            ui.text(ui.small, info, listX + 186f, ry + 8f, Ui.TEXT_DIM);

            boolean confirming = save.name().equals(confirmDelete);
            String deleteLabel = confirming ? "Opravdu?" : "Smazat";
            float dw = ui.buttonWidth(deleteLabel);
            float lw = ui.buttonWidth("Načíst");
            float by = ry + 3f;
            if (ui.button("Načíst", listX + listW - dw - lw - 12f, by, lw, ROW - 6f, false)) {
                choice = new Choice.Load(save.name());
            }
            if (ui.button(deleteLabel, listX + listW - dw - 4f, by, dw, ROW - 6f, confirming)) {
                if (confirming) {
                    choice = new Choice.Delete(save.name());
                    confirmDelete = null;
                } else {
                    confirmDelete = save.name();
                }
            }
        }
        if (saves.size() > VISIBLE_ROWS) {
            float barH = listHeight * VISIBLE_ROWS / saves.size();
            float barY = ty + (listHeight - barH) * scroll / (saves.size() - VISIBLE_ROWS);
            ui.draw().rect(listX + listW - 3f, barY, 3f, barH, 0xFF60656F);
        }
        ty += listHeight + 14f;

        String newWorld = "Nový svět";
        float nw = ui.buttonWidth(newWorld);
        if (ui.button(newWorld, x + padding, ty, nw, 30f, false)) {
            choice = new Choice.NewWorld();
        }
        String quit = "Uložit a ukončit";
        float qw = ui.buttonWidth(quit);
        if (ui.button(quit, x + WIDTH - padding - qw, ty, qw, 30f, false)) {
            choice = new Choice.Quit();
        }
        ui.clickedAnywhere(); // clicks inside the menu do not reach the world
        return choice;
    }

    private static String date(String iso) {
        try {
            return OffsetDateTime.parse(iso).format(DATE);
        } catch (DateTimeParseException | NullPointerException e) {
            return "?";
        }
    }
}

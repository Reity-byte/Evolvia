package evolvia.ui;

import evolvia.core.Time;
import evolvia.core.Time.Speed;
import evolvia.evolution.EvolutionNode;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.god.Faith;
import evolvia.world.DeathStats;
import evolvia.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Always visible top bar (species, population, EP, buttons for the species panel and the evolution
 * tree, game speed) and the species panel (DESIGN.md §9) with the current stats and what changed them.
 */
public final class Hud {

    public static final float BAR_HEIGHT = 36f;
    private static final float PANEL_WIDTH = 330f;

    private boolean speciesPanel;

    public boolean isSpeciesPanelVisible() {
        return speciesPanel;
    }

    public void toggleSpeciesPanel() {
        speciesPanel = !speciesPanel;
    }

    /** Lays out, draws and handles clicks of the top bar (and the species panel when open). */
    public void build(Ui ui, World world, Time time, EvolutionTreeView tree) {
        Species species = world.species();
        float w = ui.width();
        ui.draw().rect(0, 0, w, BAR_HEIGHT, 0xF0161920);
        ui.draw().rect(0, BAR_HEIGHT - 1f, w, 1f, 0xFF3A3F4A);
        ui.block(0, 0, w, BAR_HEIGHT);

        float y = (BAR_HEIGHT - ui.regular.lineHeight()) / 2f;
        float x = 12f;
        x += ui.text(ui.bold, species.stats().name(), x, y, Ui.TEXT) + 18f;
        x += stat(ui, "Populace", Integer.toString(world.population()), x, y);
        x += stat(ui, "Generace", Integer.toString(world.maxGeneration()), x, y);
        x += stat(ui, "EP", String.format(Locale.ROOT, "%.0f", species.points()), x, y);
        x += ui.text(ui.small, String.format(Locale.ROOT, "+%.1f/min", world.evolutionSystem().pointsPerMinute()),
                x - 12f, y + 2f, Ui.TEXT_DIM) + 8f;

        float buttonY = 5f;
        float buttonH = BAR_HEIGHT - 10f;
        String speciesLabel = "Druh";
        float bw = ui.buttonWidth(speciesLabel);
        if (ui.button(speciesLabel, x, buttonY, bw, buttonH, speciesPanel && !tree.isVisible())) {
            speciesPanel = !speciesPanel;
            tree.close();
        }
        x += bw + 6f;
        int available = availableNodes(world);
        String treeLabel = available > 0 ? "Evoluce (" + available + ")" : "Evoluce";
        bw = ui.buttonWidth(treeLabel);
        if (ui.button(treeLabel, x, buttonY, bw, buttonH, tree.isVisible())) {
            tree.toggle();
        }
        if (available > 0 && !tree.isVisible()) {
            ui.draw().outline(x, buttonY, bw, buttonH, 2f, 0xFFE0B040);
        }
        x += bw + 22f;
        faith(ui, world, x, y);

        // Game speed, right-aligned
        Speed[] speeds = {Speed.PAUSED, Speed.NORMAL, Speed.FAST, Speed.FASTEST};
        String[] labels = {"Pauza", "1×", "3×", "10×"};
        float sx = w - 12f;
        for (int i = speeds.length - 1; i >= 0; i--) {
            float sw = Math.max(40f, ui.buttonWidth(labels[i]));
            sx -= sw;
            if (ui.button(labels[i], sx, buttonY, sw, buttonH, time.speed() == speeds[i])) {
                if (speeds[i] == Speed.PAUSED) {
                    time.togglePause();
                } else {
                    time.setSpeed(speeds[i]);
                }
            }
            sx -= 4f;
        }
        ui.text(ui.small, "Rychlost", sx - ui.small.width("Rychlost") - 6f, y + 2f, Ui.TEXT_DIM);

        if (speciesPanel && !tree.isVisible()) {
            speciesPanel(ui, world);
        }
    }

    /** Faith, believers and the god's alignment (good / evil). */
    private static void faith(Ui ui, World world, float x, float y) {
        Faith faith = world.godPowers().faith();
        x += stat(ui, "Víra", String.format(Locale.ROOT, "%.0f", faith.points()), x, y);
        x += ui.text(ui.small, String.format(Locale.ROOT, "+%.1f/min", faith.perMinute()), x - 12f, y + 2f, Ui.TEXT_DIM) + 8f;
        x += stat(ui, "Věřící", Integer.toString(faith.believers()), x, y);

        float barX = x;
        float barY = y + 6f;
        float barW = 64f;
        float centre = barX + barW / 2f;
        ui.draw().rect(barX, barY, barW, 6f, 0xFF30343C);
        float value = faith.alignment();
        if (value > 0) {
            ui.draw().rect(centre, barY, barW / 2f * value, 6f, 0xFFE8D27A);
        } else if (value < 0) {
            ui.draw().rect(centre + barW / 2f * value, barY, -barW / 2f * value, 6f, 0xFFC0473A);
        }
        ui.draw().rect(centre - 0.5f, barY - 2f, 1f, 10f, 0xFF9AA0A8);
        String label = value > 0.05f ? "dobrý bůh" : value < -0.05f ? "zlý bůh" : "neutrální";
        float labelWidth = ui.text(ui.small, label, barX + barW + 6f, y + 2f, value > 0.05f ? 0xFFE8D27A : value < -0.05f ? 0xFFE08A7A : Ui.TEXT_DIM);
        if (ui.hovered(barX - 4f, 0f, barW + labelWidth + 12f, BAR_HEIGHT)) {
            String text = String.format(Locale.ROOT, "Morálka %+.2f · laskavé činy %d, kruté %d", value, faith.kindActs(), faith.cruelActs());
            float w = ui.small.width(text) + 16f;
            float tx = Math.min(barX, ui.width() - w - 4f);
            ui.draw().rect(tx, BAR_HEIGHT + 4f, w, ui.small.lineHeight() + 10f, 0xF5181B20);
            ui.text(ui.small, text, tx + 8f, BAR_HEIGHT + 9f, Ui.TEXT);
        }
    }

    private static float stat(Ui ui, String label, String value, float x, float y) {
        float lw = ui.text(ui.regular, label, x, y, Ui.TEXT_DIM);
        float vw = ui.text(ui.bold, value, x + lw + 6f, y, Ui.TEXT);
        return lw + 6f + vw + 18f;
    }

    private static int availableNodes(World world) {
        int count = 0;
        for (EvolutionNode node : world.species().tree().nodes()) {
            if (world.species().availability(node, world).status() == Species.NodeStatus.AVAILABLE) {
                count++;
            }
        }
        return count;
    }

    private void speciesPanel(Ui ui, World world) {
        Species species = world.species();
        SpeciesDefinition s = species.stats();
        SpeciesDefinition base = species.base();
        List<String[]> rows = new ArrayList<>();
        rows.add(row("Rychlost", String.format(Locale.ROOT, "%.2f pole/s", s.speed()), s.speed() / base.speed()));
        rows.add(row("Dohled", String.format(Locale.ROOT, "%.0f polí", s.senseRadius()), s.senseRadius() / base.senseRadius()));
        rows.add(row("Velikost", String.format(Locale.ROOT, "%.2f", s.bodySize()), s.bodySize() / base.bodySize()));
        rows.add(row("Zdraví", String.format(Locale.ROOT, "%.2f", s.maxHealth()), s.maxHealth() / base.maxHealth()));
        rows.add(row("Hladovění", String.format(Locale.ROOT, "%.4f /s", s.needs().hungerPerSecond()),
                s.needs().hungerPerSecond() / base.needs().hungerPerSecond()));
        rows.add(row("Žíznivost", String.format(Locale.ROOT, "%.4f /s", s.needs().thirstPerSecond()),
                s.needs().thirstPerSecond() / base.needs().thirstPerSecond()));
        float lifespan = (s.lifespanMinSeconds() + s.lifespanMaxSeconds()) / 2f;
        float baseLifespan = (base.lifespanMinSeconds() + base.lifespanMaxSeconds()) / 2f;
        rows.add(row("Délka života", String.format(Locale.ROOT, "%.0f min", lifespan / 60f), lifespan / baseLifespan));
        rows.add(row("Strava", diet(s), 1f));
        rows.add(row("Výživa", String.format(Locale.ROOT, "rostliny %.2f, maso %.2f",
                s.diet().plantNutrition(), s.diet().meatNutrition()), 1f));
        rows.add(row("Teplota", String.format(Locale.ROOT, "%.2f – %.2f", s.climate().comfortMin(), s.climate().comfortMax()), 1f));
        List<String> abilities = new ArrayList<>();
        for (String ability : species.abilities()) {
            abilities.add(Texts.ability(ability));
        }
        rows.add(row("Schopnosti", abilities.isEmpty() ? "–" : String.join(", ", abilities), 1f));

        DeathStats deaths = world.deaths();
        String deathText = String.format(Locale.ROOT, "hlad %d, žízeň %d, klima %d, stáří %d, blesk %d",
                deaths.count(DeathStats.Cause.STARVATION), deaths.count(DeathStats.Cause.THIRST),
                deaths.count(DeathStats.Cause.EXPOSURE), deaths.count(DeathStats.Cause.OLD_AGE),
                deaths.count(DeathStats.Cause.LIGHTNING));
        String evolution = String.format(Locale.ROOT, "%d / %d uzlů odemčeno, celkem získáno %.0f EP",
                species.unlockedNodes().size(), species.tree().size(), species.pointsEarned());

        float padding = 12f;
        float line = ui.regular.lineHeight() + 1f;
        float h = padding + ui.title.lineHeight() + 6f + rows.size() * line + 10f
                + ui.small.lineHeight() * 4 + padding;
        float x = 10f;
        float y = BAR_HEIGHT + 10f;
        ui.panel(x, y, PANEL_WIDTH, h);
        float ty = y + padding;
        ui.text(ui.title, species.stats().name(), x + padding, ty, Ui.TEXT);
        ty += ui.title.lineHeight() + 6f;
        float valueX = x + padding + 110f;
        for (String[] r : rows) {
            ui.text(ui.regular, r[0], x + padding, ty, Ui.TEXT_DIM);
            float vw = ui.text(ui.regular, r[1], valueX, ty, Ui.TEXT);
            if (r[2] != null) {
                ui.text(ui.small, r[2], valueX + vw + 8f, ty + 2f, Ui.TEXT_ACCENT);
            }
            ty += line;
        }
        ty += 10f;
        ui.text(ui.small, "Úmrtí: " + deathText, x + padding, ty, Ui.TEXT_DIM);
        ty += ui.small.lineHeight();
        ui.text(ui.small, "Evoluce: " + evolution, x + padding, ty, Ui.TEXT_DIM);
        ty += ui.small.lineHeight() * 1.4f;
        ui.text(ui.small, "Vlastnosti měníš v evolučním stromu (Evoluce / F4).", x + padding, ty, Ui.TEXT_DIM);
    }

    /** Label, value and the change against the starting species (null if unchanged). */
    private static String[] row(String label, String value, float ratio) {
        String change = Math.abs(ratio - 1f) > 0.005f ? Texts.percent(ratio - 1f) : null;
        return new String[]{label, value, change};
    }

    private static String diet(SpeciesDefinition s) {
        boolean plants = s.diet().plantNutrition() > 0f;
        boolean meat = s.diet().meatNutrition() > 0f;
        if (plants && meat) {
            return "všežravec";
        }
        return meat ? "masožravec" : plants ? "býložravec" : "nic";
    }
}

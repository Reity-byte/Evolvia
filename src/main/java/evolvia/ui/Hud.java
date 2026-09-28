package evolvia.ui;

import evolvia.core.Time;
import evolvia.core.Time.Speed;
import evolvia.evolution.EvolutionNode;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.DeathStats;
import evolvia.world.Nature;
import evolvia.world.Science;
import evolvia.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Always visible top bar (species, generation, EP, buttons for the species panel, the evolution tree and the
 * herds, the time and weather, game speed) and the species panel (DESIGN.md §9) with the current stats and what
 * changed them. The people, the stock and faith are in the {@link BottomBar} (phase 9i).
 */
public final class Hud {

    public static final float BAR_HEIGHT = 36f;
    private static final float PANEL_WIDTH = 330f;

    private boolean speciesPanel;
    private boolean showGroups = true; // herds are central from the start (phase 9c)

    public boolean isSpeciesPanelVisible() {
        return speciesPanel;
    }

    /** Whether the herd view is on (only while the species lives in herds). */
    public boolean showGroups(World world) {
        return showGroups;
    }

    public void toggleGroups() {
        showGroups = !showGroups;
    }

    public void toggleSpeciesPanel() {
        speciesPanel = !speciesPanel;
    }

    /**
     * Lays out, draws and handles clicks of the top bar (and the species panel when open).
     *
     * @param menuOpen whether the game menu is open (its button is highlighted)
     * @return true if the game menu button was clicked
     */
    public boolean build(Ui ui, World world, Time time, EvolutionTreeView tree, boolean menuOpen) {
        Species species = world.species();
        float w = ui.width();
        ui.draw().rect(0, 0, w, BAR_HEIGHT, 0xF0161920);
        ui.draw().rect(0, BAR_HEIGHT - 1f, w, 1f, 0xFF3A3F4A);
        ui.block(0, 0, w, BAR_HEIGHT);

        float y = (BAR_HEIGHT - ui.regular.lineHeight()) / 2f;
        float x = 6f;
        boolean menuClicked = ui.button("Hra", x, 5f, ui.buttonWidth("Hra"), BAR_HEIGHT - 10f, menuOpen);
        x += ui.buttonWidth("Hra") + 14f;
        x += ui.text(ui.bold, species.stats().name(), x, y, Ui.TEXT) + 18f;
        x += stat(ui, "Gen.", Integer.toString(world.maxGeneration()), x, y);
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
        boolean evolutionOpen = tree.isVisible() && tree.mode() == EvolutionTreeView.Mode.EVOLUTION;
        if (ui.button(treeLabel, x, buttonY, bw, buttonH, evolutionOpen)) {
            tree.toggle(EvolutionTreeView.Mode.EVOLUTION);
        }
        if (available > 0 && !tree.isVisible()) {
            ui.draw().outline(x, buttonY, bw, buttonH, 2f, 0xFFE0B040);
        }
        x += bw + 6f;
        x += scienceButton(ui, world, tree, x, buttonY, buttonH) + 6f;
        {
            String herdLabel = "Stáda";
            float hw = ui.buttonWidth(herdLabel);
            if (ui.button(herdLabel, x, buttonY, hw, buttonH, showGroups)) {
                showGroups = !showGroups;
            }
            x += hw + 6f;
        }

        // Game speed, right-aligned; the time of day and the weather left of it
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
        clock(ui, world, (int) time.tickCount(), x + 12f, sx - 14f, y);

        if (speciesPanel && !tree.isVisible() && !menuOpen) {
            speciesPanel(ui, world);
        }
        return menuClicked;
    }

    /**
     * Day, part of the day, season and weather in the top bar, right-aligned to {@code right} (phase 9i: the
     * clock panel covered the creature panel). Parts that do not fit after {@code left} are left out.
     */
    private static void clock(Ui ui, World world, int tick, float left, float right, float y) {
        Nature nature = world.nature();
        String day = "Den " + world.clock().day(tick) + " · " + dayPart(world.clock().timeOfDay(tick));
        String season = nature.season(tick).name() + ", rok " + nature.year(tick);
        String weather = weather(world, tick);
        boolean danger = nature.blizzard(tick) || !nature.burningTiles().isEmpty() || nature.floodLevel(tick) > 0f;
        float gap = 14f;
        float full = ui.bold.width(day) + gap + ui.regular.width(season) + gap + ui.regular.width(weather);
        boolean withSeason = right - left >= full;
        float width = withSeason ? full : ui.bold.width(day) + gap + ui.regular.width(weather);
        if (right - left < width) {
            return;
        }
        float x = right - width;
        x += ui.text(ui.bold, day, x, y, Ui.TEXT) + gap;
        if (withSeason) {
            x += ui.text(ui.regular, season, x, y, Ui.TEXT_ACCENT) + gap;
        }
        ui.text(ui.regular, weather, x, y, danger ? 0xFFE08A7A : 0xFFB7C7DA);
    }

    private static String weather(World world, int tick) {
        Nature nature = world.nature();
        String text = nature.blizzard(tick) ? "Vánice" : capitalize(nature.weather().label);
        if (!nature.burningTiles().isEmpty()) {
            text += ", požár";
        }
        if (nature.floodLevel(tick) > 0f) {
            text += ", záplava";
        }
        return text;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String dayPart(float timeOfDay) {
        if (timeOfDay < 0.22f || timeOfDay >= 0.78f) {
            return "noc";
        }
        if (timeOfDay < 0.35f) {
            return "ráno";
        }
        if (timeOfDay < 0.62f) {
            return "den";
        }
        return "večer";
    }

    /**
     * The science button (phase 10b): what is being researched and how far, or a call to choose; dim before speech.
     *
     * @return its width
     */
    private static float scienceButton(Ui ui, World world, EvolutionTreeView tree, float x, float y, float h) {
        Science science = world.science();
        String label = "Věda";
        boolean choose = false;
        if (science.isActive()) {
            EvolutionNode target = science.target() != null ? science.tree().node(science.target()) : null;
            if (target != null) {
                label = String.format(Locale.ROOT, "Věda: %s %.0f %%", target.name(), science.share(target) * 100f);
            } else {
                choose = science.tree().nodes().stream()
                        .anyMatch(n -> science.availability(n, world).status() == Species.NodeStatus.AVAILABLE);
                label = choose ? "Věda (vyber)" : "Věda";
            }
        }
        float bw = ui.buttonWidth(label);
        boolean open = tree.isVisible() && tree.mode() == EvolutionTreeView.Mode.SCIENCE;
        if (ui.button(label, x, y, bw, h, open)) {
            tree.toggle(EvolutionTreeView.Mode.SCIENCE);
        }
        if (!science.isActive()) {
            ui.draw().rect(x, y, bw, h, 0x70101216);
        } else if (choose && !tree.isVisible()) {
            ui.draw().outline(x, y, bw, h, 2f, 0xFF8FB8F0);
        }
        return bw;
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
        int[] stages = world.stageCounts();
        if (stages.length > 1) {
            int newest = stages[stages.length - 1];
            rows.add(row("Nejnovější znaky", String.format(Locale.ROOT, "%.0f %% populace",
                    100f * newest / Math.max(1, world.creatureCount())), 1f));
        }
        {
            int herds = world.groups().count();
            int mine = world.groups().playerCount();
            rows.add(row("Stáda", String.format(Locale.ROOT, "tvoje %d, divoká %d, vítězství %d", mine, herds - mine,
                    world.groups().playerVictories()), 1f));
        }

        rows.add(row("Věda", world.science().isActive()
                ? String.format(Locale.ROOT, "%d / %d objevů, +%.1f ZN/min", world.science().discovered().size(),
                world.science().tree().size(), world.science().perMinute())
                : "začne s Řečí", 1f));

        DeathStats deaths = world.deaths();
        String deathText = String.format(Locale.ROOT, "boj %d, hlad %d, žízeň %d, klima %d, stáří %d, blesk %d, nemoc %d, oheň %d, voda %d",
                deaths.count(DeathStats.Cause.FIGHT), deaths.count(DeathStats.Cause.STARVATION), deaths.count(DeathStats.Cause.THIRST),
                deaths.count(DeathStats.Cause.EXPOSURE), deaths.count(DeathStats.Cause.OLD_AGE),
                deaths.count(DeathStats.Cause.LIGHTNING), deaths.count(DeathStats.Cause.DISEASE),
                deaths.count(DeathStats.Cause.FIRE), deaths.count(DeathStats.Cause.DROWNING));
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
        ui.text(ui.small, "Hodnoty nejnovější generace. Změny v Evoluci (F4).", x + padding, ty, Ui.TEXT_DIM);
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

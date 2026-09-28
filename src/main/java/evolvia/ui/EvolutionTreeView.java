package evolvia.ui;

import evolvia.evolution.Effect;
import evolvia.evolution.EvolutionNode;
import evolvia.evolution.Species;
import evolvia.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evolution tree window (DESIGN.md §9): all branches as graphs of nodes with prerequisite lines,
 * node states unlocked / available / locked / excluded, a tooltip with the description, effects and
 * why a node is locked, and unlocking by clicking an available node. Covers the screen below the top bar.
 */
public final class EvolutionTreeView {

    private static final float MARGIN = 24f;
    private static final float HEADER = 52f;
    private static final float TOOLTIP_WIDTH = 310f;
    private static final long MESSAGE_NANOS = 4_000_000_000L;
    private static final float SCROLL_STEP = 60f;

    private static final int LINE_UNLOCKED = 0xFF5FAF6A;
    private static final int LINE_AVAILABLE = 0xFFB08A3A;
    private static final int LINE_LOCKED = 0xFF4A4F58;

    private boolean visible;
    private float scroll;
    /** Nothing of the scrolled tree is drawn above this (it would cover the top bar; the header hides the rest). */
    private static float clipTop;
    private String message = "";
    private boolean messageGood;
    private long messageTime;

    public boolean isVisible() {
        return visible;
    }

    public void toggle() {
        visible = !visible;
    }

    public void close() {
        visible = false;
    }

    /**
     * Lays out, draws and handles clicks (call during input handling). A tree taller than the screen
     * scrolls with the mouse wheel.
     *
     * @param scrollY mouse wheel movement this frame
     */
    public void build(Ui ui, World world, float top, double scrollY) {
        if (!visible) {
            return;
        }
        Species species = world.species();
        float width = ui.width();
        float height = ui.height() - top;
        ui.draw().rect(0, top, width, height, 0xE6101216);
        ui.block(0, top, width, height);

        // Tree (drawn first; the header covers what scrolls under it)
        TreeLayout layout = TreeLayout.of(species.tree(), width - 2 * MARGIN);
        float contentTop = top + HEADER;
        float contentHeight = layout.height() + 26f + 30f; // tree + legend
        float maxScroll = Math.max(0f, contentHeight - (ui.height() - contentTop - 10f));
        scroll = Math.clamp(scroll - (float) scrollY * SCROLL_STEP, 0f, maxScroll);
        float originX = Math.max(MARGIN, (width - layout.width()) / 2f);
        float originY = contentTop + 10f - scroll;
        clipTop = top;
        drawBranches(ui, species, layout, originX, originY);
        drawLines(ui, world, layout, originX, originY);
        EvolutionNode hovered = drawNodes(ui, world, layout, originX, originY, contentTop);
        drawLegend(ui, originX, originY + layout.height() + 26f);
        if (maxScroll > 0f) {
            float trackHeight = ui.height() - contentTop - 20f;
            float barHeight = trackHeight * trackHeight / (trackHeight + maxScroll);
            ui.draw().rect(width - 8f, contentTop + 10f + (trackHeight - barHeight) * scroll / maxScroll, 4f, barHeight, 0xFF60656F);
        }

        // Header
        ui.draw().rect(0, top, width, HEADER, 0xFF101216);
        ui.draw().rect(0, contentTop - 1f, width, 1f, 0xFF2A2E36);
        float x = MARGIN;
        float y = top + 14f;
        ui.text(ui.title, "Evoluční strom", x, y, Ui.TEXT);
        float textX = x + ui.title.width("Evoluční strom") + 24f;
        ui.text(ui.bold, String.format(Locale.ROOT, "%.0f EP", species.points()), textX, y + 3f, Ui.TEXT_ACCENT);
        textX += ui.bold.width(String.format(Locale.ROOT, "%.0f EP", species.points())) + 10f;
        textX += ui.text(ui.regular, String.format(Locale.ROOT, "k utracení  (+%.1f za minutu)", world.evolutionSystem().pointsPerMinute()),
                textX, y + 3f, Ui.TEXT_DIM) + 14f;
        ui.text(ui.small, String.format(Locale.ROOT, "každý odemčený uzel zdraží další o %.0f %%",
                species.base().evolution().costGrowthPerNode() * 100f), textX, y + 6f, Ui.TEXT_DIM);
        if (!message.isEmpty() && System.nanoTime() - messageTime < MESSAGE_NANOS) {
            float messageX = width / 2f - ui.bold.width(message) / 2f;
            ui.text(ui.bold, message, Math.max(textX + 250f, messageX), y + 3f, messageGood ? 0xFF7FD68A : 0xFFE08A7A);
        }
        String closeLabel = "Zavřít (F4)";
        float closeWidth = ui.buttonWidth(closeLabel);
        if (ui.button(closeLabel, width - MARGIN - closeWidth, y - 2f, closeWidth, 28f, false)) {
            visible = false;
        }

        if (hovered != null) {
            Species.Availability availability = species.availability(hovered, world);
            if (ui.clicked(0, 0, width, ui.height())) {
                unlock(world, hovered, availability);
                hovered = species.currentLevel(hovered); // a trait moves on to its next level
                availability = species.availability(hovered, world);
            }
            drawTooltip(ui, species, hovered, availability);
        }
        ui.clickedAnywhere(); // clicks on the background do nothing
    }

    private void unlock(World world, EvolutionNode node, Species.Availability availability) {
        messageTime = System.nanoTime();
        if (availability.status() == Species.NodeStatus.AVAILABLE) {
            world.unlock(node.id());
            message = "Odemčeno: " + node.name();
            messageGood = true;
        } else if (availability.status() == Species.NodeStatus.UNLOCKED) {
            message = node.name() + " už je odemčeno";
            messageGood = true;
        } else {
            message = "Nelze odemknout " + node.name() + (availability.reason() != null ? " – " + availability.reason() : "");
            messageGood = false;
        }
    }

    private static void drawBranches(Ui ui, Species species, TreeLayout layout, float ox, float oy) {
        for (var entry : layout.branches().entrySet()) {
            TreeLayout.Box box = entry.getValue();
            int total = 0;
            int unlocked = 0;
            for (EvolutionNode node : species.tree().nodes()) {
                if (node.branch().equals(entry.getKey())) {
                    total++;
                    if (species.isUnlocked(node.id())) {
                        unlocked++;
                    }
                }
            }
            String name = Texts.branch(entry.getKey()).toUpperCase(Locale.ROOT);
            if (oy + box.y() < clipTop) {
                continue;
            }
            ui.text(ui.bold, name, ox + box.x(), oy + box.y() + 2f, 0xFFC9CED6);
            ui.text(ui.small, unlocked + " / " + total, ox + box.x() + ui.bold.width(name) + 10f, oy + box.y() + 4f, Ui.TEXT_DIM);
            ui.draw().rect(ox + box.x(), oy + box.y() + 22f, box.w(), 1f, 0xFF3A3F4A);
        }
    }

    private static void drawLines(Ui ui, World world, TreeLayout layout, float ox, float oy) {
        Species species = world.species();
        for (EvolutionNode node : species.tree().nodes()) {
            if (!node.isShown()) {
                continue;
            }
            TreeLayout.Box child = layout.node(node.id());
            int color = switch (species.availability(species.currentLevel(node), world).status()) {
                case UNLOCKED -> LINE_UNLOCKED;
                case AVAILABLE -> LINE_AVAILABLE;
                default -> LINE_LOCKED;
            };
            for (String required : node.requires()) {
                if (!species.tree().node(required).branch().equals(node.branch())) {
                    continue; // across branches: named in the tooltip instead of a long line
                }
                TreeLayout.Box parent = layout.node(required);
                float x1 = ox + parent.centerX();
                float y1 = oy + parent.bottom();
                float x2 = ox + child.centerX();
                float y2 = oy + child.y();
                float midY = (y1 + y2) / 2f;
                if (y2 <= clipTop) {
                    continue;
                }
                if (midY > clipTop) {
                    ui.draw().line(x1, Math.max(y1, clipTop), x1, midY, 2f, color);
                    ui.draw().line(x1 - 1f, midY, x2 + 1f, midY, 2f, color);
                }
                ui.draw().line(x2, Math.max(midY, clipTop), x2, y2, 2f, color);
            }
        }
    }

    /** Draws the nodes and returns the one under the mouse, or null. */
    private static EvolutionNode drawNodes(Ui ui, World world, TreeLayout layout, float ox, float oy, float visibleTop) {
        Species species = world.species();
        EvolutionNode hovered = null;
        int[] stageCounts = world.stageCounts();
        int population = Math.max(1, world.creatureCount());
        for (EvolutionNode card : species.tree().nodes()) {
            if (!card.isShown()) {
                continue;
            }
            TreeLayout.Box box = layout.node(card.id());
            float x = ox + box.x();
            float y = oy + box.y();
            if (y < clipTop) {
                continue; // scrolled up out of view
            }
            EvolutionNode node = species.currentLevel(card); // a levelled trait shows its next level (phase 10a)
            EvolutionNode.Trait trait = node.trait();
            boolean hover = ui.hovered(x, y, box.w(), box.h()) && ui.mouseY() >= visibleTop;
            if (hover) {
                hovered = node;
            }
            Species.NodeStatus status = species.availability(node, world).status();
            String level = trait != null ? " · stupeň " + trait.level() : "";
            int fill;
            int border;
            int text;
            int subText;
            String sub;
            switch (status) {
                case UNLOCKED -> {
                    fill = hover ? 0xFF3A7D47 : 0xFF2F6B3A;
                    border = 0xFF7FD68A;
                    text = Ui.TEXT;
                    subText = 0xFFBFE8C6;
                    sub = (trait != null ? "vše odemčeno · " : "odemčeno · ")
                            + carrying(stageCounts, species.stageOf(node.id()), population) + " % populace";
                }
                case AVAILABLE -> {
                    fill = hover ? 0xFF3E3726 : 0xFF2E2A20;
                    border = 0xFFE0B040;
                    text = Ui.TEXT;
                    subText = Ui.TEXT_ACCENT;
                    sub = species.cost(node) + " EP – " + (trait != null ? "stupeň " + trait.level() : "odemknout");
                }
                case EXCLUDED -> {
                    fill = 0xFF2B2224;
                    border = 0xFF7A3A3A;
                    text = 0xFF8C8080;
                    subText = 0xFF8C6B6B;
                    sub = "vyloučeno";
                }
                default -> {
                    fill = hover ? 0xFF2C2F35 : 0xFF24262B;
                    border = 0xFF555A63;
                    text = Ui.TEXT_DIM;
                    subText = 0xFF7C828B;
                    sub = species.cost(node) + " EP" + level;
                }
            }
            ui.draw().rect(x, y, box.w(), box.h(), fill);
            ui.draw().outline(x, y, box.w(), box.h(), status == Species.NodeStatus.AVAILABLE ? 2f : 1f, hover ? 0xFFFFFFFF : border);
            float pips = trait != null ? trait.levels() * 8f + 4f : 0f;
            String name = fit(ui.bold, trait != null ? trait.name() : node.name(), box.w() - 16f - pips);
            ui.text(ui.bold, name, x + 8f, y + 5f, text);
            if (trait != null) { // one square per level, filled when unlocked
                int owned = species.unlockedLevels(node);
                for (int i = 0; i < trait.levels(); i++) {
                    float px = x + box.w() - 8f - (trait.levels() - i) * 8f;
                    ui.draw().rect(px, y + 9f, 6f, 6f, i < owned ? 0xFF7FD68A : 0xFF30343C);
                }
            }
            ui.text(ui.small, sub, x + 8f, y + 25f, subText);
        }
        return hovered;
    }

    /** Share (percent) of the population born with this stage or a later one, i.e. carrying the node's traits. */
    private static int carrying(int[] stageCounts, int stage, int population) {
        int count = 0;
        for (int s = Math.max(0, stage); s < stageCounts.length; s++) {
            count += stageCounts[s];
        }
        return Math.round(100f * count / population);
    }

    private static void drawLegend(Ui ui, float x, float y) {
        int[][] items = {{0xFF2F6B3A, 0xFF7FD68A}, {0xFF2E2A20, 0xFFE0B040}, {0xFF24262B, 0xFF555A63}, {0xFF2B2224, 0xFF7A3A3A}};
        String[] labels = {"odemčeno", "dostupné", "zamčeno", "vyloučeno"};
        for (int i = 0; i < items.length; i++) {
            ui.draw().rect(x, y + 2f, 14f, 14f, items[i][0]);
            ui.draw().outline(x, y + 2f, 14f, 14f, 1f, items[i][1]);
            x += 20f;
            x += ui.text(ui.small, labels[i], x, y + 1f, Ui.TEXT_DIM) + 18f;
        }
        ui.text(ui.small, "Najeď myší na uzel pro detail, kliknutím odemkneš. Nové znaky se objeví u mláďat.", x + 10f, y + 1f, Ui.TEXT_DIM);
    }

    private static void drawTooltip(Ui ui, Species species, EvolutionNode node, Species.Availability availability) {
        float padding = 10f;
        float inner = TOOLTIP_WIDTH - 2 * padding;
        List<String> description = Ui.wrap(ui.regular, node.description(), inner);
        List<String> effects = new ArrayList<>();
        for (Effect effect : node.effects()) {
            effects.addAll(Ui.wrap(ui.small, "• " + Texts.effect(effect), inner));
        }
        String statusText = Texts.status(availability.status());
        List<String> reason = availability.reason() != null
                ? Ui.wrap(ui.small, availability.reason(), inner) : List.of();

        float h = padding + ui.bold.lineHeight() + 2f
                + description.size() * ui.regular.lineHeight() + 6f
                + effects.size() * ui.small.lineHeight() + 6f
                + ui.small.lineHeight() + reason.size() * ui.small.lineHeight() + padding;
        float x = ui.mouseX() + 18f;
        float y = ui.mouseY() + 18f;
        if (x + TOOLTIP_WIDTH > ui.width() - 4f) {
            x = ui.mouseX() - TOOLTIP_WIDTH - 12f;
        }
        if (y + h > ui.height() - 4f) {
            y = Math.max(4f, ui.height() - 4f - h);
        }
        ui.draw().rect(x, y, TOOLTIP_WIDTH, h, 0xF5181B20);
        ui.draw().outline(x, y, TOOLTIP_WIDTH, h, 1f, 0xFF60656F);

        float ty = y + padding;
        String header = node.name();
        ui.text(ui.bold, header, x + padding, ty, Ui.TEXT);
        String cost = species.cost(node) + " EP";
        ui.text(ui.bold, cost, x + TOOLTIP_WIDTH - padding - ui.bold.width(cost), ty, Ui.TEXT_ACCENT);
        ty += ui.bold.lineHeight() + 2f;
        for (String line : description) {
            ui.text(ui.regular, line, x + padding, ty, 0xFFD5D9DF);
            ty += ui.regular.lineHeight();
        }
        ty += 6f;
        for (String line : effects) {
            ui.text(ui.small, line, x + padding, ty, 0xFFB7C7DA);
            ty += ui.small.lineHeight();
        }
        ty += 6f;
        int statusColor = switch (availability.status()) {
            case UNLOCKED -> 0xFF7FD68A;
            case AVAILABLE -> Ui.TEXT_ACCENT;
            case LOCKED -> Ui.TEXT_DIM;
            case EXCLUDED -> 0xFFE08A7A;
        };
        ui.text(ui.small, statusText, x + padding, ty, statusColor);
        ty += ui.small.lineHeight();
        for (String line : reason) {
            ui.text(ui.small, line, x + padding, ty, statusColor);
            ty += ui.small.lineHeight();
        }
    }

    /** Shortens text with an ellipsis to fit {@code maxWidth}. */
    static String fit(Font font, String text, float maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String shortened = text;
        while (!shortened.isEmpty() && font.width(shortened + "…") > maxWidth) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened + "…";
    }
}

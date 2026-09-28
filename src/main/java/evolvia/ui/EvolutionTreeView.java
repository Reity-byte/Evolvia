package evolvia.ui;

import evolvia.evolution.Effect;
import evolvia.evolution.EvolutionNode;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.Species;
import evolvia.world.Science;
import evolvia.world.Tribe;
import evolvia.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tree window (DESIGN.md §9) for evolution and, since phase 10b, science: all branches as graphs of nodes with
 * prerequisite lines, node states, a tooltip with the description, effects and why a node is locked. A click on
 * an evolution node unlocks it; a click on a discovery makes it the research target, Shift+click queues it.
 * Tabs switch between the two trees. Covers the screen below the top bar.
 */
public final class EvolutionTreeView {

    /** Which tree the window shows. */
    public enum Mode { EVOLUTION, SCIENCE }

    private static final float MARGIN = 24f;
    private static final float HEADER = 52f;
    private static final float TOOLTIP_WIDTH = 310f;
    private static final long MESSAGE_NANOS = 4_000_000_000L;
    private static final float SCROLL_STEP = 60f;

    private static final int LINE_UNLOCKED = 0xFF5FAF6A;
    private static final int LINE_AVAILABLE = 0xFFB08A3A;
    private static final int LINE_LOCKED = 0xFF4A4F58;

    private boolean visible;
    private Mode mode = Mode.EVOLUTION;
    private float scroll;
    /** Nothing of the scrolled tree is drawn above this (it would cover the top bar; the header hides the rest). */
    private static float clipTop;
    private String message = "";
    private boolean messageGood;
    private long messageTime;

    public boolean isVisible() {
        return visible;
    }

    public Mode mode() {
        return mode;
    }

    /** Opens or closes the evolution tree (F4). */
    public void toggle() {
        toggle(Mode.EVOLUTION);
    }

    /** Opens the window on {@code tree}, or closes it when it already shows that tree. */
    public void toggle(Mode tree) {
        if (visible && mode == tree) {
            visible = false;
        } else {
            open(tree);
        }
    }

    public void open(Mode tree) {
        if (mode != tree) {
            scroll = 0f;
        }
        mode = tree;
        visible = true;
    }

    public void close() {
        visible = false;
    }

    /** A message after a click (green when it worked). */
    record Message(String text, boolean good) {
    }

    /** What the window shows: the evolution tree or the science tree. */
    private interface Model {

        EvolutionTree tree();

        /** The node a card stands for now (a trait's next level). */
        EvolutionNode current(EvolutionNode card);

        int unlockedLevels(EvolutionNode node);

        Species.Availability availability(EvolutionNode node);

        int cost(EvolutionNode node);

        String unit();

        /** Second line of a card. */
        String sub(EvolutionNode node, Species.NodeStatus status);

        /** Research done so far (0..1), or -1 for no bar. */
        float progress(EvolutionNode node);

        /** The research target is drawn highlighted. */
        boolean isTarget(EvolutionNode node);

        /** Points, their income and a hint for the header. */
        String[] header();

        /** Status line of the tooltip for an available node. */
        String availableStatus();

        /** More tooltip lines (science: the buildings a discovery allows). */
        List<String> extra(EvolutionNode node);

        /** Labels of the four node states and a hint, for the legend. */
        String[] legend();

        Message click(EvolutionNode node, Species.Availability availability, boolean shift);
    }

    /** The species' evolution (EP). */
    private static Model evolution(World world) {
        Species species = world.species();
        int[] stageCounts = world.stageCounts();
        int population = Math.max(1, world.creatureCount());
        return new Model() {
            public EvolutionTree tree() {
                return species.tree();
            }

            public EvolutionNode current(EvolutionNode card) {
                return species.currentLevel(card);
            }

            public int unlockedLevels(EvolutionNode node) {
                return species.unlockedLevels(node);
            }

            public Species.Availability availability(EvolutionNode node) {
                return species.availability(node, world);
            }

            public int cost(EvolutionNode node) {
                return species.cost(node);
            }

            public String unit() {
                return "EP";
            }

            public String sub(EvolutionNode node, Species.NodeStatus status) {
                EvolutionNode.Trait trait = node.trait();
                return switch (status) {
                    case UNLOCKED -> (trait != null ? "vše odemčeno · " : "odemčeno · ")
                            + carrying(stageCounts, species.stageOf(node.id()), population) + " % populace";
                    case AVAILABLE -> cost(node) + " EP – " + (trait != null ? "stupeň " + trait.level() : "odemknout");
                    case EXCLUDED -> "vyloučeno";
                    case LOCKED -> cost(node) + " EP" + (trait != null ? " · stupeň " + trait.level() : "");
                };
            }

            public float progress(EvolutionNode node) {
                return -1f;
            }

            public boolean isTarget(EvolutionNode node) {
                return false;
            }

            public String[] header() {
                return new String[]{String.format(Locale.ROOT, "%.0f EP", species.points()),
                        String.format(Locale.ROOT, "k utracení  (+%.1f za minutu)", world.evolutionSystem().pointsPerMinute()),
                        String.format(Locale.ROOT, "každý odemčený uzel zdraží další o %.0f %%",
                                species.base().evolution().costGrowthPerNode() * 100f)};
            }

            public String availableStatus() {
                return "Dostupné – klikni pro odemčení";
            }

            public List<String> extra(EvolutionNode node) {
                return List.of();
            }

            public String[] legend() {
                return new String[]{"odemčeno", "dostupné", "zamčeno", "vyloučeno",
                        "Najeď myší na uzel pro detail, kliknutím odemkneš. Nové znaky se objeví u mláďat."};
            }

            public Message click(EvolutionNode node, Species.Availability availability, boolean shift) {
                if (availability.status() == Species.NodeStatus.AVAILABLE) {
                    world.unlock(node.id());
                    return new Message("Odemčeno: " + node.name(), true);
                }
                if (availability.status() == Species.NodeStatus.UNLOCKED) {
                    return new Message(node.name() + " už je odemčeno", true);
                }
                return new Message("Nelze odemknout " + node.name()
                        + (availability.reason() != null ? " – " + availability.reason() : ""), false);
            }
        };
    }

    /** The people's science (knowledge, phase 10b). */
    private static Model science(World world) {
        Science science = world.science();
        return new Model() {
            public EvolutionTree tree() {
                return science.tree();
            }

            public EvolutionNode current(EvolutionNode card) {
                return card;
            }

            public int unlockedLevels(EvolutionNode node) {
                return 0;
            }

            public Species.Availability availability(EvolutionNode node) {
                return science.availability(node, world);
            }

            public int cost(EvolutionNode node) {
                return science.cost(node);
            }

            public String unit() {
                return "ZN";
            }

            public String sub(EvolutionNode node, Species.NodeStatus status) {
                if (status == Species.NodeStatus.UNLOCKED) {
                    return "objeveno";
                }
                int queued = science.queue().indexOf(node.id());
                int done = Math.round(science.share(node) * 100f);
                if (node.id().equals(science.target())) {
                    return "zkoumá se · " + done + " %";
                }
                if (queued >= 0) {
                    return "ve frontě " + (queued + 1) + ". · " + cost(node) + " ZN";
                }
                String cost = cost(node) + " ZN";
                if (done > 0) {
                    return cost + " · hotovo " + done + " %";
                }
                return status == Species.NodeStatus.AVAILABLE ? cost + " – zkoumat" : cost;
            }

            public float progress(EvolutionNode node) {
                return science.isDiscovered(node.id()) || science.progress(node.id()) <= 0f ? -1f : science.share(node);
            }

            public boolean isTarget(EvolutionNode node) {
                return node.id().equals(science.target());
            }

            public String[] header() {
                if (!science.isActive()) {
                    return new String[]{String.format(Locale.ROOT, "%.0f ZN", science.stored()),
                            "Věda začne, až tvůj lid získá Řeč (větev Mysl v Evoluci)", ""};
                }
                EvolutionNode target = science.target() != null ? science.tree().node(science.target()) : null;
                return new String[]{String.format(Locale.ROOT, "+%.1f ZN za minutu", science.perMinute()),
                        target != null ? "zkoumá se " + target.name() : "nic se nezkoumá – vyber objev",
                        String.format(Locale.ROOT, "každý objev zdraží další o %.0f %% · Shift+klik = do fronty (max %d)",
                                science.rules().costGrowthPerDiscovery() * 100f, science.rules().queueMax())};
            }

            public String availableStatus() {
                return "Klikni: zkoumat, Shift+klik: do fronty";
            }

            public List<String> extra(EvolutionNode node) {
                List<String> lines = new ArrayList<>();
                for (Tribe.BuildingType type : world.tribe().buildings()) {
                    if (node.id().equals(type.requires())) {
                        lines.add("Nová stavba: " + type.name());
                    }
                }
                if (science.progress(node.id()) > 0f && !science.isDiscovered(node.id())) {
                    lines.add(String.format(Locale.ROOT, "Prozkoumáno %.0f z %d ZN", science.progress(node.id()), cost(node)));
                }
                return lines;
            }

            public String[] legend() {
                return new String[]{"objeveno", "lze zkoumat", "zamčeno", "",
                        "Kliknutím vybereš, co lid zkoumá; Shift+klik přidá do fronty, na zkoumaném ho zruší."};
            }

            public Message click(EvolutionNode node, Species.Availability availability, boolean shift) {
                if (availability.status() == Species.NodeStatus.UNLOCKED) {
                    return new Message(node.name() + " už je objeveno", true);
                }
                boolean planned = node.id().equals(science.target()) || science.queue().contains(node.id());
                if (shift && planned) {
                    science.cancel(node.id(), world);
                    return new Message("Zrušeno: " + node.name(), true);
                }
                if (shift) {
                    return science.enqueue(node.id(), world) ? new Message("Ve frontě: " + node.name(), true)
                            : new Message("Do fronty nelze: " + node.name() + " (plná fronta nebo chybí předchozí objev)", false);
                }
                if (availability.status() != Species.NodeStatus.AVAILABLE) {
                    return new Message("Nelze zkoumat " + node.name()
                            + (availability.reason() != null ? " – " + availability.reason() : ""), false);
                }
                science.setTarget(node.id(), world);
                return new Message("Zkoumá se: " + node.name() + (science.isActive() ? "" : " (věda začne s Řečí)"), true);
            }
        };
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
        Model model = mode == Mode.EVOLUTION ? evolution(world) : science(world);
        float width = ui.width();
        float height = ui.height() - top;
        ui.draw().rect(0, top, width, height, 0xE6101216);
        ui.block(0, top, width, height);

        // Tree (drawn first; the header covers what scrolls under it)
        TreeLayout layout = TreeLayout.of(model.tree(), width - 2 * MARGIN);
        float contentTop = top + HEADER;
        float contentHeight = layout.height() + 26f + 30f; // tree + legend
        float maxScroll = Math.max(0f, contentHeight - (ui.height() - contentTop - 10f));
        scroll = Math.clamp(scroll - (float) scrollY * SCROLL_STEP, 0f, maxScroll);
        float originX = Math.max(MARGIN, (width - layout.width()) / 2f);
        float originY = contentTop + 10f - scroll;
        clipTop = top;
        drawBranches(ui, model, layout, originX, originY);
        drawLines(ui, model, layout, originX, originY);
        EvolutionNode hovered = drawNodes(ui, model, layout, originX, originY, contentTop);
        drawLegend(ui, model, originX, originY + layout.height() + 26f);
        if (maxScroll > 0f) {
            float trackHeight = ui.height() - contentTop - 20f;
            float barHeight = trackHeight * trackHeight / (trackHeight + maxScroll);
            ui.draw().rect(width - 8f, contentTop + 10f + (trackHeight - barHeight) * scroll / maxScroll, 4f, barHeight, 0xFF60656F);
        }

        // Header: the two trees as tabs, the points, a hint, close
        ui.draw().rect(0, top, width, HEADER, 0xFF101216);
        ui.draw().rect(0, contentTop - 1f, width, 1f, 0xFF2A2E36);
        float x = MARGIN;
        float y = top + 14f;
        for (Mode each : Mode.values()) {
            String label = each == Mode.EVOLUTION ? "Evoluce (F4)" : "Věda (F2)";
            float bw = ui.buttonWidth(label);
            if (ui.button(label, x, y - 4f, bw, 30f, mode == each)) {
                open(each);
            }
            x += bw + 6f;
        }
        String[] header = model.header();
        float textX = x + 18f;
        textX += ui.text(ui.bold, header[0], textX, y + 3f, Ui.TEXT_ACCENT) + 10f;
        textX += ui.text(ui.regular, header[1], textX, y + 3f, Ui.TEXT_DIM) + 14f;
        String closeLabel = "Zavřít";
        float closeWidth = ui.buttonWidth(closeLabel);
        float closeX = width - MARGIN - closeWidth;
        if (textX + ui.small.width(header[2]) < closeX - 10f) {
            ui.text(ui.small, header[2], textX, y + 6f, Ui.TEXT_DIM);
        }
        if (ui.button(closeLabel, closeX, y - 2f, closeWidth, 28f, false)) {
            visible = false;
        }
        if (!message.isEmpty() && System.nanoTime() - messageTime < MESSAGE_NANOS) {
            float mw = ui.bold.width(message) + 20f;
            float mx = (width - mw) / 2f;
            ui.draw().rect(mx, contentTop + 6f, mw, ui.bold.lineHeight() + 8f, 0xF0181B20);
            ui.draw().outline(mx, contentTop + 6f, mw, ui.bold.lineHeight() + 8f, 1f, messageGood ? 0xFF5FAF6A : 0xFFC0473A);
            ui.text(ui.bold, message, mx + 10f, contentTop + 10f, messageGood ? 0xFF7FD68A : 0xFFE08A7A);
        }

        if (hovered != null) {
            Species.Availability availability = model.availability(hovered);
            if (ui.clicked(0, 0, width, ui.height())) {
                Message result = model.click(hovered, availability, ui.shiftDown());
                message = result.text();
                messageGood = result.good();
                messageTime = System.nanoTime();
                hovered = model.current(hovered); // a trait moves on to its next level
                availability = model.availability(hovered);
            }
            drawTooltip(ui, model, hovered, availability);
        }
        ui.clickedAnywhere(); // clicks on the background do nothing
    }

    private static void drawBranches(Ui ui, Model model, TreeLayout layout, float ox, float oy) {
        for (var entry : layout.branches().entrySet()) {
            TreeLayout.Box box = entry.getValue();
            int total = 0;
            int unlocked = 0;
            for (EvolutionNode node : model.tree().nodes()) {
                if (node.branch().equals(entry.getKey())) {
                    total++;
                    if (model.availability(node).status() == Species.NodeStatus.UNLOCKED) {
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

    private static void drawLines(Ui ui, Model model, TreeLayout layout, float ox, float oy) {
        EvolutionTree tree = model.tree();
        for (EvolutionNode node : tree.nodes()) {
            if (!node.isShown()) {
                continue;
            }
            TreeLayout.Box child = layout.node(node.id());
            int color = switch (model.availability(model.current(node)).status()) {
                case UNLOCKED -> LINE_UNLOCKED;
                case AVAILABLE -> LINE_AVAILABLE;
                default -> LINE_LOCKED;
            };
            for (String required : node.requires()) {
                if (!tree.node(required).branch().equals(node.branch())) {
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
    private static EvolutionNode drawNodes(Ui ui, Model model, TreeLayout layout, float ox, float oy, float visibleTop) {
        EvolutionNode hovered = null;
        for (EvolutionNode card : model.tree().nodes()) {
            if (!card.isShown()) {
                continue;
            }
            TreeLayout.Box box = layout.node(card.id());
            float x = ox + box.x();
            float y = oy + box.y();
            if (y < clipTop) {
                continue; // scrolled up out of view
            }
            EvolutionNode node = model.current(card); // a levelled trait shows its next level (phase 10a)
            EvolutionNode.Trait trait = node.trait();
            boolean hover = ui.hovered(x, y, box.w(), box.h()) && ui.mouseY() >= visibleTop;
            if (hover) {
                hovered = node;
            }
            Species.NodeStatus status = model.availability(node).status();
            boolean target = model.isTarget(node);
            int fill;
            int border;
            int text;
            int subText;
            switch (status) {
                case UNLOCKED -> {
                    fill = hover ? 0xFF3A7D47 : 0xFF2F6B3A;
                    border = 0xFF7FD68A;
                    text = Ui.TEXT;
                    subText = 0xFFBFE8C6;
                }
                case AVAILABLE -> {
                    fill = target ? (hover ? 0xFF35507A : 0xFF2C4468) : hover ? 0xFF3E3726 : 0xFF2E2A20;
                    border = target ? 0xFF8FB8F0 : 0xFFE0B040;
                    text = Ui.TEXT;
                    subText = target ? 0xFFBFD6F5 : Ui.TEXT_ACCENT;
                }
                case EXCLUDED -> {
                    fill = 0xFF2B2224;
                    border = 0xFF7A3A3A;
                    text = 0xFF8C8080;
                    subText = 0xFF8C6B6B;
                }
                default -> {
                    fill = hover ? 0xFF2C2F35 : 0xFF24262B;
                    border = 0xFF555A63;
                    text = Ui.TEXT_DIM;
                    subText = 0xFF7C828B;
                }
            }
            ui.draw().rect(x, y, box.w(), box.h(), fill);
            ui.draw().outline(x, y, box.w(), box.h(), status == Species.NodeStatus.AVAILABLE ? 2f : 1f, hover ? 0xFFFFFFFF : border);
            float pips = trait != null ? trait.levels() * 8f + 4f : 0f;
            String name = fit(ui.bold, trait != null ? trait.name() : node.name(), box.w() - 16f - pips);
            ui.text(ui.bold, name, x + 8f, y + 5f, text);
            if (trait != null) { // one square per level, filled when unlocked
                int owned = model.unlockedLevels(node);
                for (int i = 0; i < trait.levels(); i++) {
                    float px = x + box.w() - 8f - (trait.levels() - i) * 8f;
                    ui.draw().rect(px, y + 9f, 6f, 6f, i < owned ? 0xFF7FD68A : 0xFF30343C);
                }
            }
            ui.text(ui.small, fit(ui.small, model.sub(node, status), box.w() - 16f), x + 8f, y + 25f, subText);
            float progress = model.progress(node);
            if (progress >= 0f) { // research done so far
                ui.draw().rect(x + 1f, y + box.h() - 4f, box.w() - 2f, 3f, 0xFF30343C);
                ui.draw().rect(x + 1f, y + box.h() - 4f, (box.w() - 2f) * progress, 3f, 0xFF8FB8F0);
            }
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

    private static void drawLegend(Ui ui, Model model, float x, float y) {
        int[][] items = {{0xFF2F6B3A, 0xFF7FD68A}, {0xFF2E2A20, 0xFFE0B040}, {0xFF24262B, 0xFF555A63}, {0xFF2B2224, 0xFF7A3A3A}};
        String[] labels = model.legend();
        for (int i = 0; i < items.length; i++) {
            if (labels[i].isEmpty()) {
                continue;
            }
            ui.draw().rect(x, y + 2f, 14f, 14f, items[i][0]);
            ui.draw().outline(x, y + 2f, 14f, 14f, 1f, items[i][1]);
            x += 20f;
            x += ui.text(ui.small, labels[i], x, y + 1f, Ui.TEXT_DIM) + 18f;
        }
        ui.text(ui.small, labels[4], x + 10f, y + 1f, Ui.TEXT_DIM);
    }

    private static void drawTooltip(Ui ui, Model model, EvolutionNode node, Species.Availability availability) {
        float padding = 10f;
        float inner = TOOLTIP_WIDTH - 2 * padding;
        List<String> description = Ui.wrap(ui.regular, node.description(), inner);
        List<String> effects = new ArrayList<>();
        for (Effect effect : node.effects()) {
            effects.addAll(Ui.wrap(ui.small, "• " + Texts.effect(effect), inner));
        }
        for (String line : model.extra(node)) {
            effects.addAll(Ui.wrap(ui.small, "• " + line, inner));
        }
        String statusText = availability.status() == Species.NodeStatus.AVAILABLE ? model.availableStatus()
                : model.unit().equals("ZN") && availability.status() == Species.NodeStatus.UNLOCKED ? "Objeveno"
                : Texts.status(availability.status());
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
        ui.text(ui.bold, node.name(), x + padding, ty, Ui.TEXT);
        String cost = model.cost(node) + " " + model.unit();
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

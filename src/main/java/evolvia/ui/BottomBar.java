package evolvia.ui;

import evolvia.core.Input;
import evolvia.god.DivinePower;
import evolvia.god.Faith;
import evolvia.god.GodConfig;
import evolvia.god.GodPowers;
import evolvia.render.GodEffectsRenderer;
import evolvia.world.Groups;
import evolvia.world.Settlement;
import evolvia.world.Tribe;
import evolvia.world.World;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * The bottom bar (phase 9i, in the spirit of The Universim), left to right: the people and the tribe, the stock
 * of their camp, tabs, the cards of the open tab and faith. The "Zásahy" tab holds the god's powers (DESIGN.md
 * §9), the "Stavby" tab the building plans once there is a tribe (phase 9h). A selected power shows its area on
 * the ground; left click uses it (terrain powers repeat while the button is held), right click or ESC puts it
 * away. A selected plan is placed near the camp for faith.
 */
public final class BottomBar {

    public static final float HEIGHT = 64f;

    static final float GAP = 8f;
    static final float PEOPLE_WIDTH = 150f;
    static final float STOCK_WIDTH = 124f;
    static final float TABS_WIDTH = 84f;
    static final float FAITH_WIDTH = 158f;
    static final float MIN_CARD = 64f;
    static final float MAX_CARD = 120f;
    private static final float CARD_HEIGHT = 44f;
    private static final float TOOLTIP_WIDTH = 330f;
    private static final long MESSAGE_NANOS = 2_500_000_000L;
    private static final long HIGHLIGHT_NANOS = 10_000_000_000L;

    /** The tabs of the bar. */
    public enum Tab {
        POWERS("Zásahy"), BUILDINGS("Stavby");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    /** Horizontal layout of the bar for one screen width (pure, so tests can check it without a window). */
    record Layout(float peopleX, float stockX, float tabsX, float cardsX, float cardWidth, float faithX) {

        float cardX(int index) {
            return cardsX + index * (cardWidth + GAP);
        }

        float cardsEnd(int cards) {
            return cards == 0 ? cardsX : cardX(cards - 1) + cardWidth;
        }
    }

    /** Lays the sections out: fixed side sections, cards between them (as wide as fits, centred). */
    static Layout layout(float width, int cards) {
        float peopleX = GAP;
        float stockX = peopleX + PEOPLE_WIDTH + GAP;
        float tabsX = stockX + STOCK_WIDTH + GAP;
        float faithX = width - FAITH_WIDTH - GAP;
        float start = tabsX + TABS_WIDTH + GAP;
        float room = faithX - GAP - start;
        float card = cards == 0 ? MAX_CARD : Math.clamp((room - (cards - 1) * GAP) / cards, MIN_CARD, MAX_CARD);
        float used = cards * card + Math.max(0, cards - 1) * GAP;
        return new Layout(peopleX, stockX, tabsX, start + Math.max(0f, (room - used) / 2f), card, faithX);
    }

    private Tab tab = Tab.POWERS;
    private DivinePower armed;
    /** Building type of an armed plan, or null. */
    private String armedPlan;
    private float repeatTimer;
    private String message = "";
    private long messageTime;
    private boolean hadTribe;
    /** The world of the last frame: a loaded or new world does not light the building tab up. */
    private World lastWorld;
    private long highlightUntil;

    /** The selected power, or null. */
    public DivinePower armed() {
        return armed;
    }

    public Tab tab() {
        return tab;
    }

    public void disarm() {
        armed = null;
        armedPlan = null;
    }

    /** A power or a building plan is selected. */
    public boolean isArmed() {
        return armed != null || armedPlan != null;
    }

    /** Switches to the next tab (Tab key); the building tab only once there is a tribe. */
    public void nextTab(World world) {
        if (tab == Tab.POWERS && world.tribeGroup() == null) {
            show("Stavby odemkne kmen (uzel Kmen v Evoluci)");
            return;
        }
        select(tab == Tab.POWERS ? Tab.BUILDINGS : Tab.POWERS);
    }

    private void select(Tab next) {
        if (next != tab) {
            tab = next;
            disarm();
        }
    }

    private void show(String text) {
        message = text;
        messageTime = System.nanoTime();
    }

    /** Lays out, draws and handles clicks of the bar (call during input handling). */
    public void build(Ui ui, World world) {
        boolean tribe = world.tribeGroup() != null;
        if (tribe && !hadTribe && world == lastWorld) {
            highlightUntil = System.nanoTime() + HIGHLIGHT_NANOS; // the building tab lights up once the tribe is founded
        }
        hadTribe = tribe;
        lastWorld = world;
        if (!tribe && tab == Tab.BUILDINGS) {
            select(Tab.POWERS);
        }

        float width = ui.width();
        float top = ui.height() - HEIGHT;
        ui.draw().rect(0, top, width, HEIGHT, 0xF0161920);
        ui.draw().rect(0, top, width, 1f, Ui.PANEL_BORDER);
        ui.block(0, top, width, HEIGHT);

        List<Tribe.BuildingType> plans = world.tribe().buildings();
        int cards = tab == Tab.POWERS ? DivinePower.values().length : plans.size();
        Layout layout = layout(width, cards);
        people(ui, world, layout.peopleX(), top);
        separator(ui, layout.stockX() - GAP / 2f, top);
        stock(ui, world, layout.stockX(), top);
        separator(ui, layout.tabsX() - GAP / 2f, top);
        tabs(ui, layout.tabsX(), top, tribe);
        separator(ui, layout.faithX() - GAP / 2f, top);
        faith(ui, world, layout.faithX(), top);

        Runnable tooltip = tab == Tab.POWERS ? powers(ui, world, layout, top) : plans(ui, world, plans, layout, top);

        if (!message.isEmpty() && System.nanoTime() - messageTime < MESSAGE_NANOS) {
            float mw = ui.bold.width(message);
            float mx = (width - mw) / 2f;
            float my = top - 34f;
            ui.draw().rect(mx - 10f, my - 4f, mw + 20f, ui.bold.lineHeight() + 8f, 0xE0181B20);
            ui.text(ui.bold, message, mx, my, 0xFFE08A7A);
        } else if (tooltip == null && isArmed()) {
            String hint = armedPlan != null
                    ? world.tribe().building(armedPlan).name() + ": klikni poblíž tábora kmene, pravé tlačítko zruší"
                    : world.godPowers().config().of(armed).name() + ": klikni do krajiny"
                    + (armed.repeats() ? " (podrž pro opakování)" : "") + ", pravé tlačítko zruší";
            float hw = ui.small.width(hint);
            float hx = (width - hw) / 2f;
            ui.draw().rect(hx - 8f, top - 28f, hw + 16f, ui.small.lineHeight() + 8f, 0xC0181B20);
            ui.text(ui.small, hint, hx, top - 24f, Ui.TEXT);
        }
        if (tooltip != null) {
            tooltip.run();
        }
    }

    private static void separator(Ui ui, float x, float top) {
        ui.draw().rect(x, top + 10f, 1f, HEIGHT - 20f, Ui.PANEL_BORDER);
    }

    private static float line1(Ui ui, float top) {
        return top + 10f;
    }

    private static float line2(Ui ui, float top) {
        return top + 14f + ui.regular.lineHeight();
    }

    /** The people, the tribe and its mood, the wild kin and the game. */
    private static void people(Ui ui, World world, float x, float top) {
        float y = line1(ui, top);
        float dx = stat(ui, "Lid", Integer.toString(world.population()), Ui.TEXT, x, y);
        Groups.Group tribe = world.tribeGroup();
        Settlement.Mood mood = world.settlement().mood();
        if (tribe != null) {
            stat(ui, "Kmen", Integer.toString(tribe.size), moodColor(mood), x + dx, y);
        }
        y = line2(ui, top);
        dx = stat(ui, "Divocí", Integer.toString(world.creatureCount() - world.population()), Ui.TEXT, x, y);
        stat(ui, "Zvěř", Integer.toString(world.animalCount()), Ui.TEXT, x + dx, y);
        if (ui.hovered(x, top, PEOPLE_WIDTH, HEIGHT)) {
            String text = tribe != null
                    ? String.format(Locale.ROOT, "Tvůj lid %d, z toho kmen %d (%s) · divocí lidé %d · zvěř %d",
                    world.population(), tribe.size, mood.label, world.creatureCount() - world.population(), world.animalCount())
                    : String.format(Locale.ROOT, "Tvůj lid %d · divocí lidé %d · zvěř %d (kmen vznikne s uzlem Kmen)",
                    world.population(), world.creatureCount() - world.population(), world.animalCount());
            hint(ui, text, x, top);
        }
    }

    private static int moodColor(Settlement.Mood mood) {
        return mood == Settlement.Mood.AFRAID ? 0xFFE08A7A : mood == Settlement.Mood.CONTENT ? 0xFF7FD68A : Ui.TEXT;
    }

    /** The stock of the people's camp (the tribe's, else the herd with the biggest stock). */
    private static void stock(Ui ui, World world, float x, float top) {
        Groups.Group camp = world.playerCamp();
        if (camp == null) {
            ui.text(ui.regular, "Bez tábora", x, line1(ui, top), Ui.TEXT_DIM);
            ui.text(ui.small, "sběr přijde s Nástroji", x, line2(ui, top) + 2f, Ui.TEXT_DIM);
            if (ui.hovered(x, top, STOCK_WIDTH, HEIGHT)) {
                hint(ui, "Tvůj lid zatím nemá tábor: první donesené dřevo nebo kámen ho založí (uzel Nástroje)", x, top);
            }
            return;
        }
        float cap = world.stockCap(camp);
        List<String> materials = world.materials();
        for (int i = 0; i < materials.size() && i < 2; i++) {
            String material = materials.get(materials.size() - 1 - i); // wood first, then stone
            float amount = camp.stock(material);
            float y = i == 0 ? line1(ui, top) : line2(ui, top);
            float lw = ui.text(ui.regular, capitalize(Texts.material(material)), x, y, Ui.TEXT_DIM);
            float vw = ui.text(ui.bold, String.format(Locale.ROOT, "%.0f", amount), x + lw + 6f, y,
                    amount >= cap ? Ui.TEXT_ACCENT : Ui.TEXT);
            ui.text(ui.small, String.format(Locale.ROOT, "/ %.0f", cap), x + lw + vw + 10f, y + 2f, Ui.TEXT_DIM);
        }
        if (ui.hovered(x, top, STOCK_WIDTH, HEIGHT)) {
            hint(ui, String.format(Locale.ROOT, "Zásoby %s · strop %.0f každé suroviny%s",
                    camp.tribe ? "tábora kmene" : "tábora stáda #" + camp.id, cap,
                    camp.tribe && world.settlement().storageFactor() > 1f ? " (se skladem)" : ""), x, top);
        }
    }

    private void tabs(Ui ui, float x, float top, boolean tribe) {
        float h = (HEIGHT - 12f - 4f) / 2f;
        float y = top + 6f;
        for (Tab each : Tab.values()) {
            boolean enabled = each == Tab.POWERS || tribe;
            if (ui.button(each.label, x, y, TABS_WIDTH, h, tab == each)) {
                if (enabled) {
                    select(each);
                } else {
                    show("Stavby odemkne kmen (uzel Kmen v Evoluci)");
                }
            }
            if (!enabled) {
                ui.draw().rect(x, y, TABS_WIDTH, h, 0x80101216);
            } else if (each == Tab.BUILDINGS && tab != each && System.nanoTime() < highlightUntil) {
                boolean on = (System.nanoTime() / 400_000_000L) % 2 == 0;
                ui.draw().outline(x, y, TABS_WIDTH, h, 2f, on ? 0xFFF2C75C : 0xFF8A7440);
            }
            y += h + 4f;
        }
    }

    /** Faith, its growth and the god's alignment (good / evil). */
    private static void faith(Ui ui, World world, float x, float top) {
        Faith faith = world.godPowers().faith();
        float y = line1(ui, top);
        float dx = stat(ui, "Víra", String.format(Locale.ROOT, "%.0f", faith.points()), Ui.TEXT_ACCENT, x, y);
        ui.text(ui.small, String.format(Locale.ROOT, "+%.1f/min", faith.perMinute()), x + dx - 8f, y + 2f, Ui.TEXT_DIM);

        y = line2(ui, top);
        float barW = 60f;
        float barY = y + 6f;
        float centre = x + barW / 2f;
        ui.draw().rect(x, barY, barW, 6f, 0xFF30343C);
        float value = faith.alignment();
        if (value > 0) {
            ui.draw().rect(centre, barY, barW / 2f * value, 6f, 0xFFE8D27A);
        } else if (value < 0) {
            ui.draw().rect(centre + barW / 2f * value, barY, -barW / 2f * value, 6f, 0xFFC0473A);
        }
        ui.draw().rect(centre - 0.5f, barY - 2f, 1f, 10f, 0xFF9AA0A8);
        String label = value > 0.05f ? "dobrý bůh" : value < -0.05f ? "zlý bůh" : "neutrální";
        ui.text(ui.small, label, x + barW + 6f, y + 2f, value > 0.05f ? 0xFFE8D27A : value < -0.05f ? 0xFFE08A7A : Ui.TEXT_DIM);
        if (ui.hovered(x, top, FAITH_WIDTH, HEIGHT)) {
            hint(ui, String.format(Locale.ROOT, "Víru dávají věřící · morálka %+.2f · laskavé činy %d, kruté %d",
                    value, faith.kindActs(), faith.cruelActs()), x, top);
        }
    }

    /** A one-line tooltip above the bar, kept on screen. */
    private static void hint(Ui ui, String text, float x, float top) {
        float w = ui.small.width(text) + 16f;
        float h = ui.small.lineHeight() + 10f;
        float tx = Math.clamp(x, 4f, Math.max(4f, ui.width() - w - 4f));
        float ty = top - h - 6f;
        ui.draw().rect(tx, ty, w, h, 0xF5181B20);
        ui.draw().outline(tx, ty, w, h, 1f, 0xFF60656F);
        ui.text(ui.small, text, tx + 8f, ty + 5f, Ui.TEXT);
    }

    private static float stat(Ui ui, String label, String value, int valueColor, float x, float y) {
        float lw = ui.text(ui.regular, label, x, y, Ui.TEXT_DIM);
        float vw = ui.text(ui.bold, value, x + lw + 6f, y, valueColor);
        return lw + 6f + vw + 14f;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Cuts {@code text} with an ellipsis to fit {@code width}. */
    private static String fit(Font font, String text, float width) {
        if (font.width(text) <= width) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && font.width(cut + "…") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.stripTrailing() + "…";
    }

    /** A card: coloured stripe, name and a second line; returns whether it was clicked. */
    private static boolean card(Ui ui, float x, float y, float w, boolean selected, int accent, String name,
                                String detail, boolean affordable) {
        boolean hover = ui.hovered(x, y, w, CARD_HEIGHT);
        ui.draw().rect(x, y, w, CARD_HEIGHT, selected ? Ui.BUTTON_ACTIVE : hover ? Ui.BUTTON_HOVER : Ui.BUTTON);
        ui.draw().outline(x, y, w, CARD_HEIGHT, selected ? 2f : 1f, selected ? 0xFFF2C75C : Ui.PANEL_BORDER);
        ui.draw().rect(x, y, 4f, CARD_HEIGHT, accent);
        float inner = w - 16f;
        Font font = ui.bold.width(name) <= inner ? ui.bold : ui.small;
        ui.text(font, fit(font, name, inner), x + 10f, y + (font == ui.bold ? 5f : 7f), affordable ? Ui.TEXT : Ui.TEXT_DIM);
        ui.text(ui.small, fit(ui.small, detail, inner), x + 10f, y + 24f, affordable ? Ui.TEXT_ACCENT : 0xFFE08A7A);
        return ui.clicked(x, y, w, CARD_HEIGHT);
    }

    /** The god's powers; returns the tooltip of the hovered card to draw last, or null. */
    private Runnable powers(Ui ui, World world, Layout layout, float top) {
        GodPowers powers = world.godPowers();
        DivinePower[] all = DivinePower.values();
        float y = top + (HEIGHT - CARD_HEIGHT) / 2f;
        Runnable tooltip = null;
        for (int i = 0; i < all.length; i++) {
            DivinePower power = all[i];
            GodConfig.Power p = powers.config().of(power);
            float x = layout.cardX(i);
            if (card(ui, x, y, layout.cardWidth(), power == armed, accent(power), p.name(),
                    String.format(Locale.ROOT, "%.0f Víry", p.cost()), powers.canAfford(power))) {
                armed = armed == power ? null : power;
                armedPlan = null;
            }
            if (ui.hovered(x, y, layout.cardWidth(), CARD_HEIGHT)) {
                tooltip = () -> tooltip(ui, powers, power, x, top);
            }
        }
        return tooltip;
    }

    /** The god's building plans (phase 9h); returns the tooltip of the hovered card, or null. */
    private Runnable plans(Ui ui, World world, List<Tribe.BuildingType> types, Layout layout, float top) {
        float y = top + (HEIGHT - CARD_HEIGHT) / 2f;
        Runnable tooltip = null;
        for (int i = 0; i < types.size(); i++) {
            Tribe.BuildingType type = types.get(i);
            float x = layout.cardX(i);
            boolean affordable = world.godPowers().faith().canAfford(type.planFaith());
            if (card(ui, x, y, layout.cardWidth(), type.id().equals(armedPlan), buildingAccent(type.id()), type.name(),
                    String.format(Locale.ROOT, "plán %.0f Víry", type.planFaith()), affordable)) {
                armedPlan = type.id().equals(armedPlan) ? null : type.id();
                armed = null;
            }
            if (ui.hovered(x, y, layout.cardWidth(), CARD_HEIGHT)) {
                tooltip = () -> planTooltip(ui, type, x, top);
            }
        }
        return tooltip;
    }

    private static void planTooltip(Ui ui, Tribe.BuildingType type, float buttonX, float top) {
        float padding = 10f;
        List<String> description = Ui.wrap(ui.regular, type.description(), TOOLTIP_WIDTH - 2 * padding);
        StringBuilder cost = new StringBuilder("Kmen zaplatí:");
        type.cost().forEach((material, amount) -> cost.append(String.format(Locale.ROOT, " %s %.0f", Texts.material(material), amount)));
        String plan = String.format(Locale.ROOT, "Plán stojí %.0f Víry, kmen ho postaví přednostně", type.planFaith());
        float h = padding + ui.bold.lineHeight() + 2f + description.size() * ui.regular.lineHeight() + 6f
                + 2 * ui.small.lineHeight() + padding;
        float x = Math.clamp(buttonX, 4f, ui.width() - TOOLTIP_WIDTH - 4f);
        float y = top - h - 8f;
        ui.draw().rect(x, y, TOOLTIP_WIDTH, h, 0xF5181B20);
        ui.draw().outline(x, y, TOOLTIP_WIDTH, h, 1f, 0xFF60656F);
        float ty = y + padding;
        ui.text(ui.bold, type.name(), x + padding, ty, Ui.TEXT);
        ty += ui.bold.lineHeight() + 2f;
        for (String line : description) {
            ui.text(ui.regular, line, x + padding, ty, 0xFFD5D9DF);
            ty += ui.regular.lineHeight();
        }
        ty += 6f;
        ui.text(ui.small, cost.toString(), x + padding, ty, 0xFFB7C7DA);
        ty += ui.small.lineHeight();
        ui.text(ui.small, plan, x + padding, ty, Ui.TEXT_ACCENT);
    }

    private static void tooltip(Ui ui, GodPowers powers, DivinePower power, float buttonX, float top) {
        GodConfig.Power p = powers.config().of(power);
        float padding = 10f;
        float inner = TOOLTIP_WIDTH - 2 * padding;
        List<String> description = Ui.wrap(ui.regular, p.description(), inner);
        String area = String.format(Locale.ROOT, "Cena %.0f Víry · poloměr %.0f polí", p.cost(), p.radius());
        String morality = p.alignment() > 0 ? "Laskavý čin (posouvá k dobru)"
                : p.alignment() < 0 ? "Krutý čin (posouvá ke zlu)" : "Neutrální čin";
        float missing = p.cost() - powers.faith().points();
        String afford = missing > 0 ? String.format(Locale.ROOT, "Chybí %.0f Víry", missing) : null;

        float h = padding + ui.bold.lineHeight() + 2f + description.size() * ui.regular.lineHeight() + 6f
                + 2 * ui.small.lineHeight() + (afford != null ? ui.small.lineHeight() : 0f) + padding;
        float x = Math.clamp(buttonX, 4f, ui.width() - TOOLTIP_WIDTH - 4f);
        float y = top - h - 8f;
        ui.draw().rect(x, y, TOOLTIP_WIDTH, h, 0xF5181B20);
        ui.draw().outline(x, y, TOOLTIP_WIDTH, h, 1f, 0xFF60656F);
        float ty = y + padding;
        ui.text(ui.bold, p.name(), x + padding, ty, Ui.TEXT);
        ty += ui.bold.lineHeight() + 2f;
        for (String line : description) {
            ui.text(ui.regular, line, x + padding, ty, 0xFFD5D9DF);
            ty += ui.regular.lineHeight();
        }
        ty += 6f;
        ui.text(ui.small, area, x + padding, ty, 0xFFB7C7DA);
        ty += ui.small.lineHeight();
        ui.text(ui.small, morality, x + padding, ty, p.alignment() > 0 ? 0xFF7FD68A : p.alignment() < 0 ? 0xFFE08A7A : Ui.TEXT_DIM);
        ty += ui.small.lineHeight();
        if (afford != null) {
            ui.text(ui.small, afford, x + padding, ty, 0xFFE08A7A);
        }
    }

    /**
     * Uses the selected power where the mouse points (call when the mouse is over the world, not the UI).
     *
     * @param ground ground point under the cursor, or null
     * @return the ring to draw for the selected power, or null
     */
    public GodEffectsRenderer.Brush handleWorld(Input input, World world, Vector3f ground, float frameSeconds) {
        if (armedPlan != null) {
            return handlePlan(input, world, ground);
        }
        if (armed == null) {
            return null;
        }
        if (input.isButtonPressed(GLFW_MOUSE_BUTTON_RIGHT)) {
            armed = null;
            return null;
        }
        if (ground == null) {
            return null;
        }
        GodPowers powers = world.godPowers();
        if (input.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            cast(powers, ground);
            repeatTimer = repeatSeconds(powers);
        } else if (armed.repeats() && input.isButtonDown(GLFW_MOUSE_BUTTON_LEFT)) {
            repeatTimer -= frameSeconds;
            if (repeatTimer <= 0f) {
                cast(powers, ground);
                repeatTimer += repeatSeconds(powers);
            }
        }
        return GodEffectsRenderer.brush(armed, ground.x, ground.z, powers.config().of(armed).radius(), powers.canAfford(armed));
    }

    private GodEffectsRenderer.Brush handlePlan(Input input, World world, Vector3f ground) {
        if (input.isButtonPressed(GLFW_MOUSE_BUTTON_RIGHT) || world.tribeGroup() == null) {
            armedPlan = null;
            return null;
        }
        if (ground == null) {
            return null;
        }
        Tribe.BuildingType type = world.tribe().building(armedPlan);
        boolean free = world.tribeSystem().free(ground.x, ground.z);
        if (input.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            if (!free) {
                show("Tady stavět nejde (voda nebo jiná stavba)");
            } else if (world.godPowers().request(new GodPowers.PlanCommand(type.id(), ground.x, ground.z, type.planFaith()))) {
                armedPlan = null;
            } else {
                show(String.format(Locale.ROOT, "Nedostatek Víry – plán stojí %.0f", type.planFaith()));
            }
        }
        return new GodEffectsRenderer.Brush(ground.x, ground.z, 2f, free ? 1.4f : 1.6f, free ? 1.2f : 0.5f, free ? 0.6f : 0.4f);
    }

    private float repeatSeconds(GodPowers powers) {
        return switch (armed) {
            case RAISE -> powers.config().raise().repeatSeconds();
            case LOWER -> powers.config().lower().repeatSeconds();
            default -> Float.MAX_VALUE;
        };
    }

    private void cast(GodPowers powers, Vector3f ground) {
        if (!powers.request(armed, ground.x, ground.z)) {
            float missing = powers.config().of(armed).cost() - powers.faith().points();
            show(String.format(Locale.ROOT, "Nedostatek Víry – chybí %.0f", Math.max(1f, missing)));
        }
    }

    private static int accent(DivinePower power) {
        return switch (power) {
            case RAIN -> 0xFF5B8FD6;
            case ABUNDANCE -> 0xFF6FBF4A;
            case RAISE, LOWER -> 0xFFC4A064;
            case LIGHTNING -> 0xFFF2D65C;
            case SANCTIFY -> 0xFFE8D27A;
        };
    }

    private static int buildingAccent(String id) {
        return switch (id) {
            case "fire" -> 0xFFE0883A;
            case "shelter" -> 0xFFA07850;
            case "storage" -> 0xFF8C6A44;
            case "shrine" -> 0xFFE8D27A;
            default -> 0xFF9AA0A8;
        };
    }
}

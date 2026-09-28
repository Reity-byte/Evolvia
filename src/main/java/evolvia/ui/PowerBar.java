package evolvia.ui;

import evolvia.core.Input;
import evolvia.god.DivinePower;
import evolvia.god.GodConfig;
import evolvia.god.GodPowers;
import evolvia.render.GodEffectsRenderer;
import evolvia.world.Tribe;
import evolvia.world.World;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * God powers bar at the bottom of the screen (DESIGN.md §9): one button per power with its faith cost
 * and a tooltip. A selected power shows its area on the ground; left click uses it (terrain powers
 * repeat while the button is held), right click or ESC puts it away. Once there is a tribe (phase 9h) a second
 * row offers the god's building plans: the selected building is placed near the camp for faith.
 */
public final class PowerBar {

    private static final float BUTTON_WIDTH = 112f;
    private static final float BUTTON_HEIGHT = 44f;
    private static final float GAP = 6f;
    private static final float MARGIN = 10f;
    private static final float TOOLTIP_WIDTH = 330f;
    private static final long MESSAGE_NANOS = 2_500_000_000L;

    private DivinePower armed;
    /** Building type of an armed plan, or null. */
    private String armedPlan;
    private float repeatTimer;
    private String message = "";
    private long messageTime;

    /** The selected power, or null. */
    public DivinePower armed() {
        return armed;
    }

    public void disarm() {
        armed = null;
        armedPlan = null;
    }

    /** A power or a building plan is selected. */
    public boolean isArmed() {
        return armed != null || armedPlan != null;
    }

    /** Lays out, draws and handles clicks of the bar (call during input handling). */
    public void build(Ui ui, World world) {
        GodPowers powers = world.godPowers();
        DivinePower[] all = DivinePower.values();
        float width = all.length * BUTTON_WIDTH + (all.length - 1) * GAP + 2 * GAP;
        float x0 = (ui.width() - width) / 2f;
        float y0 = ui.height() - MARGIN - BUTTON_HEIGHT - 2 * GAP;
        ui.panel(x0, y0, width, BUTTON_HEIGHT + 2 * GAP);

        DivinePower hovered = null;
        float hoveredX = 0f;
        for (int i = 0; i < all.length; i++) {
            DivinePower power = all[i];
            GodConfig.Power p = powers.config().of(power);
            float x = x0 + GAP + i * (BUTTON_WIDTH + GAP);
            float y = y0 + GAP;
            boolean affordable = powers.canAfford(power);
            boolean hover = ui.hovered(x, y, BUTTON_WIDTH, BUTTON_HEIGHT);
            int fill = power == armed ? Ui.BUTTON_ACTIVE : hover ? Ui.BUTTON_HOVER : Ui.BUTTON;
            ui.draw().rect(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, fill);
            ui.draw().outline(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, power == armed ? 2f : 1f,
                    power == armed ? 0xFFF2C75C : Ui.PANEL_BORDER);
            ui.draw().rect(x, y, 4f, BUTTON_HEIGHT, accent(power));
            ui.text(ui.bold, p.name(), x + 12f, y + 5f, affordable ? Ui.TEXT : Ui.TEXT_DIM);
            ui.text(ui.small, String.format(Locale.ROOT, "%.0f Víry", p.cost()), x + 12f, y + 24f,
                    affordable ? Ui.TEXT_ACCENT : 0xFFE08A7A);
            if (hover) {
                hovered = power;
                hoveredX = x;
            }
            if (ui.clicked(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)) {
                armed = armed == power ? null : power;
                armedPlan = null;
            }
        }
        if (world.tribeGroup() != null) {
            buildPlans(ui, world, y0);
        }
        if (!message.isEmpty() && System.nanoTime() - messageTime < MESSAGE_NANOS) {
            float mw = ui.bold.width(message);
            float mx = (ui.width() - mw) / 2f;
            float my = y0 - 30f;
            ui.draw().rect(mx - 10f, my - 4f, mw + 20f, ui.bold.lineHeight() + 8f, 0xE0181B20);
            ui.text(ui.bold, message, mx, my, 0xFFE08A7A);
        } else if (armedPlan != null && hovered == null) {
            String hint = world.tribe().building(armedPlan).name() + ": klikni poblíž tábora kmene, pravé tlačítko zruší";
            float hw = ui.small.width(hint);
            ui.text(ui.small, hint, (ui.width() - hw) / 2f, y0 - PLAN_HEIGHT - 3 * GAP - 22f, Ui.TEXT);
        } else if (armed != null && hovered == null) {
            String hint = powers.config().of(armed).name() + ": klikni do krajiny"
                    + (armed.repeats() ? " (podrž pro opakování)" : "") + ", pravé tlačítko zruší";
            float hw = ui.small.width(hint);
            ui.text(ui.small, hint, (ui.width() - hw) / 2f, y0 - 22f, Ui.TEXT);
        }
        if (hovered != null) {
            tooltip(ui, powers, hovered, hoveredX, y0);
        }
    }

    private static final float PLAN_HEIGHT = 30f;

    /** A row of building plans above the powers (phase 9h). */
    private void buildPlans(Ui ui, World world, float barTop) {
        List<Tribe.BuildingType> types = world.tribe().buildings();
        float width = types.size() * BUTTON_WIDTH + (types.size() - 1) * GAP + 2 * GAP;
        float x0 = (ui.width() - width) / 2f;
        float y0 = barTop - PLAN_HEIGHT - 3 * GAP;
        ui.panel(x0, y0, width, PLAN_HEIGHT + 2 * GAP);
        for (int i = 0; i < types.size(); i++) {
            Tribe.BuildingType type = types.get(i);
            float x = x0 + GAP + i * (BUTTON_WIDTH + GAP);
            float y = y0 + GAP;
            boolean affordable = world.godPowers().faith().canAfford(type.planFaith());
            if (ui.button(String.format(Locale.ROOT, "%s %.0f", type.name(), type.planFaith()), x, y, BUTTON_WIDTH, PLAN_HEIGHT,
                    type.id().equals(armedPlan))) {
                armedPlan = type.id().equals(armedPlan) ? null : type.id();
                armed = null;
            }
            if (!affordable) {
                ui.draw().rect(x, y, BUTTON_WIDTH, PLAN_HEIGHT, 0x60101010);
            }
            if (ui.hovered(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)) {
                planTooltip(ui, world, type, x, y0);
            }
        }
    }

    private static void planTooltip(Ui ui, World world, Tribe.BuildingType type, float buttonX, float top) {
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

    private static void tooltip(Ui ui, GodPowers powers, DivinePower power, float buttonX, float barTop) {
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
        float y = barTop - h - 8f;
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
                message = "Tady stavět nejde (voda nebo jiná stavba)";
                messageTime = System.nanoTime();
            } else if (world.godPowers().request(new GodPowers.PlanCommand(type.id(), ground.x, ground.z, type.planFaith()))) {
                armedPlan = null;
            } else {
                message = String.format(Locale.ROOT, "Nedostatek Víry – plán stojí %.0f", type.planFaith());
                messageTime = System.nanoTime();
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
            message = String.format(Locale.ROOT, "Nedostatek Víry – chybí %.0f", Math.max(1f, missing));
            messageTime = System.nanoTime();
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
}

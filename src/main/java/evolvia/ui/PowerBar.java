package evolvia.ui;

import evolvia.core.Input;
import evolvia.god.DivinePower;
import evolvia.god.GodConfig;
import evolvia.god.GodPowers;
import evolvia.render.GodEffectsRenderer;
import evolvia.world.World;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * God powers bar at the bottom of the screen (DESIGN.md §9): one button per power with its faith cost
 * and a tooltip. A selected power shows its area on the ground; left click uses it (terrain powers
 * repeat while the button is held), right click or ESC puts it away.
 */
public final class PowerBar {

    private static final float BUTTON_WIDTH = 112f;
    private static final float BUTTON_HEIGHT = 44f;
    private static final float GAP = 6f;
    private static final float MARGIN = 10f;
    private static final float TOOLTIP_WIDTH = 330f;
    private static final long MESSAGE_NANOS = 2_500_000_000L;

    private DivinePower armed;
    private float repeatTimer;
    private String message = "";
    private long messageTime;

    /** The selected power, or null. */
    public DivinePower armed() {
        return armed;
    }

    public void disarm() {
        armed = null;
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
            }
        }
        if (!message.isEmpty() && System.nanoTime() - messageTime < MESSAGE_NANOS) {
            float mw = ui.bold.width(message);
            float mx = (ui.width() - mw) / 2f;
            float my = y0 - 30f;
            ui.draw().rect(mx - 10f, my - 4f, mw + 20f, ui.bold.lineHeight() + 8f, 0xE0181B20);
            ui.text(ui.bold, message, mx, my, 0xFFE08A7A);
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

package evolvia.ui;

import evolvia.core.Input;
import evolvia.core.Window;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.glfwGetWindowContentScale;

/**
 * Small immediate-mode UI toolkit: panels, buttons, text, bars. The UI is laid out and its clicks are
 * handled once per frame during input handling ({@link #beginFrame}), the geometry is drawn later by
 * {@link #render}.
 * <p>
 * Coordinates are UI units: screen points scaled by the OS display scale (so the UI has the same
 * physical size on HiDPI / Retina screens and with Windows scaling). Panels drawn this frame block
 * the mouse for the 3D world ({@link #wantsMouse()}).
 */
public final class Ui implements AutoCloseable {

    public static final int TEXT = 0xFFEDEFF2;
    public static final int TEXT_DIM = 0xFF9AA0A8;
    public static final int TEXT_ACCENT = 0xFFF2C75C;
    public static final int PANEL = 0xE01B1E24;
    public static final int PANEL_BORDER = 0xFF3A3F4A;
    public static final int BUTTON = 0xFF2C313B;
    public static final int BUTTON_HOVER = 0xFF3A414D;
    public static final int BUTTON_ACTIVE = 0xFF4E6A8C;

    private final FontAtlas atlas = new FontAtlas();
    private final UiRenderer renderer;
    public final Font regular;
    public final Font bold;
    public final Font small;
    public final Font title;

    private final List<float[]> blockers = new ArrayList<>();
    private float scale = 1f;
    private float width;
    private float height;
    private float mouseX;
    private float mouseY;
    private boolean clickPending;
    private boolean clickUsed;

    public Ui() {
        regular = atlas.add("fonts/DroidSans.ttf", 15f);
        bold = atlas.add("fonts/DroidSans-Bold.ttf", 15f);
        small = atlas.add("fonts/DroidSans.ttf", 13f);
        title = atlas.add("fonts/DroidSans-Bold.ttf", 19f);
        atlas.ensureScale(1f); // metrics are needed before the first frame
        renderer = new UiRenderer(atlas);
    }

    /** Starts a UI frame: reads the display scale, the screen size and the mouse, clears the geometry. */
    public void beginFrame(Input input, Window window) {
        float contentScale = contentScale(window);
        float framebufferPerWindow = window.width() > 0 ? window.framebufferWidth() / (float) window.width() : 1f;
        // Framebuffer pixels per UI unit: Retina already has 2 framebuffer pixels per window point,
        // Windows scaling only shows up as the content scale.
        scale = Math.max(1f, Math.max(contentScale, framebufferPerWindow));
        width = window.framebufferWidth() / scale;
        height = window.framebufferHeight() / scale;
        mouseX = (float) input.mouseX() * framebufferPerWindow / scale;
        mouseY = (float) input.mouseY() * framebufferPerWindow / scale;
        clickPending = input.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT);
        clickUsed = false;
        blockers.clear();
        renderer.clear();
    }

    private static float contentScale(Window window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer x = stack.mallocFloat(1);
            FloatBuffer y = stack.mallocFloat(1);
            glfwGetWindowContentScale(window.handle(), x, y);
            return x.get(0) > 0 ? x.get(0) : 1f;
        }
    }

    /** Draws the UI laid out this frame (needs the OpenGL context). */
    public void render(int framebufferWidth, int framebufferHeight) {
        atlas.ensureScale(scale);
        renderer.render(framebufferWidth, framebufferHeight, scale);
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public float mouseX() {
        return mouseX;
    }

    public float mouseY() {
        return mouseY;
    }

    /** Framebuffer pixels per UI unit. */
    public float scale() {
        return scale;
    }

    public UiRenderer draw() {
        return renderer;
    }

    /** True if the mouse is over a panel drawn this frame (the world should ignore it). */
    public boolean wantsMouse() {
        for (float[] r : blockers) {
            if (inside(r[0], r[1], r[2], r[3])) {
                return true;
            }
        }
        return false;
    }

    /** Marks an area as UI, so clicks and scrolling there do not reach the world. */
    public void block(float x, float y, float w, float h) {
        blockers.add(new float[]{x, y, w, h});
    }

    public boolean hovered(float x, float y, float w, float h) {
        return inside(x, y, w, h);
    }

    /** True once per click inside the area (the click is then used up). */
    public boolean clicked(float x, float y, float w, float h) {
        if (clickPending && !clickUsed && inside(x, y, w, h)) {
            clickUsed = true;
            return true;
        }
        return false;
    }

    /** Uses up a click anywhere (e.g. a click on a modal background). */
    public boolean clickedAnywhere() {
        if (clickPending && !clickUsed) {
            clickUsed = true;
            return true;
        }
        return false;
    }

    private boolean inside(float x, float y, float w, float h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Panel background with a border; blocks the mouse. */
    public void panel(float x, float y, float w, float h) {
        renderer.rect(x, y, w, h, PANEL);
        renderer.outline(x, y, w, h, 1f, PANEL_BORDER);
        block(x, y, w, h);
    }

    /**
     * Button with a centred label.
     *
     * @param active drawn highlighted (e.g. the current game speed)
     * @return true when clicked this frame
     */
    public boolean button(String label, float x, float y, float w, float h, boolean active) {
        boolean hover = hovered(x, y, w, h);
        renderer.rect(x, y, w, h, active ? BUTTON_ACTIVE : hover ? BUTTON_HOVER : BUTTON);
        renderer.outline(x, y, w, h, 1f, active ? 0xFF7FA3CC : PANEL_BORDER);
        float textWidth = regular.width(label);
        renderer.text(regular, label, x + (w - textWidth) / 2f, y + (h - regular.lineHeight()) / 2f, TEXT);
        block(x, y, w, h);
        return clicked(x, y, w, h);
    }

    /** Width of a button that fits its label. */
    public float buttonWidth(String label) {
        return regular.width(label) + 20f;
    }

    public float text(Font font, String text, float x, float y, int argb) {
        return renderer.text(font, text, x, y, argb);
    }

    /** Horizontal bar filled to {@code fraction} (0..1). */
    public void bar(float x, float y, float w, float h, float fraction, int argb) {
        renderer.rect(x, y, w, h, 0xFF30343C);
        renderer.rect(x, y, w * Math.clamp(fraction, 0f, 1f), h, argb);
    }

    /**
     * Splits text into lines no wider than {@code maxWidth} (at spaces; explicit newlines are kept).
     */
    public static List<String> wrap(Font font, String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && font.width(candidate) > maxWidth) {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                } else {
                    line.setLength(0);
                    line.append(candidate);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    @Override
    public void close() {
        renderer.close();
        atlas.close();
    }
}

package evolvia.ui;

import evolvia.render.Shader;
import org.joml.Matrix4f;
import org.lwjgl.stb.STBEasyFont;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * Debug text overlay (toggled with F3) drawn with stb_easy_font, which needs no font file.
 * Proper font rendering via stb_truetype comes with the UI in phase 6.
 */
public final class DebugOverlay implements AutoCloseable {

    private static final int MAX_QUADS = 4096;
    /** stb_easy_font vertex: float x, y, z + 4 unsigned bytes RGBA. */
    private static final int VERTEX_BYTES = 16;
    private static final int QUAD_BYTES = 4 * VERTEX_BYTES;
    private static final float SCALE = 2f;
    private static final float MARGIN = 6f;
    /** Line height of stb_easy_font in overlay units. */
    private static final float LINE_HEIGHT = 12f;

    private final Shader shader;
    private final int vao;
    private final int vbo;
    private final int ebo;
    private final ByteBuffer vertices;
    private final ByteBuffer shadowColor;
    private final Matrix4f projection = new Matrix4f();

    private boolean visible;

    public DebugOverlay() {
        shader = Shader.fromResources("shaders/text");
        vertices = memAlloc(MAX_QUADS * QUAD_BYTES);
        shadowColor = memAlloc(4).put(0, (byte) 0).put(1, (byte) 0).put(2, (byte) 0).put(3, (byte) 200);

        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_QUADS * QUAD_BYTES, GL_STREAM_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, VERTEX_BYTES, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, VERTEX_BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);

        // stb_easy_font emits quads; core profile has no GL_QUADS, so index them as two triangles each.
        int[] indices = new int[MAX_QUADS * 6];
        for (int q = 0, i = 0; q < MAX_QUADS; q++) {
            int base = q * 4;
            indices[i++] = base;
            indices[i++] = base + 1;
            indices[i++] = base + 2;
            indices[i++] = base;
            indices[i++] = base + 2;
            indices[i++] = base + 3;
        }
        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glBindVertexArray(0);
    }

    public boolean isVisible() {
        return visible;
    }

    public void toggle() {
        visible = !visible;
    }

    /** Draws multi-line text in the top-left corner. Does nothing when hidden. */
    public void render(String text, int framebufferWidth, int framebufferHeight) {
        if (!visible) {
            return;
        }
        draw(text, MARGIN, MARGIN, framebufferWidth, framebufferHeight);
    }

    /**
     * Draws text centred horizontally above a point on the screen (e.g. over a creature's head),
     * independent of the F3 toggle.
     *
     * @param screenX framebuffer pixels from the left
     * @param screenY framebuffer pixels from the top (bottom edge of the text)
     */
    public void renderLabel(String text, float screenX, float screenY, int framebufferWidth, int framebufferHeight) {
        float width = 0;
        int lines = 1;
        for (String line : text.split("\n", -1)) {
            width = Math.max(width, STBEasyFont.stb_easy_font_width(line));
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        float x = screenX / SCALE - width / 2f;
        float y = screenY / SCALE - lines * LINE_HEIGHT;
        draw(text, x, y, framebufferWidth, framebufferHeight);
    }

    /**
     * Draws text with its top-left corner at a screen position, independent of the F3 toggle.
     *
     * @param screenX framebuffer pixels from the left
     * @param screenY framebuffer pixels from the top
     */
    public void renderText(String text, float screenX, float screenY, int framebufferWidth, int framebufferHeight) {
        draw(text, screenX / SCALE, screenY / SCALE, framebufferWidth, framebufferHeight);
    }

    /** Draws text at overlay coordinates (framebuffer pixels / SCALE) with a drop shadow. */
    private void draw(String text, float x, float y, int framebufferWidth, int framebufferHeight) {
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            return;
        }
        vertices.clear();
        int quads = STBEasyFont.stb_easy_font_print(x + 1, y + 1, text, shadowColor, vertices);
        vertices.position(quads * QUAD_BYTES);
        quads += STBEasyFont.stb_easy_font_print(x, y, text, null, vertices);
        vertices.position(0).limit(quads * QUAD_BYTES);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertices);

        projection.setOrtho(0, framebufferWidth / SCALE, framebufferHeight / SCALE, 0, -1, 1);
        shader.bind();
        shader.setUniform("uProjection", projection);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, quads * 6, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);

        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        shader.close();
        memFree(vertices);
        memFree(shadowColor);
    }
}

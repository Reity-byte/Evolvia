package evolvia.ui;

import evolvia.render.Shader;
import org.joml.Matrix4f;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memRealloc;

/**
 * Batches 2D UI geometry (solid rectangles, lines, text from the {@link FontAtlas}) into one buffer
 * and draws it in a single call, in the order it was added. Coordinates are UI units with the origin
 * at the top left; colors are 0xAARRGGBB. Adding geometry needs no OpenGL (the UI can be laid out
 * while handling input); only {@link #render} does.
 */
public final class UiRenderer implements AutoCloseable {

    /** Vertex: x, y, u, v, r, g, b, a. u < 0 marks a solid (untextured) vertex. */
    private static final int FLOATS = 8;

    private final Shader shader;
    private final FontAtlas atlas;
    private final int vao;
    private final int vbo;
    private final Matrix4f projection = new Matrix4f();
    private FloatBuffer data;
    private int capacity = 16384;
    private int vertexCount;

    public UiRenderer(FontAtlas atlas) {
        this.atlas = atlas;
        shader = Shader.fromResources("shaders/ui");
        data = memAllocFloat(capacity * FLOATS);
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * FLOATS * Float.BYTES, GL_STREAM_DRAW);
        int stride = FLOATS * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 4L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glBindVertexArray(0);
    }

    /** Discards everything added so far (start of a UI frame). */
    public void clear() {
        vertexCount = 0;
    }

    public void rect(float x, float y, float w, float h, int argb) {
        quad(x, y, x + w, y + h, -1, -1, -1, -1, argb);
    }

    /** Rectangle border of the given thickness, drawn inside the rectangle. */
    public void outline(float x, float y, float w, float h, float thickness, int argb) {
        rect(x, y, w, thickness, argb);
        rect(x, y + h - thickness, w, thickness, argb);
        rect(x, y + thickness, thickness, h - 2 * thickness, argb);
        rect(x + w - thickness, y + thickness, thickness, h - 2 * thickness, argb);
    }

    /** Straight line as a thin quad. */
    public void line(float x1, float y1, float x2, float y2, float thickness, int argb) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1e-4f) {
            return;
        }
        float nx = -dy / length * thickness / 2f;
        float ny = dx / length * thickness / 2f;
        vertex(x1 + nx, y1 + ny, -1, -1, argb);
        vertex(x2 + nx, y2 + ny, -1, -1, argb);
        vertex(x2 - nx, y2 - ny, -1, -1, argb);
        vertex(x1 + nx, y1 + ny, -1, -1, argb);
        vertex(x2 - nx, y2 - ny, -1, -1, argb);
        vertex(x1 - nx, y1 - ny, -1, -1, argb);
    }

    /**
     * Draws one line of text with its top edge at {@code y}.
     *
     * @return the text width
     */
    public float text(Font font, String text, float x, float y, int argb) {
        float penX = x;
        float baseline = y + font.ascent();
        for (int i = 0; i < text.length(); i++) {
            Font.Glyph g = font.glyph(text.charAt(i));
            if (g.x1() > g.x0()) {
                quad(penX + g.x0(), baseline + g.y0(), penX + g.x1(), baseline + g.y1(), g.u0(), g.v0(), g.u1(), g.v1(), argb);
            }
            penX += g.advance();
        }
        return penX - x;
    }

    private void quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
        vertex(x0, y0, u0, v0, argb);
        vertex(x1, y0, u1, v0, argb);
        vertex(x1, y1, u1, v1, argb);
        vertex(x0, y0, u0, v0, argb);
        vertex(x1, y1, u1, v1, argb);
        vertex(x0, y1, u0, v1, argb);
    }

    private void vertex(float x, float y, float u, float v, int argb) {
        if (vertexCount == capacity) {
            capacity *= 2;
            data = memRealloc(data, capacity * FLOATS);
        }
        int offset = vertexCount * FLOATS;
        data.put(offset, x);
        data.put(offset + 1, y);
        data.put(offset + 2, u);
        data.put(offset + 3, v);
        data.put(offset + 4, ((argb >> 16) & 0xFF) / 255f);
        data.put(offset + 5, ((argb >> 8) & 0xFF) / 255f);
        data.put(offset + 6, (argb & 0xFF) / 255f);
        data.put(offset + 7, ((argb >>> 24) & 0xFF) / 255f);
        vertexCount++;
    }

    /**
     * Draws everything added since {@link #clear()} over the scene.
     *
     * @param pixelScale framebuffer pixels per UI unit
     */
    public void render(int framebufferWidth, int framebufferHeight, float pixelScale) {
        if (vertexCount == 0 || framebufferWidth <= 0 || framebufferHeight <= 0) {
            return;
        }
        data.position(0).limit(vertexCount * FLOATS);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * FLOATS * Float.BYTES, GL_STREAM_DRAW); // orphan
        glBufferSubData(GL_ARRAY_BUFFER, 0, data);
        data.clear();

        projection.setOrtho(0, framebufferWidth / pixelScale, framebufferHeight / pixelScale, 0, -1, 1);
        shader.bind();
        shader.setUniform("uProjection", projection);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlas.texture());

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        shader.close();
        memFree(data);
    }
}

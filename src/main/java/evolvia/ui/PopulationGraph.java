package evolvia.ui;

import evolvia.render.Shader;
import evolvia.world.PopulationHistory;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.util.Locale;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * Debug graph of population (orange) and food (green) over time, bottom-left of the screen.
 * Uses the same 2D shader and vertex layout as the text overlay (position + RGBA bytes).
 */
public final class PopulationGraph implements AutoCloseable {

    /** Graph size and margin in framebuffer pixels. */
    private static final float WIDTH = 520f;
    private static final float HEIGHT = 170f;
    private static final float MARGIN = 12f;
    private static final float TEXT_HEIGHT = 30f;
    /** Vertex: float x, y, z + 4 bytes RGBA (same as stb_easy_font). */
    private static final int VERTEX_BYTES = 16;
    private static final int MAX_VERTICES = 6 + 2 * PopulationHistory.CAPACITY;

    private final Shader shader;
    private final int vao;
    private final int vbo;
    private final ByteBuffer vertices;
    private final Matrix4f projection = new Matrix4f();

    public PopulationGraph() {
        shader = Shader.fromResources("shaders/text");
        vertices = memAlloc(MAX_VERTICES * VERTEX_BYTES);
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_VERTICES * VERTEX_BYTES, GL_STREAM_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, VERTEX_BYTES, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, VERTEX_BYTES, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindVertexArray(0);
    }

    public void render(PopulationHistory history, DebugOverlay overlay, int framebufferWidth, int framebufferHeight) {
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            return;
        }
        float x0 = MARGIN;
        float y1 = framebufferHeight - MARGIN;          // bottom of the plot
        float y0 = y1 - HEIGHT;                          // top of the plot
        float top = y0 - TEXT_HEIGHT;                    // top of the panel (text row)

        vertices.clear();
        // Background panel (two triangles)
        quad(x0 - 6, top - 4, x0 + WIDTH + 6, y1 + 6, 0, 0, 0, 140);
        int samples = history.size();
        float maxPopulation = Math.max(10, history.maxPopulation() * 1.1f);
        float maxFood = Math.max(10, history.maxFood() * 1.1f);
        int populationStart = 6;
        for (int i = 0; i < samples; i++) {
            vertex(xAt(x0, i, samples), y1 - HEIGHT * history.population(i) / maxPopulation, 240, 150, 70, 255);
        }
        int foodStart = populationStart + samples;
        for (int i = 0; i < samples; i++) {
            vertex(xAt(x0, i, samples), y1 - HEIGHT * history.food(i) / maxFood, 110, 200, 90, 255);
        }
        vertices.flip();

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertices);
        projection.setOrtho(0, framebufferWidth, framebufferHeight, 0, -1, 1);
        shader.bind();
        shader.setUniform("uProjection", projection);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        if (samples >= 2) {
            glDrawArrays(GL_LINE_STRIP, populationStart, samples);
            glDrawArrays(GL_LINE_STRIP, foodStart, samples);
        }
        glBindVertexArray(0);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);

        int minutes = samples * PopulationHistory.SAMPLE_INTERVAL_TICKS / 20 / 60;
        String label = samples == 0 ? "Population" : String.format(Locale.ROOT,
                "Population %d (peak %d)   Food %.0f   last %d min",
                history.population(samples - 1), history.maxPopulation(), history.food(samples - 1), minutes);
        overlay.renderText(label, x0, top + 4, framebufferWidth, framebufferHeight);
    }

    private static float xAt(float x0, int index, int samples) {
        return samples <= 1 ? x0 : x0 + WIDTH * index / (samples - 1);
    }

    private void quad(float xa, float ya, float xb, float yb, int r, int g, int b, int a) {
        vertex(xa, ya, r, g, b, a);
        vertex(xb, ya, r, g, b, a);
        vertex(xb, yb, r, g, b, a);
        vertex(xa, ya, r, g, b, a);
        vertex(xb, yb, r, g, b, a);
        vertex(xa, yb, r, g, b, a);
    }

    private void vertex(float x, float y, int r, int g, int b, int a) {
        vertices.putFloat(x).putFloat(y).putFloat(0f)
                .put((byte) r).put((byte) g).put((byte) b).put((byte) a);
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        shader.close();
        memFree(vertices);
    }
}

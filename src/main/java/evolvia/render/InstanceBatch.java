package evolvia.render;

import org.joml.Matrix4fc;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memRealloc;

/**
 * Instanced drawing of one mesh: collects per-instance model matrix, color and walk animation
 * each frame, then uploads them and draws all instances in a single call. Instance attributes use
 * the locations right after the mesh's own ({@code mat4} = 4 locations, then {@code vec3} color,
 * then {@code vec2} walk phase + amplitude), matching {@code shaders/instanced.vert} and
 * {@code shaders/creature.vert} (a shader may ignore the walk attribute).
 */
public final class InstanceBatch implements AutoCloseable {

    /** Per instance: mat4 (16 floats) + color (3 floats) + walk (2 floats). */
    private static final int INSTANCE_FLOATS = 21;

    private final Mesh mesh;
    private final int instanceVbo;
    private FloatBuffer data;
    private int capacity = 1024;
    private int count;

    /** Takes ownership of {@code mesh}. */
    public InstanceBatch(Mesh mesh) {
        this.mesh = mesh;
        data = memAllocFloat(capacity * INSTANCE_FLOATS);
        instanceVbo = glGenBuffers();

        mesh.bind();
        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * INSTANCE_FLOATS * Float.BYTES, GL_STREAM_DRAW);
        int stride = INSTANCE_FLOATS * Float.BYTES;
        int location = mesh.attributeCount();
        for (int column = 0; column < 4; column++) {
            glVertexAttribPointer(location + column, 4, GL_FLOAT, false, stride, (long) column * 4 * Float.BYTES);
            glEnableVertexAttribArray(location + column);
            glVertexAttribDivisor(location + column, 1);
        }
        glVertexAttribPointer(location + 4, 3, GL_FLOAT, false, stride, 16L * Float.BYTES);
        glEnableVertexAttribArray(location + 4);
        glVertexAttribDivisor(location + 4, 1);
        glVertexAttribPointer(location + 5, 2, GL_FLOAT, false, stride, 19L * Float.BYTES);
        glEnableVertexAttribArray(location + 5);
        glVertexAttribDivisor(location + 5, 1);
        glBindVertexArray(0);
    }

    /** Starts collecting instances for this frame. */
    public void begin() {
        count = 0;
    }

    public void add(Matrix4fc model, float red, float green, float blue) {
        add(model, red, green, blue, 0f, 0f);
    }

    /**
     * @param walkPhase     leg swing phase in radians
     * @param walkAmplitude leg swing amplitude in radians (0 = standing)
     */
    public void add(Matrix4fc model, float red, float green, float blue, float walkPhase, float walkAmplitude) {
        if (count == capacity) {
            capacity *= 2;
            data = memRealloc(data, capacity * INSTANCE_FLOATS);
        }
        int offset = count * INSTANCE_FLOATS;
        model.get(offset, data);
        data.put(offset + 16, red);
        data.put(offset + 17, green);
        data.put(offset + 18, blue);
        data.put(offset + 19, walkPhase);
        data.put(offset + 20, walkAmplitude);
        count++;
    }

    /** Uploads the collected instances and draws them (the shader must already be bound). */
    public void draw() {
        if (count == 0) {
            return;
        }
        data.position(0).limit(count * INSTANCE_FLOATS);
        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * INSTANCE_FLOATS * Float.BYTES, GL_STREAM_DRAW); // orphan
        glBufferSubData(GL_ARRAY_BUFFER, 0, data);
        data.clear();
        mesh.drawInstanced(count);
    }

    @Override
    public void close() {
        glDeleteBuffers(instanceVbo);
        mesh.close();
        memFree(data);
    }
}

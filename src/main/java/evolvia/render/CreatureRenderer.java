package evolvia.render;

import evolvia.components.PrevTransform;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.evolution.SpeciesDefinition;
import org.joml.Matrix4f;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memRealloc;

/**
 * Draws all creatures in one instanced draw call. The mesh is shared; each instance gets its
 * model matrix (position and heading interpolated between the last two ticks) and a color.
 * <p>
 * Phase 2 placeholder shape: a box body with a smaller box head in front (+Z), so the heading
 * is visible. Procedural bodies come in phase 6. Only reads simulation data.
 */
public final class CreatureRenderer implements AutoCloseable {

    /** Per instance: mat4 (16 floats) + color (3 floats). */
    private static final int INSTANCE_FLOATS = 19;
    /** Brightness variation between individuals (visual only). */
    private static final float COLOR_JITTER = 0.12f;
    private static final float PI = (float) Math.PI;

    private final Shader shader;
    private final Mesh mesh;
    private final int instanceVbo;
    private final float bodySize;
    private final float red;
    private final float green;
    private final float blue;
    private final Matrix4f model = new Matrix4f();

    private FloatBuffer instanceData;
    private int capacity;

    public CreatureRenderer(SpeciesDefinition species) {
        shader = Shader.fromResources("shaders/creature.vert", "shaders/terrain.frag");
        mesh = buildPlaceholderMesh();
        bodySize = species.bodySize();
        red = ((species.rgb() >> 16) & 0xFF) / 255f;
        green = ((species.rgb() >> 8) & 0xFF) / 255f;
        blue = (species.rgb() & 0xFF) / 255f;

        capacity = 1024;
        instanceData = memAllocFloat(capacity * INSTANCE_FLOATS);
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
        glBindVertexArray(0);
    }

    /**
     * @param alpha interpolation factor between the previous (0) and the current (1) tick
     */
    public void render(Camera camera, Lighting lighting, EcsWorld ecs, float alpha) {
        ComponentStore<Transform> transforms = ecs.store(Transform.class);
        ComponentStore<PrevTransform> previous = ecs.store(PrevTransform.class);
        int count = transforms.size();
        if (count == 0) {
            return;
        }
        ensureCapacity(count);

        for (int i = 0; i < count; i++) {
            int entity = transforms.entityAt(i);
            Transform current = transforms.componentAt(i);
            PrevTransform prev = previous.get(entity);
            float x = current.position.x;
            float y = current.position.y;
            float z = current.position.z;
            float yaw = current.yaw;
            if (prev != null) {
                x = prev.position.x + (x - prev.position.x) * alpha;
                y = prev.position.y + (y - prev.position.y) * alpha;
                z = prev.position.z + (z - prev.position.z) * alpha;
                yaw = lerpAngle(prev.yaw, yaw, alpha);
            }
            model.translation(x, y, z).rotateY(yaw).scale(bodySize);
            int offset = i * INSTANCE_FLOATS;
            model.get(offset, instanceData);
            float brightness = 1f + COLOR_JITTER * hashToSigned(entity);
            instanceData.put(offset + 16, Math.min(red * brightness, 1f));
            instanceData.put(offset + 17, Math.min(green * brightness, 1f));
            instanceData.put(offset + 18, Math.min(blue * brightness, 1f));
        }
        instanceData.position(0).limit(count * INSTANCE_FLOATS);

        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * INSTANCE_FLOATS * Float.BYTES, GL_STREAM_DRAW); // orphan
        glBufferSubData(GL_ARRAY_BUFFER, 0, instanceData);
        instanceData.clear();

        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        lighting.apply(shader, camera);
        mesh.drawInstanced(count);
    }

    private void ensureCapacity(int count) {
        if (count <= capacity) {
            return;
        }
        while (capacity < count) {
            capacity *= 2;
        }
        instanceData = memRealloc(instanceData, capacity * INSTANCE_FLOATS);
    }

    /** Interpolates angles along the shorter way around the circle. */
    private static float lerpAngle(float from, float to, float t) {
        float delta = to - from;
        while (delta > PI) {
            delta -= 2 * PI;
        }
        while (delta < -PI) {
            delta += 2 * PI;
        }
        return from + delta * t;
    }

    /** Deterministic pseudo-random value in [-1, 1] per entity (visual variation only). */
    private static float hashToSigned(int entity) {
        int h = entity * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h & 0xFFFF) / 32767.5f - 1f;
    }

    /** Box body + box head at +Z. Unit scale ~1 tile long; layout: position (3), normal (3), shade (1). */
    private static Mesh buildPlaceholderMesh() {
        float[] vertices = new float[2 * 24 * 7];
        int[] indices = new int[2 * 36];
        int[] cursor = {0, 0, 0}; // vertex float index, index index, vertex count
        addBox(vertices, indices, cursor, 0f, 0.26f, -0.08f, 0.46f, 0.42f, 0.72f, 1.0f);   // body
        addBox(vertices, indices, cursor, 0f, 0.44f, 0.38f, 0.30f, 0.30f, 0.30f, 0.78f);   // head
        return new Mesh(vertices, indices, 3, 3, 1);
    }

    /** Axis-aligned box with outward normals and counter-clockwise faces. */
    private static void addBox(float[] vertices, int[] indices, int[] cursor,
                               float cx, float cy, float cz, float sx, float sy, float sz, float shade) {
        float[][] normals = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        float[][][] corners = {
                {{0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}, {0.5f, 0.5f, 0.5f}},
                {{-0.5f, -0.5f, -0.5f}, {-0.5f, -0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, -0.5f}},
                {{-0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, -0.5f}, {-0.5f, 0.5f, -0.5f}},
                {{-0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, 0.5f}, {-0.5f, -0.5f, 0.5f}},
                {{-0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f}},
                {{0.5f, -0.5f, -0.5f}, {-0.5f, -0.5f, -0.5f}, {-0.5f, 0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}},
        };
        for (int face = 0; face < 6; face++) {
            int base = cursor[2];
            for (float[] corner : corners[face]) {
                int v = cursor[0];
                vertices[v] = cx + corner[0] * sx;
                vertices[v + 1] = cy + corner[1] * sy;
                vertices[v + 2] = cz + corner[2] * sz;
                vertices[v + 3] = normals[face][0];
                vertices[v + 4] = normals[face][1];
                vertices[v + 5] = normals[face][2];
                vertices[v + 6] = shade;
                cursor[0] += 7;
                cursor[2]++;
            }
            int i = cursor[1];
            indices[i] = base;
            indices[i + 1] = base + 1;
            indices[i + 2] = base + 2;
            indices[i + 3] = base;
            indices[i + 4] = base + 2;
            indices[i + 5] = base + 3;
            cursor[1] += 6;
        }
    }

    @Override
    public void close() {
        glDeleteBuffers(instanceVbo);
        mesh.close();
        shader.close();
        memFree(instanceData);
    }
}

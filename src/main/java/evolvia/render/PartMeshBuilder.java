package evolvia.render;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * Builds a low-poly mesh from transformed boxes, each with its own color and optional leg swing.
 * Vertex layout ({@code shaders/creature.vert}): position (3), normal (3), color (3),
 * swing (3: pivot height, pivot z, direction; direction 0 = rigid part). Swinging parts rotate
 * around the X axis through the pivot, driven by the per-instance walk phase.
 */
public final class PartMeshBuilder {

    public static final int[] ATTRIBUTES = {3, 3, 3, 3};
    private static final int FLOATS = 12;

    private static final float[][] NORMALS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    /** Unit cube corners per face, counter-clockwise seen from outside. */
    private static final float[][][] CORNERS = {
            {{0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}, {0.5f, 0.5f, 0.5f}},
            {{-0.5f, -0.5f, -0.5f}, {-0.5f, -0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, -0.5f}},
            {{-0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, -0.5f}, {-0.5f, 0.5f, -0.5f}},
            {{-0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, 0.5f}, {-0.5f, -0.5f, 0.5f}},
            {{-0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f}},
            {{0.5f, -0.5f, -0.5f}, {-0.5f, -0.5f, -0.5f}, {-0.5f, 0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}},
    };

    private final Matrix4f transform = new Matrix4f();
    private final Matrix3f normalMatrix = new Matrix3f();
    private final Vector3f point = new Vector3f();
    private final Vector3f normal = new Vector3f();

    private float[] vertices = new float[24 * FLOATS * 8];
    private int[] indices = new int[36 * 8];
    private int floatCount;
    private int indexCount;
    private int vertexCount;
    private int boxCount;

    private float red = 1f;
    private float green = 1f;
    private float blue = 1f;
    private float pivotY;
    private float pivotZ;
    private float swing;

    /** Color for the following boxes. */
    public PartMeshBuilder color(float r, float g, float b) {
        red = r;
        green = g;
        blue = b;
        return this;
    }

    public PartMeshBuilder color(float[] rgb) {
        return color(rgb[0], rgb[1], rgb[2]);
    }

    /**
     * Following boxes swing around the X axis through (y = pivotY, z = pivotZ) while walking.
     *
     * @param direction +1 or -1 (legs on opposite diagonals swing in opposite directions)
     */
    public PartMeshBuilder swing(float pivotY, float pivotZ, float direction) {
        this.pivotY = pivotY;
        this.pivotZ = pivotZ;
        this.swing = direction;
        return this;
    }

    /** Following boxes do not move relative to the body. */
    public PartMeshBuilder rigid() {
        return swing(0f, 0f, 0f);
    }

    /** Axis-aligned box centred at (cx, cy, cz) with edge lengths (sx, sy, sz). */
    public PartMeshBuilder box(float cx, float cy, float cz, float sx, float sy, float sz) {
        return box(new Matrix4f().translation(cx, cy, cz).scale(sx, sy, sz));
    }

    /** Box centred at (cx, cy, cz), tilted around the X axis by {@code pitch} radians (positive = front down). */
    public PartMeshBuilder tiltedBox(float cx, float cy, float cz, float sx, float sy, float sz, float pitch) {
        return box(new Matrix4f().translation(cx, cy, cz).rotateX(pitch).scale(sx, sy, sz));
    }

    /** The unit cube (-0.5..0.5) transformed by {@code boxTransform}. */
    public PartMeshBuilder box(Matrix4fc boxTransform) {
        ensureCapacity();
        transform.set(boxTransform);
        transform.normal(normalMatrix);
        for (int face = 0; face < 6; face++) {
            int base = vertexCount;
            normalMatrix.transform(NORMALS[face][0], NORMALS[face][1], NORMALS[face][2], normal).normalize();
            for (float[] corner : CORNERS[face]) {
                transform.transformPosition(corner[0], corner[1], corner[2], point);
                vertices[floatCount++] = point.x;
                vertices[floatCount++] = point.y;
                vertices[floatCount++] = point.z;
                vertices[floatCount++] = normal.x;
                vertices[floatCount++] = normal.y;
                vertices[floatCount++] = normal.z;
                vertices[floatCount++] = red;
                vertices[floatCount++] = green;
                vertices[floatCount++] = blue;
                vertices[floatCount++] = pivotY;
                vertices[floatCount++] = pivotZ;
                vertices[floatCount++] = swing;
                vertexCount++;
            }
            indices[indexCount++] = base;
            indices[indexCount++] = base + 1;
            indices[indexCount++] = base + 2;
            indices[indexCount++] = base;
            indices[indexCount++] = base + 2;
            indices[indexCount++] = base + 3;
        }
        boxCount++;
        return this;
    }

    /** Number of boxes added so far. */
    public int boxCount() {
        return boxCount;
    }

    private void ensureCapacity() {
        if (floatCount + 24 * FLOATS > vertices.length) {
            vertices = Arrays.copyOf(vertices, vertices.length * 2);
            indices = Arrays.copyOf(indices, indices.length * 2);
        }
    }

    public MeshData build() {
        return new MeshData(Arrays.copyOf(vertices, floatCount), Arrays.copyOf(indices, indexCount), ATTRIBUTES.clone());
    }
}

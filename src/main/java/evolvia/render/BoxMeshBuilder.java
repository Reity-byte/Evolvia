package evolvia.render;

import java.util.Arrays;

/**
 * Builds a mesh from axis-aligned boxes (resources: bushes, carcasses). Creatures use {@link PartMeshBuilder}.
 * Vertex layout: position (3), normal (3), shade (1) - the shade multiplies the instance color.
 */
public final class BoxMeshBuilder {

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

    private float[] vertices = new float[24 * 7 * 2];
    private int[] indices = new int[36 * 2];
    private int floatCount;
    private int indexCount;
    private int vertexCount;

    /**
     * Adds a box centred at (cx, cy, cz) with edge lengths (sx, sy, sz).
     *
     * @param shade brightness multiplier for this box
     */
    public BoxMeshBuilder box(float cx, float cy, float cz, float sx, float sy, float sz, float shade) {
        if (floatCount + 24 * 7 > vertices.length) {
            vertices = Arrays.copyOf(vertices, vertices.length * 2);
            indices = Arrays.copyOf(indices, indices.length * 2);
        }
        for (int face = 0; face < 6; face++) {
            int base = vertexCount;
            for (float[] corner : CORNERS[face]) {
                vertices[floatCount++] = cx + corner[0] * sx;
                vertices[floatCount++] = cy + corner[1] * sy;
                vertices[floatCount++] = cz + corner[2] * sz;
                vertices[floatCount++] = NORMALS[face][0];
                vertices[floatCount++] = NORMALS[face][1];
                vertices[floatCount++] = NORMALS[face][2];
                vertices[floatCount++] = shade;
                vertexCount++;
            }
            indices[indexCount++] = base;
            indices[indexCount++] = base + 1;
            indices[indexCount++] = base + 2;
            indices[indexCount++] = base;
            indices[indexCount++] = base + 2;
            indices[indexCount++] = base + 3;
        }
        return this;
    }

    public Mesh build() {
        return new Mesh(Arrays.copyOf(vertices, floatCount), Arrays.copyOf(indices, indexCount), 3, 3, 1);
    }
}

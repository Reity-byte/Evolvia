package evolvia.render;

/**
 * Factory for simple procedural meshes.
 */
public final class Primitives {

    private Primitives() {
    }

    /**
     * Unit cube centered at the origin (side 1) with a distinct flat color per face.
     * Vertex layout: position (3), color (3). Counter-clockwise front faces.
     */
    public static Mesh coloredCube() {
        float[][] faceColors = {
                {0.90f, 0.30f, 0.25f}, // +X red
                {0.25f, 0.70f, 0.35f}, // -X green
                {0.95f, 0.85f, 0.30f}, // +Y yellow
                {0.30f, 0.45f, 0.90f}, // -Y blue
                {0.95f, 0.55f, 0.20f}, // +Z orange
                {0.65f, 0.35f, 0.85f}, // -Z purple
        };
        // Four corners per face, counter-clockwise when viewed from outside.
        float[][][] faceCorners = {
                {{0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}, {0.5f, 0.5f, 0.5f}},
                {{-0.5f, -0.5f, -0.5f}, {-0.5f, -0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, -0.5f}},
                {{-0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, -0.5f}, {-0.5f, 0.5f, -0.5f}},
                {{-0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, 0.5f}, {-0.5f, -0.5f, 0.5f}},
                {{-0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f}},
                {{0.5f, -0.5f, -0.5f}, {-0.5f, -0.5f, -0.5f}, {-0.5f, 0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}},
        };

        float[] vertices = new float[6 * 4 * 6];
        int[] indices = new int[6 * 6];
        int v = 0;
        int i = 0;
        for (int face = 0; face < 6; face++) {
            for (float[] corner : faceCorners[face]) {
                vertices[v++] = corner[0];
                vertices[v++] = corner[1];
                vertices[v++] = corner[2];
                vertices[v++] = faceColors[face][0];
                vertices[v++] = faceColors[face][1];
                vertices[v++] = faceColors[face][2];
            }
            int base = face * 4;
            indices[i++] = base;
            indices[i++] = base + 1;
            indices[i++] = base + 2;
            indices[i++] = base;
            indices[i++] = base + 2;
            indices[i++] = base + 3;
        }
        return new Mesh(vertices, indices, 3, 3);
    }
}

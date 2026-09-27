package evolvia.render;

/**
 * Mesh geometry on the CPU (no OpenGL), so mesh builders can be unit tested.
 * Turn it into a GPU {@link Mesh} with {@link #toMesh()}.
 *
 * @param vertices       interleaved vertex data
 * @param indices        triangle indices
 * @param attributeSizes floats per vertex attribute, in location order
 */
public record MeshData(float[] vertices, int[] indices, int[] attributeSizes) {

    public int floatsPerVertex() {
        int sum = 0;
        for (int size : attributeSizes) {
            sum += size;
        }
        return sum;
    }

    public int vertexCount() {
        return vertices.length / floatsPerVertex();
    }

    /** Uploads the data to the GPU (needs a current OpenGL context). */
    public Mesh toMesh() {
        return new Mesh(vertices, indices, attributeSizes);
    }
}

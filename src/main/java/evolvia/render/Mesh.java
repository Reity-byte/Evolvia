package evolvia.render;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Static indexed triangle mesh with interleaved float vertex attributes (VAO + VBO + EBO).
 */
public final class Mesh implements AutoCloseable {

    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexCount;

    /**
     * @param vertices       interleaved vertex data
     * @param indices        triangle indices
     * @param attributeSizes number of floats of each attribute, in location order (e.g. 3, 3 = position, color)
     */
    public Mesh(float[] vertices, int[] indices, int... attributeSizes) {
        int floatsPerVertex = 0;
        for (int size : attributeSizes) {
            floatsPerVertex += size;
        }
        if (vertices.length % floatsPerVertex != 0) {
            throw new IllegalArgumentException("Vertex data length " + vertices.length
                    + " is not a multiple of " + floatsPerVertex + " floats per vertex");
        }

        indexCount = indices.length;
        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        int stride = floatsPerVertex * Float.BYTES;
        int offset = 0;
        for (int location = 0; location < attributeSizes.length; location++) {
            glVertexAttribPointer(location, attributeSizes[location], GL_FLOAT, false, stride, offset);
            glEnableVertexAttribArray(location);
            offset += attributeSizes[location] * Float.BYTES;
        }

        glBindVertexArray(0);
    }

    public void draw() {
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
    }

    @Override
    public void close() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
    }
}

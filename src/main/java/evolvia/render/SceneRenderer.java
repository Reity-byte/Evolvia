package evolvia.render;

import org.joml.Matrix4fc;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Renders 3D meshes with the basic flat-color shader.
 */
public final class SceneRenderer implements AutoCloseable {

    private final Shader shader;

    public SceneRenderer() {
        shader = Shader.fromResources("shaders/basic");
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glClearColor(0.53f, 0.72f, 0.88f, 1f);
    }

    /** Human-readable description of the active OpenGL context. */
    public static String describeContext() {
        return "OpenGL " + glGetString(GL_VERSION) + " | " + glGetString(GL_RENDERER);
    }

    /** Clears the framebuffer and sets up the camera for this frame. */
    public void beginFrame(int framebufferWidth, int framebufferHeight, Camera camera) {
        glViewport(0, 0, framebufferWidth, framebufferHeight);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
    }

    public void draw(Mesh mesh, Matrix4fc model) {
        shader.setUniform("uModel", model);
        mesh.draw();
    }

    @Override
    public void close() {
        shader.close();
    }
}

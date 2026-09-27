package evolvia.render;

import evolvia.world.Terrain;
import evolvia.world.WorldConfig;
import org.joml.Vector3fc;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Renders the world: terrain first (opaque), then the transparent water.
 */
public final class SceneRenderer implements AutoCloseable {

    private final Lighting lighting = new Lighting();
    private final TerrainRenderer terrainRenderer;
    private final WaterRenderer waterRenderer;

    public SceneRenderer(Terrain terrain, WorldConfig.WaterSettings water) {
        terrainRenderer = new TerrainRenderer(terrain);
        waterRenderer = new WaterRenderer(terrain, water);

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        Vector3fc sky = lighting.skyColor();
        glClearColor(sky.x(), sky.y(), sky.z(), 1f);
    }

    /** Human-readable description of the active OpenGL context. */
    public static String describeContext() {
        return "OpenGL " + glGetString(GL_VERSION) + " | " + glGetString(GL_RENDERER);
    }

    public void render(Camera camera, int framebufferWidth, int framebufferHeight) {
        glViewport(0, 0, framebufferWidth, framebufferHeight);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        terrainRenderer.render(camera, lighting);
        waterRenderer.render(camera, lighting);
    }

    @Override
    public void close() {
        terrainRenderer.close();
        waterRenderer.close();
    }
}

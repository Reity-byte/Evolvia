package evolvia.render;

import evolvia.world.World;
import evolvia.world.WorldConfig;
import org.joml.Vector3fc;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Renders the world: opaque terrain and creatures first, then the transparent water.
 * Reads the simulation state, never changes it.
 */
public final class SceneRenderer implements AutoCloseable {

    private final Lighting lighting = new Lighting();
    private final World world;
    private final TerrainRenderer terrainRenderer;
    private final CreatureRenderer creatureRenderer;
    private final WaterRenderer waterRenderer;

    public SceneRenderer(World world, WorldConfig.WaterSettings water) {
        this.world = world;
        terrainRenderer = new TerrainRenderer(world.terrain());
        creatureRenderer = new CreatureRenderer(world.species());
        waterRenderer = new WaterRenderer(world.terrain(), water);

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

    /**
     * @param alpha interpolation factor between the previous and the current tick
     */
    public void render(Camera camera, int framebufferWidth, int framebufferHeight, float alpha) {
        glViewport(0, 0, framebufferWidth, framebufferHeight);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        terrainRenderer.render(camera, lighting);
        creatureRenderer.render(camera, lighting, world.ecs(), alpha);
        waterRenderer.render(camera, lighting);
    }

    @Override
    public void close() {
        terrainRenderer.close();
        creatureRenderer.close();
        waterRenderer.close();
    }
}

package evolvia.render;

import evolvia.world.World;
import evolvia.world.WorldConfig;
import org.joml.Vector3fc;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Renders the world: opaque terrain, resources and creatures first, then the transparent water.
 * Reads the simulation state, never changes it.
 */
public final class SceneRenderer implements AutoCloseable {

    private final Lighting lighting = new Lighting();
    private final World world;
    private final TerrainRenderer terrainRenderer;
    private final ResourceRenderer resourceRenderer;
    private final CreatureRenderer creatureRenderer;
    private final WaterRenderer waterRenderer;
    private final GodEffectsRenderer effectsRenderer;
    private final GroupOverlayRenderer groupRenderer;
    private boolean showGroups;
    private int selectedGroup;

    public SceneRenderer(World world, WorldConfig.WaterSettings water) {
        this.world = world;
        terrainRenderer = new TerrainRenderer(world.terrain());
        resourceRenderer = new ResourceRenderer();
        creatureRenderer = new CreatureRenderer();
        waterRenderer = new WaterRenderer(world.terrain(), water);
        effectsRenderer = new GodEffectsRenderer();
        groupRenderer = new GroupOverlayRenderer();

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        Vector3fc sky = lighting.skyColor();
        glClearColor(sky.x(), sky.y(), sky.z(), 1f);
    }

    /** Herd whose territory is shown (the selected creature's), or 0. */
    public void setSelectedGroup(int group) {
        selectedGroup = group;
    }

    /** Shows or hides the herd view (colored rings, leader flags). */
    public void setShowGroups(boolean show) {
        showGroups = show;
    }

    /** Human-readable description of the active OpenGL context. */
    public static String describeContext() {
        return "OpenGL " + glGetString(GL_VERSION) + " | " + glGetString(GL_RENDERER);
    }

    /**
     * @param alpha      interpolation factor between the previous and the current tick
     * @param simSeconds simulation time in seconds (drives animations, stops when paused)
     * @param selected   creature to highlight, or -1
     * @param brush      where the selected god power would hit, or null
     */
    public void render(Camera camera, int framebufferWidth, int framebufferHeight, float alpha, double simSeconds, int selected,
                       GodEffectsRenderer.Brush brush) {
        glViewport(0, 0, framebufferWidth, framebufferHeight);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        terrainRenderer.render(camera, lighting);
        resourceRenderer.render(camera, lighting, world.ecs());
        creatureRenderer.render(camera, lighting, world.ecs(), alpha, simSeconds, selected);
        effectsRenderer.render(camera, lighting, world.terrain(), world.godPowers(), simSeconds, brush);
        groupRenderer.render(camera, lighting, world.terrain(), world.ecs(), world.groups(), alpha, showGroups, selectedGroup,
                world.species().stats().combat().territoryRadius());
        waterRenderer.render(camera, lighting);
        effectsRenderer.renderTranslucent(camera, lighting);
    }

    @Override
    public void close() {
        terrainRenderer.close();
        resourceRenderer.close();
        creatureRenderer.close();
        waterRenderer.close();
        effectsRenderer.close();
        groupRenderer.close();
    }
}

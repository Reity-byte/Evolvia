package evolvia.render;

import evolvia.data.Colors;
import evolvia.world.Terrain;
import evolvia.world.WorldConfig;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Water as one flat, semi-transparent plane at sea level. It extends far past the map border
 * (fading into the fog) so the map looks like an island in an open sea.
 */
public final class WaterRenderer implements AutoCloseable {

    /** How far the sea plane extends beyond the map border, in world units. */
    public static final float OCEAN_MARGIN = 2000f;

    private final Shader shader;
    private final Mesh plane;
    private final float red;
    private final float green;
    private final float blue;
    private final float alpha;

    public WaterRenderer(Terrain terrain, WorldConfig.WaterSettings settings) {
        shader = Shader.fromResources("shaders/water");
        int rgb = Colors.parseHex(settings.color(), "water.color");
        red = ((rgb >> 16) & 0xFF) / 255f;
        green = ((rgb >> 8) & 0xFF) / 255f;
        blue = (rgb & 0xFF) / 255f;
        alpha = settings.alpha();

        float x0 = -OCEAN_MARGIN;
        float z0 = -OCEAN_MARGIN;
        float x1 = terrain.width() + OCEAN_MARGIN;
        float z1 = terrain.depth() + OCEAN_MARGIN;
        float y = terrain.seaLevel();
        float[] vertices = {
                x0, y, z0,
                x0, y, z1,
                x1, y, z1,
                x1, y, z0,
        };
        int[] indices = {0, 1, 2, 0, 2, 3};
        plane = new Mesh(vertices, indices, 3);
    }

    /** Must be drawn after all opaque geometry. */
    public void render(Camera camera, Lighting lighting, float floodLevel) {
        shader.bind();
        shader.setUniform("uLevel", floodLevel);
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        shader.setUniform("uWaterColor", red, green, blue, alpha);
        lighting.apply(shader, camera);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        plane.draw();
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    @Override
    public void close() {
        plane.close();
        shader.close();
    }
}

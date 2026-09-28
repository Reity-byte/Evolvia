package evolvia.render;

import evolvia.world.Groups;
import evolvia.world.Terrain;
import org.joml.Matrix4f;

/**
 * Draws the herds' camps (phase 9g): a stack of logs and a heap of stones that grow with the stock, and a
 * small ring of stones marking the place.
 */
public final class CampRenderer implements AutoCloseable {

    private static final int MAX_LOGS = 16;
    private static final int MAX_STONES = 14;
    private static final float PER_PIECE = 3f;

    private final Shader shader;
    private final InstanceBatch boxes;
    private final Matrix4f model = new Matrix4f();

    public CampRenderer() {
        shader = Shader.fromResources("shaders/instanced.vert", "shaders/terrain.frag");
        boxes = new InstanceBatch(new BoxMeshBuilder().box(0f, 0f, 0f, 1f, 1f, 1f, 1f).build());
    }

    public void render(Camera camera, Lighting lighting, Terrain terrain, Groups groups) {
        boxes.begin();
        for (Groups.Group group : groups.all()) {
            if (!group.hasCamp) {
                continue;
            }
            float cx = group.campX;
            float cz = group.campZ;
            for (int k = 0; k < 8; k++) { // the ring
                double angle = k * Math.PI / 4;
                float x = cx + (float) Math.sin(angle) * 2.6f;
                float z = cz + (float) Math.cos(angle) * 2.6f;
                model.translation(x, ground(terrain, x, z) + 0.08f, z).rotateY((float) angle).scale(0.3f, 0.16f, 0.24f);
                boxes.add(model, 0.55f, 0.54f, 0.52f);
            }
            int logs = Math.min(MAX_LOGS, (int) Math.ceil(group.stock("wood") / PER_PIECE));
            for (int n = 0; n < logs; n++) {
                int layer = n / 4;
                int slot = n % 4;
                float x = cx - 1.2f;
                float z = cz - 0.45f + slot * 0.3f - (layer % 2) * 0.15f;
                model.translation(x, ground(terrain, x, cz) + 0.13f + layer * 0.24f, z).scale(1.3f, 0.24f, 0.24f);
                boxes.add(model, 0.5f - (n % 3) * 0.03f, 0.35f, 0.21f);
            }
            int stones = Math.min(MAX_STONES, (int) Math.ceil(group.stock("stone") / PER_PIECE));
            for (int n = 0; n < stones; n++) {
                double angle = n * 2.4;
                float r = n < 6 ? 0.45f : 0.2f;
                float x = cx + 1.2f + (float) Math.sin(angle) * r;
                float z = cz + (float) Math.cos(angle) * r;
                model.translation(x, ground(terrain, x, z) + 0.14f + (n / 6) * 0.22f, z).rotateY((float) angle)
                        .scale(0.34f, 0.26f, 0.3f);
                boxes.add(model, 0.6f - (n % 3) * 0.04f, 0.59f, 0.57f);
            }
        }
        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        lighting.apply(shader, camera);
        boxes.draw();
    }

    private static float ground(Terrain terrain, float x, float z) {
        float cx = Math.clamp(x, 0f, terrain.width() - 0.01f);
        float cz = Math.clamp(z, 0f, terrain.depth() - 0.01f);
        return Math.max(terrain.heightAt(cx, cz), terrain.seaLevel());
    }

    @Override
    public void close() {
        boxes.close();
        shader.close();
    }
}

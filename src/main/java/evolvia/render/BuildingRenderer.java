package evolvia.render;

import evolvia.world.Settlement;
import evolvia.world.Terrain;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the tribe's buildings (phase 9h): a fire (stone ring, logs, flickering flames), a shelter (posts,
 * walls, a sloped roof), a store (a big wooden shed) and a shrine (a stone pillar with a golden top). A site
 * rises with its progress; a site still waiting for materials shows only its corner stakes (golden for the
 * god's plans).
 */
public final class BuildingRenderer implements AutoCloseable {

    private final Shader shader;
    private final Map<String, InstanceBatch> shapes = new HashMap<>();
    private final Map<String, float[]> colors = new HashMap<>();
    private final InstanceBatch boxes;
    private final Matrix4f model = new Matrix4f();

    public BuildingRenderer() {
        shader = Shader.fromResources("shaders/instanced.vert", "shaders/effects.frag");
        BoxMeshBuilder fire = new BoxMeshBuilder();
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            fire.box((float) Math.sin(a) * 0.9f, 0.12f, (float) Math.cos(a) * 0.9f, 0.34f, 0.24f, 0.3f, 0.75f);
        }
        fire.box(0f, 0.12f, 0f, 1.2f, 0.16f, 0.18f, 0.45f).box(0f, 0.2f, 0f, 0.18f, 0.16f, 1.2f, 0.4f);
        shapes.put("fire", new InstanceBatch(fire.build()));
        colors.put("fire", new float[]{0.62f, 0.6f, 0.58f});
        shapes.put("shelter", new InstanceBatch(new BoxMeshBuilder()
                .box(-1.4f, 0.7f, -1.2f, 0.18f, 1.4f, 0.18f, 0.6f).box(1.4f, 0.7f, -1.2f, 0.18f, 1.4f, 0.18f, 0.6f)
                .box(-1.4f, 0.7f, 1.2f, 0.18f, 1.4f, 0.18f, 0.6f).box(1.4f, 0.7f, 1.2f, 0.18f, 1.4f, 0.18f, 0.6f)
                .box(0f, 0.6f, -1.25f, 2.8f, 1.2f, 0.1f, 0.85f)       // back wall
                .box(-1.45f, 0.6f, 0f, 0.1f, 1.2f, 2.4f, 0.8f)        // side walls
                .box(1.45f, 0.6f, 0f, 0.1f, 1.2f, 2.4f, 0.8f)
                .box(0f, 1.55f, 0f, 3.3f, 0.2f, 3.0f, 1.0f)            // roof
                .box(0f, 1.8f, 0f, 2.4f, 0.3f, 2.2f, 1.05f)
                .build()));
        colors.put("shelter", new float[]{0.62f, 0.47f, 0.3f});
        shapes.put("storage", new InstanceBatch(new BoxMeshBuilder()
                .box(0f, 0.8f, 0f, 3.2f, 1.6f, 2.4f, 0.85f)
                .box(0f, 1.75f, 0f, 3.5f, 0.3f, 2.8f, 1.0f)
                .box(0f, 0.55f, 1.21f, 1.0f, 1.1f, 0.05f, 0.4f)        // door
                .build()));
        colors.put("storage", new float[]{0.55f, 0.4f, 0.26f});
        shapes.put("shrine", new InstanceBatch(new BoxMeshBuilder()
                .box(0f, 0.2f, 0f, 2.2f, 0.4f, 2.2f, 0.8f)            // plinth
                .box(0f, 1.6f, 0f, 0.6f, 2.4f, 0.6f, 1.0f)            // pillar
                .box(0f, 2.9f, 0f, 0.9f, 0.25f, 0.9f, 1.1f)
                .build()));
        colors.put("shrine", new float[]{0.7f, 0.69f, 0.66f});
        boxes = new InstanceBatch(new BoxMeshBuilder().box(0f, 0f, 0f, 1f, 1f, 1f, 1f).build());
    }

    public void render(Camera camera, Lighting lighting, Terrain terrain, Settlement settlement, double simSeconds) {
        shapes.values().forEach(InstanceBatch::begin);
        boxes.begin();
        for (Settlement.Building building : settlement.all()) {
            float y = ground(terrain, building.x, building.z);
            if (!building.paid) {
                stakes(terrain, building, building.planned ? new float[]{1.5f, 1.3f, 0.5f} : new float[]{0.6f, 0.45f, 0.3f});
                continue;
            }
            InstanceBatch batch = shapes.get(building.type.id());
            float[] color = colors.getOrDefault(building.type.id(), new float[]{0.6f, 0.6f, 0.6f});
            if (batch == null) {
                continue;
            }
            float rise = building.done() ? 1f : 0.15f + 0.85f * building.progress;
            float shade = building.done() ? 1f : 0.7f;
            model.translation(building.x, y - 0.05f, building.z).rotateY(building.id * 0.9f).scale(1f, rise, 1f);
            batch.add(model, color[0] * shade, color[1] * shade, color[2] * shade);
            if (!building.done()) {
                stakes(terrain, building, new float[]{0.6f, 0.45f, 0.3f});
            } else if ("fire".equals(building.type.id())) {
                flames(building, y, simSeconds);
            } else if ("shrine".equals(building.type.id())) {
                model.translation(building.x, y + 3.25f, building.z).rotateY((float) simSeconds).scale(0.45f);
                boxes.add(model, 1.9f, 1.6f, 0.6f); // golden top
            }
        }
        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        shader.setUniform("uAlpha", 1f);
        lighting.apply(shader, camera);
        shapes.values().forEach(InstanceBatch::draw);
        boxes.draw();
    }

    private void stakes(Terrain terrain, Settlement.Building building, float[] color) {
        for (int k = 0; k < 4; k++) {
            double a = Math.PI / 4 + k * Math.PI / 2;
            float x = building.x + (float) Math.sin(a) * 1.7f;
            float z = building.z + (float) Math.cos(a) * 1.7f;
            model.translation(x, ground(terrain, x, z) + 0.45f, z).scale(0.1f, 0.9f, 0.1f);
            boxes.add(model, color[0], color[1], color[2]);
        }
    }

    private void flames(Settlement.Building building, float y, double simSeconds) {
        for (int k = 0; k < 3; k++) {
            float flicker = 0.6f + 0.4f * (float) Math.abs(Math.sin(simSeconds * (6 + k) + building.id + k));
            float h = (k == 0 ? 0.9f : 0.55f) * flicker;
            model.translation(building.x + (k - 1) * 0.18f, y + 0.25f + h * 0.5f, building.z + (k % 2) * 0.12f)
                    .rotateY(k).scale(0.3f, h, 0.3f);
            boxes.add(model, 2.0f, k == 0 ? 0.8f : 1.4f, 0.25f);
        }
    }

    private static float ground(Terrain terrain, float x, float z) {
        float cx = Math.clamp(x, 0f, terrain.width() - 0.01f);
        float cz = Math.clamp(z, 0f, terrain.depth() - 0.01f);
        return Math.max(terrain.heightAt(cx, cz), terrain.seaLevel());
    }

    @Override
    public void close() {
        shapes.values().forEach(InstanceBatch::close);
        boxes.close();
        shader.close();
    }
}

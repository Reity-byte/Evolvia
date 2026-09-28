package evolvia.render;

import evolvia.world.Refuges;
import evolvia.world.Terrain;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the refuges (phase 9d): a rock mound with a dark mouth for caves, a cluster of trees for groves,
 * a slanted rock slab for overhangs. Sacred places get a ring of golden stones and a glowing pillar.
 */
public final class RefugeRenderer implements AutoCloseable {

    private final Shader shader;
    private final Map<String, InstanceBatch> shapes = new HashMap<>();
    private final InstanceBatch sacred;
    private final Matrix4f model = new Matrix4f();

    public RefugeRenderer() {
        shader = Shader.fromResources("shaders/instanced.vert", "shaders/effects.frag");
        shapes.put("cave", new InstanceBatch(new BoxMeshBuilder()
                .box(0f, 1.1f, 0f, 5.0f, 2.2f, 4.2f, 0.62f)      // rock mound
                .box(0.3f, 2.4f, -0.3f, 3.4f, 1.2f, 3.0f, 0.7f)
                .box(0f, 0.8f, 2.12f, 1.6f, 1.6f, 0.1f, 0.12f)   // dark mouth
                .build()));
        shapes.put("grove", new InstanceBatch(new BoxMeshBuilder()
                .box(-1.6f, 1.2f, -1.2f, 0.35f, 2.4f, 0.35f, 0.45f).box(-1.6f, 3.0f, -1.2f, 2.2f, 1.8f, 2.2f, 0.75f)
                .box(1.4f, 1.4f, -0.8f, 0.35f, 2.8f, 0.35f, 0.45f).box(1.4f, 3.4f, -0.8f, 2.4f, 2.0f, 2.4f, 0.7f)
                .box(0.2f, 1.1f, 1.6f, 0.35f, 2.2f, 0.35f, 0.45f).box(0.2f, 2.8f, 1.6f, 2.0f, 1.6f, 2.0f, 0.8f)
                .box(-0.4f, 1.5f, 0.1f, 0.4f, 3.0f, 0.4f, 0.45f).box(-0.4f, 3.7f, 0.1f, 2.6f, 2.2f, 2.6f, 0.72f)
                .build()));
        shapes.put("overhang", new InstanceBatch(new BoxMeshBuilder()
                .box(-1.2f, 0.9f, -0.8f, 1.2f, 1.8f, 3.0f, 0.62f)  // back wall
                .box(0.2f, 1.9f, 0f, 3.4f, 0.5f, 3.2f, 0.7f)       // roof slab
                .box(1.6f, 0.4f, 0.9f, 0.8f, 0.8f, 0.9f, 0.55f)
                .build()));
        sacred = new InstanceBatch(new BoxMeshBuilder().box(0f, 0f, 0f, 1f, 1f, 1f, 1f).build());
    }

    public void render(Camera camera, Lighting lighting, Terrain terrain, Refuges refuges) {
        shapes.values().forEach(InstanceBatch::begin);
        sacred.begin();
        for (Refuges.Refuge refuge : refuges.all()) {
            float y = Math.max(terrain.heightAt(refuge.x, refuge.z), terrain.seaLevel());
            InstanceBatch batch = shapes.get(refuge.type.id());
            if (batch != null) {
                model.translation(refuge.x, y - 0.1f, refuge.z).rotateY(refuge.id * 1.3f);
                float[] color = color(refuge.type.id());
                batch.add(model, color[0], color[1], color[2]);
            }
            if (refuge.sacred) {
                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4;
                    float px = refuge.x + (float) Math.sin(angle) * refuge.radius();
                    float pz = refuge.z + (float) Math.cos(angle) * refuge.radius();
                    float py = Math.max(terrain.heightAt(px, pz), terrain.seaLevel());
                    model.translation(px, py + 0.35f, pz).rotateY((float) angle).scale(0.45f, 0.7f, 0.35f);
                    sacred.add(model, 1.4f, 1.15f, 0.5f);
                }
                model.translation(refuge.x, y + 4.5f, refuge.z).scale(0.25f, 9f, 0.25f);
                sacred.add(model, 1.9f, 1.7f, 0.9f); // glowing pillar of light
            }
        }
        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        shader.setUniform("uAlpha", 1f);
        lighting.apply(shader, camera);
        shapes.values().forEach(InstanceBatch::draw);
        sacred.draw();
    }

    private static float[] color(String type) {
        return switch (type) {
            case "grove" -> new float[]{0.35f, 0.55f, 0.28f};
            case "cave" -> new float[]{0.62f, 0.6f, 0.58f};
            default -> new float[]{0.7f, 0.62f, 0.52f};
        };
    }

    @Override
    public void close() {
        shapes.values().forEach(InstanceBatch::close);
        sacred.close();
        shader.close();
    }
}

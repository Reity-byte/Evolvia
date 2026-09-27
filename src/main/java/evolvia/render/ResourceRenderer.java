package evolvia.render;

import evolvia.components.ResourceNode;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.world.ResourceDefinition;
import evolvia.world.ResourceKind;
import org.joml.Matrix4f;

/**
 * Draws food nodes (berry bushes) instanced. The color blends from the "empty" to the "full"
 * color by how much food is left, so depleted areas are visible. Water nodes are not drawn
 * (the water surface already shows them).
 */
public final class ResourceRenderer implements AutoCloseable {

    private final Shader shader;
    private final InstanceBatch batch;
    private final Matrix4f model = new Matrix4f();

    public ResourceRenderer() {
        shader = Shader.fromResources("shaders/creature.vert", "shaders/terrain.frag");
        batch = new InstanceBatch(new BoxMeshBuilder()
                .box(0f, 0.22f, 0f, 0.9f, 0.44f, 0.9f, 1.0f)      // lower bush
                .box(0.05f, 0.55f, -0.05f, 0.6f, 0.3f, 0.6f, 1.1f) // top
                .build());
    }

    public void render(Camera camera, Lighting lighting, EcsWorld ecs) {
        ComponentStore<ResourceNode> nodes = ecs.store(ResourceNode.class);
        ComponentStore<Transform> transforms = ecs.store(Transform.class);

        batch.begin();
        for (int i = 0; i < nodes.size(); i++) {
            ResourceNode node = nodes.componentAt(i);
            ResourceDefinition type = node.type;
            if (type.kind() != ResourceKind.FOOD) {
                continue;
            }
            Transform t = transforms.get(nodes.entityAt(i));
            float fill = type.capacity() > 0 ? Math.clamp(node.amount / type.capacity(), 0f, 1f) : 0f;
            // Varied rotation per node so bushes do not all look aligned (visual only).
            model.translation(t.position.x, t.position.y, t.position.z)
                    .rotateY(nodes.entityAt(i) * 1.7f)
                    .scale(type.size());
            batch.add(model,
                    mix(type.emptyRgb() >> 16, type.rgb() >> 16, fill),
                    mix(type.emptyRgb() >> 8, type.rgb() >> 8, fill),
                    mix(type.emptyRgb(), type.rgb(), fill));
        }

        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        lighting.apply(shader, camera);
        batch.draw();
    }

    private static float mix(int from, int to, float t) {
        float a = (from & 0xFF) / 255f;
        float b = (to & 0xFF) / 255f;
        return a + (b - a) * t;
    }

    @Override
    public void close() {
        batch.close();
        shader.close();
    }
}

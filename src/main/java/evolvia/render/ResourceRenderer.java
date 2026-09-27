package evolvia.render;

import evolvia.components.ResourceNode;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.world.ResourceDefinition;
import evolvia.world.ResourceKind;
import org.joml.Matrix4f;

/**
 * Draws food nodes instanced: plants as bushes, meat as a lying carcass. The color blends from
 * the "empty" to the "full" color by how much food is left, so depleted areas are visible.
 * Water nodes are not drawn (the water surface already shows them).
 */
public final class ResourceRenderer implements AutoCloseable {

    private final Shader shader;
    private final InstanceBatch bushes;
    private final InstanceBatch carcasses;
    private final Matrix4f model = new Matrix4f();

    public ResourceRenderer() {
        shader = Shader.fromResources("shaders/instanced.vert", "shaders/terrain.frag");
        bushes = new InstanceBatch(new BoxMeshBuilder()
                .box(0f, 0.22f, 0f, 0.9f, 0.44f, 0.9f, 1.0f)      // lower bush
                .box(0.05f, 0.55f, -0.05f, 0.6f, 0.3f, 0.6f, 1.1f) // top
                .build());
        carcasses = new InstanceBatch(new BoxMeshBuilder()
                .box(0f, 0.16f, 0f, 0.62f, 0.32f, 0.9f, 1.0f)       // body lying on its side
                .box(0.05f, 0.12f, 0.6f, 0.36f, 0.24f, 0.34f, 0.9f)  // head
                .box(0.42f, 0.07f, 0.22f, 0.3f, 0.1f, 0.1f, 0.8f)    // legs
                .box(0.42f, 0.07f, -0.24f, 0.3f, 0.1f, 0.1f, 0.8f)
                .box(0f, 0.34f, 0f, 0.5f, 0.04f, 0.14f, 1.3f)        // ribs showing
                .box(0f, 0.34f, -0.2f, 0.5f, 0.04f, 0.12f, 1.3f)
                .build());
    }

    public void render(Camera camera, Lighting lighting, EcsWorld ecs) {
        ComponentStore<ResourceNode> nodes = ecs.store(ResourceNode.class);
        ComponentStore<Transform> transforms = ecs.store(Transform.class);

        bushes.begin();
        carcasses.begin();
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
            InstanceBatch batch = "meat".equals(type.foodType()) ? carcasses : bushes;
            batch.add(model,
                    mix(type.emptyRgb() >> 16, type.rgb() >> 16, fill),
                    mix(type.emptyRgb() >> 8, type.rgb() >> 8, fill),
                    mix(type.emptyRgb(), type.rgb(), fill));
        }

        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        lighting.apply(shader, camera);
        bushes.draw();
        carcasses.draw();
    }

    private static float mix(int from, int to, float t) {
        float a = (from & 0xFF) / 255f;
        float b = (to & 0xFF) / 255f;
        return a + (b - a) * t;
    }

    @Override
    public void close() {
        bushes.close();
        carcasses.close();
        shader.close();
    }
}

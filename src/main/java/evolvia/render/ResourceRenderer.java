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
 * Water nodes are not drawn (the water surface already shows them). Trees and rocks (phase 9g) shrink as
 * they are worked: a felled tree is a stump.
 */
public final class ResourceRenderer implements AutoCloseable {

    private final Shader shader;
    private final InstanceBatch bushes;
    private final InstanceBatch carcasses;
    private final InstanceBatch trunks;
    private final InstanceBatch crowns;
    private final InstanceBatch rocks;
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
        trunks = new InstanceBatch(new BoxMeshBuilder().box(0f, 0.5f, 0f, 0.26f, 1f, 0.26f, 1f).build());
        crowns = new InstanceBatch(new BoxMeshBuilder()
                .box(0f, 0f, 0f, 1.3f, 1.0f, 1.3f, 1.0f)
                .box(0.05f, 0.75f, -0.05f, 0.9f, 0.7f, 0.9f, 1.1f)
                .box(-0.02f, 1.3f, 0.02f, 0.45f, 0.45f, 0.45f, 1.15f)
                .build());
        rocks = new InstanceBatch(new BoxMeshBuilder()
                .box(0f, 0.25f, 0f, 0.9f, 0.5f, 0.75f, 1.0f)
                .box(0.25f, 0.5f, 0.1f, 0.45f, 0.35f, 0.45f, 1.1f)
                .box(-0.35f, 0.15f, -0.25f, 0.4f, 0.3f, 0.35f, 0.9f)
                .build());
    }

    /** A tree (trunk and crown, smaller as it is felled; a stump when empty) or a rock (smaller as it is broken). */
    private void addMaterial(int entity, ResourceNode node, Transform t) {
        ResourceDefinition type = node.type;
        float fill = Math.clamp(node.amount / type.capacity(), 0f, 1f);
        float size = type.size();
        if ("stone".equals(type.material())) {
            float s = size * (0.35f + 0.65f * fill);
            model.translation(t.position.x, t.position.y, t.position.z).rotateY(entity * 1.3f).scale(s);
            rocks.add(model, (type.rgb() >> 16 & 0xFF) / 255f, (type.rgb() >> 8 & 0xFF) / 255f, (type.rgb() & 0xFF) / 255f);
            return;
        }
        float height = fill > 0.05f ? 1.2f + 1.3f * fill : 0.25f; // a stump when felled
        model.translation(t.position.x, t.position.y, t.position.z).rotateY(entity * 1.7f).scale(size, size * height, size);
        trunks.add(model, (type.emptyRgb() >> 16 & 0xFF) / 255f, (type.emptyRgb() >> 8 & 0xFF) / 255f, (type.emptyRgb() & 0xFF) / 255f);
        if (fill > 0.05f) {
            float crown = size * (0.6f + 0.6f * fill);
            model.translation(t.position.x, t.position.y + size * height, t.position.z).rotateY(entity * 1.7f).scale(crown);
            crowns.add(model, (type.rgb() >> 16 & 0xFF) / 255f, (type.rgb() >> 8 & 0xFF) / 255f, (type.rgb() & 0xFF) / 255f);
        }
    }

    public void render(Camera camera, Lighting lighting, EcsWorld ecs, int spoilTicks) {
        ComponentStore<ResourceNode> nodes = ecs.store(ResourceNode.class);
        ComponentStore<Transform> transforms = ecs.store(Transform.class);

        bushes.begin();
        carcasses.begin();
        trunks.begin();
        crowns.begin();
        rocks.begin();
        for (int i = 0; i < nodes.size(); i++) {
            ResourceNode node = nodes.componentAt(i);
            ResourceDefinition type = node.type;
            if (type.kind() == ResourceKind.MATERIAL) {
                addMaterial(nodes.entityAt(i), node, transforms.get(nodes.entityAt(i)));
                continue;
            }
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
            float r = mix(type.emptyRgb() >> 16, type.rgb() >> 16, fill);
            float g = mix(type.emptyRgb() >> 8, type.rgb() >> 8, fill);
            float bl = mix(type.emptyRgb(), type.rgb(), fill);
            if (type.decays() && node.ageTicks > spoilTicks) { // spoiled: greenish (phase 9e)
                r = r * 0.5f + 0.2f;
                g = g * 0.5f + 0.3f;
                bl = bl * 0.5f + 0.08f;
            }
            batch.add(model, r, g, bl);
        }

        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        lighting.apply(shader, camera);
        bushes.draw();
        carcasses.draw();
        trunks.draw();
        crowns.draw();
        rocks.draw();
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
        trunks.close();
        crowns.close();
        rocks.close();
        shader.close();
    }
}

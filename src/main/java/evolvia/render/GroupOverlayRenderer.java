package evolvia.render;

import evolvia.components.GroupMember;
import evolvia.components.PrevTransform;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.world.Groups;
import evolvia.world.Terrain;
import org.joml.Matrix4f;

/**
 * Herd view (phase 9a): a flat ring in the herd's color under every member and a pole with a flag
 * above each leader. Visual only; toggled in the UI.
 */
public final class GroupOverlayRenderer implements AutoCloseable {

    private final Shader shader;
    private final InstanceBatch boxes;
    private final Matrix4f model = new Matrix4f();
    private final float[] color = new float[3];

    public GroupOverlayRenderer() {
        shader = Shader.fromResources("shaders/instanced.vert", "shaders/effects.frag");
        boxes = new InstanceBatch(new BoxMeshBuilder().box(0f, 0f, 0f, 1f, 1f, 1f, 1f).build());
    }

    public void render(Camera camera, Lighting lighting, Terrain terrain, EcsWorld ecs, Groups groups, float alpha) {
        ComponentStore<GroupMember> members = ecs.store(GroupMember.class);
        ComponentStore<Transform> transforms = ecs.store(Transform.class);
        ComponentStore<PrevTransform> previous = ecs.store(PrevTransform.class);
        boxes.begin();
        for (int i = 0; i < members.size(); i++) {
            int entity = members.entityAt(i);
            Groups.Group group = groups.get(members.componentAt(i).group);
            Transform t = transforms.get(entity);
            if (group == null || t == null) {
                continue;
            }
            PrevTransform p = previous.get(entity);
            float x = p != null ? p.position.x + (t.position.x - p.position.x) * alpha : t.position.x;
            float z = p != null ? p.position.z + (t.position.z - p.position.z) * alpha : t.position.z;
            float y = Math.max(terrain.heightAt(x, z), terrain.seaLevel()) + 0.04f;
            groupColor(group.id, color);
            boolean leader = group.leader == entity;
            float size = leader ? 1.5f : 1.0f;
            // Ring from four flat bars
            float half = size * 0.5f;
            float bar = 0.12f;
            model.translation(x, y, z - half).scale(size, 0.04f, bar);
            boxes.add(model, color[0], color[1], color[2]);
            model.translation(x, y, z + half).scale(size, 0.04f, bar);
            boxes.add(model, color[0], color[1], color[2]);
            model.translation(x - half, y, z).scale(bar, 0.04f, size);
            boxes.add(model, color[0], color[1], color[2]);
            model.translation(x + half, y, z).scale(bar, 0.04f, size);
            boxes.add(model, color[0], color[1], color[2]);
            if (leader) {
                model.translation(x, y + 1.3f, z).scale(0.06f, 2.6f, 0.06f);
                boxes.add(model, 0.35f, 0.28f, 0.2f);
                model.translation(x + 0.3f, y + 2.35f, z).scale(0.55f, 0.35f, 0.04f);
                boxes.add(model, color[0] * 1.3f, color[1] * 1.3f, color[2] * 1.3f);
            }
        }
        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        shader.setUniform("uAlpha", 1f);
        lighting.apply(shader, camera);
        boxes.draw();
    }

    /** Distinct, bright color per herd (golden-ratio hue steps). */
    public static void groupColor(int id, float[] out) {
        float hue = (id * 0.618034f) % 1f;
        float h = hue * 6f;
        int sector = (int) h;
        float f = h - sector;
        float s = 0.65f;
        float v = 1f;
        float p = v * (1f - s);
        float q = v * (1f - s * f);
        float t = v * (1f - s * (1f - f));
        switch (sector % 6) {
            case 0 -> set(out, v, t, p);
            case 1 -> set(out, q, v, p);
            case 2 -> set(out, p, v, t);
            case 3 -> set(out, p, q, v);
            case 4 -> set(out, t, p, v);
            default -> set(out, v, p, q);
        }
    }

    private static void set(float[] out, float r, float g, float b) {
        out[0] = r;
        out[1] = g;
        out[2] = b;
    }

    @Override
    public void close() {
        boxes.close();
        shader.close();
    }
}

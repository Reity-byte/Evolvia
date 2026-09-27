package evolvia.render;

import evolvia.components.Age;
import evolvia.components.Genome;
import evolvia.components.Needs;
import evolvia.components.PrevTransform;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.evolution.SpeciesDefinition;
import org.joml.Matrix4f;

/**
 * Draws all creatures in one instanced draw call. Position and heading are interpolated between
 * the last two ticks. Size and shade come from the genome, young creatures are smaller and grow;
 * sleeping creatures are darker, the selected creature is highlighted.
 * <p>
 * Placeholder shape: a box body with a smaller box head in front (+Z), so the heading is visible.
 * Procedural bodies come in phase 6. Only reads simulation data.
 */
public final class CreatureRenderer implements AutoCloseable {

    /** Newborns are drawn at this fraction of the adult size and grow linearly until adulthood. */
    private static final float NEWBORN_SCALE = 0.45f;
    private static final float SLEEP_DARKEN = 0.5f;
    private static final float PI = (float) Math.PI;

    private final Shader shader;
    private final InstanceBatch batch;
    private final Matrix4f model = new Matrix4f();

    public CreatureRenderer() {
        shader = Shader.fromResources("shaders/creature.vert", "shaders/terrain.frag");
        batch = new InstanceBatch(new BoxMeshBuilder()
                .box(0f, 0.26f, -0.08f, 0.46f, 0.42f, 0.72f, 1.0f)   // body
                .box(0f, 0.44f, 0.38f, 0.30f, 0.30f, 0.30f, 0.78f)   // head
                .build());
    }

    /**
     * @param alpha    interpolation factor between the previous (0) and the current (1) tick
     * @param selected entity to highlight, or -1
     */
    public void render(Camera camera, Lighting lighting, EcsWorld ecs, float alpha, int selected) {
        ComponentStore<SpeciesRef> creatures = ecs.store(SpeciesRef.class);
        ComponentStore<Transform> transforms = ecs.store(Transform.class);
        ComponentStore<PrevTransform> previous = ecs.store(PrevTransform.class);
        ComponentStore<Needs> needsStore = ecs.store(Needs.class);
        ComponentStore<Genome> genomes = ecs.store(Genome.class);
        ComponentStore<Age> ages = ecs.store(Age.class);

        batch.begin();
        for (int i = 0; i < creatures.size(); i++) {
            int entity = creatures.entityAt(i);
            Transform current = transforms.get(entity);
            if (current == null) {
                continue;
            }
            PrevTransform prev = previous.get(entity);
            float x = current.position.x;
            float y = current.position.y;
            float z = current.position.z;
            float yaw = current.yaw;
            if (prev != null) {
                x = prev.position.x + (x - prev.position.x) * alpha;
                y = prev.position.y + (y - prev.position.y) * alpha;
                z = prev.position.z + (z - prev.position.z) * alpha;
                yaw = lerpAngle(prev.yaw, yaw, alpha);
            }
            SpeciesDefinition species = creatures.componentAt(i).species.stats();
            Genome genome = genomes.get(entity);
            float size = species.bodySize() * (genome != null ? genome.size : 1f) * growth(ages.get(entity), species);
            model.translation(x, y, z).rotateY(yaw).scale(size);

            int rgb = species.rgb();
            float brightness = genome != null ? genome.tint : 1f;
            Needs needs = needsStore.get(entity);
            if (needs != null && needs.sleeping) {
                brightness *= SLEEP_DARKEN;
            }
            float r = ((rgb >> 16) & 0xFF) / 255f * brightness;
            float g = ((rgb >> 8) & 0xFF) / 255f * brightness;
            float b = (rgb & 0xFF) / 255f * brightness;
            if (entity == selected) {
                r = 0.5f + 0.5f * r;
                g = 0.5f + 0.5f * g;
                b = 0.9f;
            }
            batch.add(model, r, g, b);
        }

        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        lighting.apply(shader, camera);
        batch.draw();
    }

    /** Interpolates angles along the shorter way around the circle. */
    private static float lerpAngle(float from, float to, float t) {
        float delta = to - from;
        while (delta > PI) {
            delta -= 2 * PI;
        }
        while (delta < -PI) {
            delta += 2 * PI;
        }
        return from + delta * t;
    }

    /** Size factor from NEWBORN_SCALE at birth to 1 at adulthood. */
    private static float growth(Age age, SpeciesDefinition species) {
        if (age == null) {
            return 1f;
        }
        int adultTicks = SpeciesDefinition.secondsToTicks(species.reproduction().adultAgeSeconds());
        float t = Math.min(1f, age.ageTicks / (float) Math.max(1, adultTicks));
        return NEWBORN_SCALE + (1f - NEWBORN_SCALE) * t;
    }

    @Override
    public void close() {
        batch.close();
        shader.close();
    }
}

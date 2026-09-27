package evolvia.render;

import evolvia.components.Age;
import evolvia.components.Genome;
import evolvia.components.Needs;
import evolvia.components.PrevTransform;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.components.Velocity;
import evolvia.core.Time;
import evolvia.ecs.ComponentStore;
import evolvia.ecs.EcsWorld;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Draws all creatures instanced, one draw call per species. Each species has a procedural mesh
 * from {@link CreatureMeshBuilder}, rebuilt when its unlocked nodes change. Position and heading
 * are interpolated between the last two ticks. Size and shade come from the genome, young creatures
 * are smaller and grow; walking creatures swing their legs and bob, sleeping ones are darker,
 * the selected creature is highlighted. Only reads simulation data.
 */
public final class CreatureRenderer implements AutoCloseable {

    /** Newborns are drawn at this fraction of the adult size and grow linearly until adulthood. */
    private static final float NEWBORN_SCALE = 0.45f;
    private static final float SLEEP_DARKEN = 0.5f;
    private static final float WALK_AMPLITUDE = 0.6f;
    /** Body lift at the top of each step, as a fraction of the body size. */
    private static final float BOB_HEIGHT = 0.035f;
    /** Tiles walked per leg cycle, as a multiple of the body size. */
    private static final float STRIDE = 0.9f;
    private static final float PI = (float) Math.PI;
    private static final double TWO_PI = 2 * Math.PI;

    private final Shader shader;
    private final Map<Species, SpeciesMesh> meshes = new IdentityHashMap<>();
    private final Matrix4f model = new Matrix4f();

    private static final class SpeciesMesh {
        final InstanceBatch batch;
        final int revision;

        SpeciesMesh(Species species) {
            batch = new InstanceBatch(CreatureMeshBuilder.build(species.stats().rgb(), species.visuals()).toMesh());
            revision = species.revision();
        }
    }

    public CreatureRenderer() {
        shader = Shader.fromResources("shaders/creature.vert", "shaders/terrain.frag");
    }

    /**
     * @param alpha      interpolation factor between the previous (0) and the current (1) tick
     * @param simSeconds simulation time (for the walk animation; stops when paused)
     * @param selected   entity to highlight, or -1
     */
    public void render(Camera camera, Lighting lighting, EcsWorld ecs, float alpha, double simSeconds, int selected) {
        ComponentStore<SpeciesRef> creatures = ecs.store(SpeciesRef.class);
        ComponentStore<Transform> transforms = ecs.store(Transform.class);
        ComponentStore<PrevTransform> previous = ecs.store(PrevTransform.class);
        ComponentStore<Needs> needsStore = ecs.store(Needs.class);
        ComponentStore<Genome> genomes = ecs.store(Genome.class);
        ComponentStore<Age> ages = ecs.store(Age.class);
        ComponentStore<Velocity> velocities = ecs.store(Velocity.class);

        for (SpeciesMesh mesh : meshes.values()) {
            mesh.batch.begin();
        }
        Species lastKind = null;
        InstanceBatch batch = null;
        for (int i = 0; i < creatures.size(); i++) {
            int entity = creatures.entityAt(i);
            Transform current = transforms.get(entity);
            if (current == null) {
                continue;
            }
            Species kind = creatures.componentAt(i).species;
            if (kind != lastKind) {
                batch = batchFor(kind);
                lastKind = kind;
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
            SpeciesDefinition species = kind.stats();
            Genome genome = genomes.get(entity);
            float size = species.bodySize() * (genome != null ? genome.size : 1f) * growth(ages.get(entity), species);

            Needs needs = needsStore.get(entity);
            boolean sleeping = needs != null && needs.sleeping;
            Velocity velocity = velocities.get(entity);
            float phase = 0f;
            float amplitude = 0f;
            if (velocity != null && velocity.speed > 0f && !sleeping) {
                // Leg cycles per second from the walking speed; entity id offsets the phase.
                double cyclesPerSecond = velocity.speed * Time.TICKS_PER_SECOND / (STRIDE * size);
                phase = (float) ((simSeconds * cyclesPerSecond * TWO_PI + entity * 1.7) % TWO_PI);
                amplitude = WALK_AMPLITUDE;
                y += Math.abs((float) Math.sin(phase)) * BOB_HEIGHT * size;
            }
            model.translation(x, y, z).rotateY(yaw).scale(size);

            float brightness = genome != null ? genome.tint : 1f;
            if (sleeping) {
                brightness *= SLEEP_DARKEN;
            }
            float r = brightness;
            float g = brightness;
            float b = brightness;
            if (entity == selected) {
                r = 1.25f;
                g = 1.3f;
                b = 1.9f;
            }
            batch.add(model, r, g, b, phase, amplitude);
        }

        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        lighting.apply(shader, camera);
        for (SpeciesMesh mesh : meshes.values()) {
            mesh.batch.draw();
        }
    }

    /** The species' batch, (re)building its mesh when the species has evolved since. */
    private InstanceBatch batchFor(Species kind) {
        SpeciesMesh mesh = meshes.get(kind);
        if (mesh == null || mesh.revision != kind.revision()) {
            if (mesh != null) {
                mesh.batch.close();
            }
            mesh = new SpeciesMesh(kind);
            mesh.batch.begin();
            meshes.put(kind, mesh);
        }
        return mesh.batch;
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
        for (SpeciesMesh mesh : meshes.values()) {
            mesh.batch.close();
        }
        meshes.clear();
        shader.close();
    }
}

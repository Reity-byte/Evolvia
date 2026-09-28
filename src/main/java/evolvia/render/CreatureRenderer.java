package evolvia.render;

import evolvia.components.Age;
import evolvia.components.Carrying;
import evolvia.components.Genome;
import evolvia.components.Needs;
import evolvia.components.PrevTransform;
import evolvia.components.Sick;
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
import java.util.TreeMap;

/**
 * Draws all creatures instanced, one draw call per species and evolutionary stage. Each stage has a
 * procedural mesh from {@link CreatureMeshBuilder}; creatures look like the stage they were born with. Position and heading
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

    /** Meshes of one species, one per evolutionary stage alive; all rebuilt when the species evolves. */
    private static final class SpeciesMesh {
        final Map<Integer, InstanceBatch> stages = new TreeMap<>();
        /** Model height per stage, in body sizes (where a carried load sits). */
        final Map<Integer, Float> heights = new TreeMap<>();
        final int revision;

        SpeciesMesh(Species species) {
            revision = species.revision();
        }

        InstanceBatch batch(Species species, int stage) {
            return stages.computeIfAbsent(stage, s -> {
                Species.Stage data = species.stage(s);
                MeshData meshData = CreatureMeshBuilder.build(data.stats().rgb(), data.visuals());
                heights.put(s, CreatureMeshBuilder.height(meshData));
                InstanceBatch batch = new InstanceBatch(meshData.toMesh());
                batch.begin();
                return batch;
            });
        }

        void close() {
            stages.values().forEach(InstanceBatch::close);
        }
    }

    /** Loads carried to the camp (phase 9g): a log or a stone on the back. */
    private final Shader loadShader;
    private final InstanceBatch loads;

    public CreatureRenderer() {
        shader = Shader.fromResources("shaders/creature.vert", "shaders/terrain.frag");
        loadShader = Shader.fromResources("shaders/instanced.vert", "shaders/terrain.frag");
        loads = new InstanceBatch(new BoxMeshBuilder().box(0f, 0f, 0f, 1f, 1f, 1f, 1f).build());
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
            mesh.stages.values().forEach(InstanceBatch::begin);
        }
        loads.begin();
        ComponentStore<Carrying> carried = ecs.store(Carrying.class);
        for (int i = 0; i < creatures.size(); i++) {
            int entity = creatures.entityAt(i);
            Transform current = transforms.get(entity);
            if (current == null) {
                continue;
            }
            SpeciesRef ref = creatures.componentAt(i);
            InstanceBatch batch = batchFor(ref); // each creature looks like the stage it was born with
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
            SpeciesDefinition species = ref.stats();
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
            Sick sick = ecs.get(entity, Sick.class);
            if (sick != null && sick.isActive((int) (simSeconds * Time.TICKS_PER_SECOND))) {
                r *= 0.8f; // ill: a sickly green (phase 9e)
                g *= 1.1f;
                b *= 0.6f;
            }
            if (entity == selected) {
                r = 1.25f;
                g = 1.3f;
                b = 1.9f;
            }
            batch.add(model, r, g, b, phase, amplitude);
            Carrying load = carried.get(entity);
            if (load != null) {
                float top = meshes.get(ref.species).heights.getOrDefault(ref.stage, 1f) * size;
                model.translation(x, y + top * 0.82f, z).rotateY(yaw);
                if ("stone".equals(load.material)) {
                    model.translate(0f, 0.08f * size, -0.12f * size).rotateY(0.6f).scale(0.28f * size, 0.22f * size, 0.26f * size);
                    loads.add(model, 0.58f, 0.57f, 0.55f);
                } else {
                    model.translate(0f, 0.06f * size, -0.1f * size).rotateY((float) (Math.PI / 2)).scale(0.8f * size, 0.13f * size, 0.13f * size);
                    loads.add(model, 0.48f, 0.34f, 0.2f);
                }
            }
        }

        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        lighting.apply(shader, camera);
        for (SpeciesMesh mesh : meshes.values()) {
            mesh.stages.values().forEach(InstanceBatch::draw);
        }
        loadShader.bind();
        loadShader.setUniform("uProjection", camera.projection());
        loadShader.setUniform("uView", camera.view());
        lighting.apply(loadShader, camera);
        loads.draw();
    }

    /** The batch for a creature's species and stage, (re)building meshes when the species has evolved since. */
    private InstanceBatch batchFor(SpeciesRef ref) {
        SpeciesMesh mesh = meshes.get(ref.species);
        if (mesh == null || mesh.revision != ref.species.revision()) {
            if (mesh != null) {
                mesh.close();
            }
            mesh = new SpeciesMesh(ref.species);
            meshes.put(ref.species, mesh);
        }
        return mesh.batch(ref.species, ref.stage);
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
        loads.close();
        loadShader.close();
        meshes.values().forEach(SpeciesMesh::close);
        meshes.clear();
        shader.close();
    }
}

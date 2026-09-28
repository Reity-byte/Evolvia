package evolvia.render;

import evolvia.core.Time;
import evolvia.world.Nature;
import evolvia.world.Terrain;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Weather and disasters on screen (phase 9e): rain drops or snowflakes around the point the camera looks
 * at, flames on burning tiles and dark burnt ground. Reads the nature state, never changes it.
 */
public final class WeatherRenderer implements AutoCloseable {

    private static final int RAIN_DROPS = 1400;
    private static final int SNOWFLAKES = 1100;
    private static final float AREA = 80f;
    private static final float HEIGHT = 26f;

    private final Shader shader;
    private final InstanceBatch boxes;
    private final Matrix4f model = new Matrix4f();
    private final Vector3f forward = new Vector3f();

    public WeatherRenderer() {
        shader = Shader.fromResources("shaders/instanced.vert", "shaders/effects.frag");
        boxes = new InstanceBatch(new BoxMeshBuilder().box(0f, 0f, 0f, 1f, 1f, 1f, 1f).build());
    }

    public void render(Camera camera, Lighting lighting, Terrain terrain, Nature nature, double simSeconds) {
        boxes.begin();
        int tick = (int) (simSeconds * Time.TICKS_PER_SECOND);
        addFlames(terrain, nature, simSeconds);
        Nature.Weather weather = nature.weather();
        boolean blizzard = nature.blizzard(tick);
        if (weather.wet() || weather == Nature.Weather.SNOW || blizzard) {
            float[] focus = focus(camera, terrain);
            if (weather.wet()) {
                addRain(terrain, focus, simSeconds, weather == Nature.Weather.STORM ? 1.4f : 1f);
            } else {
                addSnow(terrain, focus, simSeconds, blizzard ? 1.8f : 1f);
            }
        }
        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        shader.setUniform("uAlpha", 1f);
        lighting.apply(shader, camera);
        boxes.draw();
    }

    /** Where the camera looks at the ground (roughly). */
    private float[] focus(Camera camera, Terrain terrain) {
        Vector3fc eye = camera.position();
        camera.view().positiveZ(forward).negate(); // view direction
        float ground = Math.max(terrain.seaLevel(), terrain.heightAt(clampX(terrain, eye.x()), clampZ(terrain, eye.z())));
        float t = forward.y < -0.05f ? (eye.y() - ground) / -forward.y : 60f;
        t = Math.min(t, 300f);
        return new float[]{eye.x() + forward.x * t, eye.z() + forward.z * t};
    }

    private static float clampX(Terrain terrain, float x) {
        return Math.clamp(x, 0f, terrain.width() - 0.01f);
    }

    private static float clampZ(Terrain terrain, float z) {
        return Math.clamp(z, 0f, terrain.depth() - 0.01f);
    }

    private static float surface(Terrain terrain, float x, float z) {
        if (x < 0 || z < 0 || x >= terrain.width() || z >= terrain.depth()) {
            return terrain.seaLevel();
        }
        return Math.max(terrain.heightAt(x, z), terrain.seaLevel());
    }

    private void addRain(Terrain terrain, float[] focus, double simSeconds, float density) {
        int drops = (int) (RAIN_DROPS * density);
        for (int i = 0; i < drops; i++) {
            float px = focus[0] + hash(i, 1) * AREA * 0.5f;
            float pz = focus[1] + hash(i, 2) * AREA * 0.5f;
            float bottom = surface(terrain, px, pz);
            double phase = simSeconds * 1.6 + Math.abs(hash(i, 3));
            float y = bottom + HEIGHT * (1f - (float) (phase - Math.floor(phase)));
            model.translation(px, y, pz).rotateX(0.12f).scale(0.05f, 0.9f, 0.05f);
            boxes.add(model, 0.7f, 0.8f, 1.1f);
        }
    }

    private void addSnow(Terrain terrain, float[] focus, double simSeconds, float density) {
        int flakes = (int) (SNOWFLAKES * density);
        for (int i = 0; i < flakes; i++) {
            double phase = simSeconds * 0.22 * density + Math.abs(hash(i, 3));
            float fall = (float) (phase - Math.floor(phase));
            float drift = (float) Math.sin(simSeconds * 0.8 + i) * 0.8f + fall * 6f * (density - 0.8f);
            float px = focus[0] + hash(i, 1) * AREA * 0.5f + drift;
            float pz = focus[1] + hash(i, 2) * AREA * 0.5f;
            float bottom = surface(terrain, px, pz);
            model.translation(px, bottom + HEIGHT * (1f - fall), pz).rotateY(i).scale(0.16f);
            boxes.add(model, 1.25f, 1.25f, 1.3f);
        }
    }

    private void addFlames(Terrain terrain, Nature nature, double simSeconds) {
        for (int i : nature.burningTiles()) {
            float x = i % terrain.width() + 0.5f;
            float z = i / terrain.width() + 0.5f;
            float ground = surface(terrain, x, z);
            for (int k = 0; k < 3; k++) {
                float flicker = 0.6f + 0.4f * (float) Math.abs(Math.sin(simSeconds * (5 + k) + i * 0.7 + k));
                float ox = hash(i, 10 + k) * 0.35f;
                float oz = hash(i, 20 + k) * 0.35f;
                float h = (k == 0 ? 1.6f : 1.0f) * flicker;
                model.translation(x + ox, ground + h * 0.5f, z + oz).rotateY(k + i).scale(0.45f, h, 0.45f);
                if (k == 0) {
                    boxes.add(model, 2.0f, 0.75f, 0.2f);
                } else {
                    boxes.add(model, 2.1f, 1.5f, 0.4f);
                }
            }
        }
    }

    /** Deterministic pseudo-random value in -1..1. */
    private static float hash(int i, int salt) {
        int h = i * 0x9E3779B1 + salt * 0x85EBCA77;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return (h & 0xFFFF) / 32767.5f - 1f;
    }

    @Override
    public void close() {
        boxes.close();
        shader.close();
    }
}

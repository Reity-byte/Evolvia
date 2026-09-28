package evolvia.render;

import evolvia.core.Time;
import evolvia.god.DivinePower;
import evolvia.god.GodPowers;
import evolvia.world.Terrain;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Simple visual effects of god powers, all from instanced boxes: rain clouds with falling drops,
 * lightning bolts with a scorch mark, rising sparkles of Abundance, and the ring that shows where
 * the selected power will hit. Driven by simulation time (effects freeze when paused). Visual only.
 */
public final class GodEffectsRenderer implements AutoCloseable {

    private static final int RAIN_DROPS = 260;
    private static final float CLOUD_HEIGHT = 16f;
    private static final float DROP_SPEED = 16f;
    private static final float BOLT_SECONDS = 0.45f;
    private static final float SCORCH_SECONDS = 8f;
    private static final float SPARKLE_SECONDS = 1.6f;
    private static final int SPARKLES = 48;
    private static final int RING_SEGMENTS = 56;
    private static final float CLOUD_ALPHA = 0.5f;

    private final Shader shader;
    private final InstanceBatch boxes;
    private final InstanceBatch clouds;
    private final Matrix4f model = new Matrix4f();

    public GodEffectsRenderer() {
        shader = Shader.fromResources("shaders/instanced.vert", "shaders/effects.frag");
        boxes = new InstanceBatch(new BoxMeshBuilder().box(0f, 0f, 0f, 1f, 1f, 1f, 1f).build());
        clouds = new InstanceBatch(new BoxMeshBuilder().box(0f, 0f, 0f, 1f, 1f, 1f, 1f).build());
    }

    /** Where the selected power would hit (drawn as a ring), or null. */
    public record Brush(float x, float z, float radius, float red, float green, float blue) {
    }

    /** Draws the solid effects (drops, bolts, sparkles, ring); call before the transparent water. */
    public void render(Camera camera, Lighting lighting, Terrain terrain, GodPowers powers, double simSeconds, Brush brush) {
        boxes.begin();
        clouds.begin();
        double simTicks = simSeconds * Time.TICKS_PER_SECOND;
        for (GodPowers.RainArea rain : powers.rains()) {
            addRain(terrain, rain, simSeconds, simTicks);
        }
        for (GodPowers.Strike strike : powers.recentStrikes()) {
            float age = (float) ((simTicks - strike.tick()) / Time.TICKS_PER_SECOND);
            switch (strike.power()) {
                case LIGHTNING -> addLightning(terrain, strike, age);
                case ABUNDANCE -> addSparkles(terrain, strike, age);
                default -> {
                }
            }
        }
        for (GodPowers.HandEffect effect : powers.recentHandEffects()) {
            addHandSparkles(terrain, effect, (float) ((simTicks - effect.tick()) / Time.TICKS_PER_SECOND));
        }
        if (brush != null) {
            addRing(terrain, brush);
        }
        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        shader.setUniform("uAlpha", 1f);
        lighting.apply(shader, camera);
        boxes.draw();
    }

    /** Draws the translucent rain clouds collected by {@link #render}; call after the water. */
    public void renderTranslucent(Camera camera, Lighting lighting) {
        shader.bind();
        shader.setUniform("uProjection", camera.projection());
        shader.setUniform("uView", camera.view());
        shader.setUniform("uAlpha", CLOUD_ALPHA);
        lighting.apply(shader, camera);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        clouds.draw();
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    private void addRain(Terrain terrain, GodPowers.RainArea rain, double simSeconds, double simTicks) {
        float ground = surface(terrain, rain.x(), rain.z());
        float cloudY = ground + CLOUD_HEIGHT;
        // Fade in over the first two seconds and out over the last two (by size).
        float sinceStart = (float) ((simTicks - rain.startTick()) / Time.TICKS_PER_SECOND);
        float untilEnd = (float) ((rain.endTick() - simTicks) / Time.TICKS_PER_SECOND);
        float grow = Math.clamp(Math.min(sinceStart, untilEnd) / 2f, 0.05f, 1f);
        float r = rain.radius();
        for (int k = 0; k < 10; k++) {
            float angle = k * 0.63f + hash(k, rain.startTick()) * 0.5f;
            float distance = k == 0 ? 0f : r * (0.35f + 0.25f * Math.abs(hash(k, 11)));
            float px = rain.x() + (float) Math.sin(angle) * distance;
            float pz = rain.z() + (float) Math.cos(angle) * distance;
            float size = r * (k == 0 ? 0.8f : 0.55f) * grow;
            model.translation(px, cloudY + hash(k, 7) * 0.6f, pz).rotateY(angle).scale(size, 1.1f * grow + 0.2f, size);
            clouds.add(model, 0.72f, 0.74f, 0.8f);
        }
        int drops = (int) (RAIN_DROPS * grow);
        for (int i = 0; i < drops; i++) {
            float angle = hash(i, rain.startTick() + 1) * 6.2832f;
            float distance = r * (float) Math.sqrt(Math.abs(hash(i, rain.startTick() + 2)));
            float px = rain.x() + (float) Math.sin(angle) * distance;
            float pz = rain.z() + (float) Math.cos(angle) * distance;
            float bottom = surface(terrain, px, pz);
            float fall = cloudY - bottom;
            double phase = simSeconds * DROP_SPEED / fall + Math.abs(hash(i, 3));
            float y = cloudY - (float) (phase - Math.floor(phase)) * fall;
            model.translation(px, y, pz).scale(0.06f, 0.7f, 0.06f);
            boxes.add(model, 0.75f, 0.85f, 1.2f);
        }
    }

    private void addLightning(Terrain terrain, GodPowers.Strike strike, float age) {
        float ground = surface(terrain, strike.x(), strike.z());
        if (age < SCORCH_SECONDS) {
            float fade = 1f - age / SCORCH_SECONDS;
            model.translation(strike.x(), ground + 0.03f, strike.z()).scale(strike.radius() * 2f * (0.6f + 0.4f * fade), 0.04f, strike.radius() * 2f);
            boxes.add(model, 0.12f + 0.3f * (1f - fade), 0.1f + 0.3f * (1f - fade), 0.08f + 0.3f * (1f - fade));
        }
        if (age < 0f || age > BOLT_SECONDS) {
            return;
        }
        float brightness = 3f * (1f - age / BOLT_SECONDS) + 0.5f;
        float x = strike.x();
        float y = ground;
        float z = strike.z();
        for (int s = 0; s < 10; s++) {
            float nx = strike.x() + hash(s, strike.tick()) * (s < 9 ? 2.2f : 0f);
            float ny = ground + (s + 1) * 4.5f;
            float nz = strike.z() + hash(s, strike.tick() + 5) * (s < 9 ? 2.2f : 0f);
            segment(x, y, z, nx, ny, nz, 0.14f, brightness, brightness, brightness * 0.85f);
            x = nx;
            y = ny;
            z = nz;
        }
    }

    private void addSparkles(Terrain terrain, GodPowers.Strike strike, float age) {
        if (age < 0f || age > SPARKLE_SECONDS) {
            return;
        }
        float t = age / SPARKLE_SECONDS;
        for (int i = 0; i < SPARKLES; i++) {
            float angle = hash(i, strike.tick()) * 6.2832f;
            float distance = strike.radius() * (float) Math.sqrt(Math.abs(hash(i, strike.tick() + 9)));
            float px = strike.x() + (float) Math.sin(angle) * distance;
            float pz = strike.z() + (float) Math.cos(angle) * distance;
            float y = surface(terrain, px, pz) + 0.3f + t * (2.5f + Math.abs(hash(i, 4)) * 2f);
            float size = 0.18f * (1f - t) + 0.02f;
            model.translation(px, y, pz).rotateY(age * 3f + i).scale(size);
            boxes.add(model, 0.8f + 0.6f * (1f - t), 1.6f, 0.45f);
        }
    }

    /** Golden sparkles where the god's hand touched a creature. */
    private void addHandSparkles(Terrain terrain, GodPowers.HandEffect effect, float age) {
        if (age < 0f || age > SPARKLE_SECONDS) {
            return;
        }
        float t = age / SPARKLE_SECONDS;
        for (int i = 0; i < 20; i++) {
            float angle = hash(i, effect.tick()) * 6.2832f;
            float distance = 1.2f * Math.abs(hash(i, effect.tick() + 3));
            float px = effect.x() + (float) Math.sin(angle) * distance;
            float pz = effect.z() + (float) Math.cos(angle) * distance;
            float y = surface(terrain, px, pz) + 0.3f + t * (2f + Math.abs(hash(i, 7)) * 1.5f);
            float size = 0.14f * (1f - t) + 0.02f;
            model.translation(px, y, pz).rotateY(age * 4f + i).scale(size);
            boxes.add(model, 1.8f, 1.5f, 0.6f);
        }
    }

    private void addRing(Terrain terrain, Brush brush) {
        float step = (float) (Math.PI * 2 / RING_SEGMENTS);
        float length = brush.radius() * step * 1.02f;
        for (int i = 0; i < RING_SEGMENTS; i++) {
            float angle = i * step;
            float px = brush.x() + (float) Math.sin(angle) * brush.radius();
            float pz = brush.z() + (float) Math.cos(angle) * brush.radius();
            model.translation(px, surface(terrain, px, pz) + 0.12f, pz).rotateY(angle).scale(length, 0.08f, 0.16f);
            boxes.add(model, brush.red(), brush.green(), brush.blue());
        }
        model.translation(brush.x(), surface(terrain, brush.x(), brush.z()) + 0.12f, brush.z()).scale(0.3f, 0.08f, 0.3f);
        boxes.add(model, brush.red(), brush.green(), brush.blue());
    }

    /** A thin box from (x0, y0, z0) to (x1, y1, z1). */
    private void segment(float x0, float y0, float z0, float x1, float y1, float z1, float thickness, float r, float g, float b) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        model.translation((x0 + x1) * 0.5f, (y0 + y1) * 0.5f, (z0 + z1) * 0.5f)
                .rotateTowards(dx, dy, dz, 1f, 0f, 0f)
                .scale(thickness, thickness, length);
        boxes.add(model, r, g, b);
    }

    /** Ground or water surface height. */
    private static float surface(Terrain terrain, float x, float z) {
        return Math.max(terrain.heightAt(x, z), terrain.seaLevel());
    }

    /** Deterministic value in [-1, 1] (visual variety only). */
    private static float hash(int a, int b) {
        int h = a * 73856093 ^ b * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0xFFFF) / 32767.5f - 1f;
    }

    /** Ring color of a power (dimmed red when it cannot be afforded). */
    public static Brush brush(DivinePower power, float x, float z, float radius, boolean affordable) {
        if (!affordable) {
            return new Brush(x, z, radius, 0.9f, 0.25f, 0.2f);
        }
        return switch (power) {
            case RAIN -> new Brush(x, z, radius, 0.45f, 0.7f, 1.3f);
            case ABUNDANCE -> new Brush(x, z, radius, 0.6f, 1.3f, 0.4f);
            case RAISE, LOWER -> new Brush(x, z, radius, 1.2f, 0.95f, 0.55f);
            case LIGHTNING -> new Brush(x, z, radius, 1.5f, 1.4f, 0.5f);
            case SANCTIFY -> new Brush(x, z, radius, 1.6f, 1.35f, 0.7f);
        };
    }

    @Override
    public void close() {
        boxes.close();
        clouds.close();
        shader.close();
    }
}

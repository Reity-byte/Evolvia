package evolvia.render;

import evolvia.world.WorldClock;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Scene-wide lighting and fog shared by all world shaders: one directional sun light, an ambient term,
 * a light tint and distance fog that fades into the sky color. Follows the time of day (phase 9d): the
 * sun circles, the night is dark and blue, dawn and dusk are orange.
 */
public final class Lighting {

    private static final Vector3fc DAY_SKY = new Vector3f(0.62f, 0.77f, 0.90f);
    private static final Vector3fc NIGHT_SKY = new Vector3f(0.05f, 0.07f, 0.15f);
    private static final Vector3fc DUSK_SKY = new Vector3f(0.85f, 0.55f, 0.38f);
    private static final Vector3fc NIGHT_TINT = new Vector3f(0.32f, 0.38f, 0.62f);
    private static final Vector3fc DUSK_TINT = new Vector3f(1.1f, 0.78f, 0.6f);

    private final Vector3f sunDirection = new Vector3f(-0.45f, 0.8f, -0.35f).normalize();
    private final Vector3f skyColor = new Vector3f(DAY_SKY);
    private final Vector3f tint = new Vector3f(1f, 1f, 1f);
    private final float ambient = 0.4f;
    private final float fogStart = 320f;
    private final float fogEnd = 900f;

    public Vector3fc skyColor() {
        return skyColor;
    }

    /** Sets sun, tint and sky for a time of day (0 = midnight, 0.5 = noon). */
    public void update(float timeOfDay) {
        float elevation = WorldClock.sunElevation(timeOfDay);
        float daylight = smoothstep(-0.15f, 0.3f, elevation);
        float dusk = Math.max(0f, 1f - Math.abs(elevation) / 0.3f) * 0.8f; // strongest at sunrise / sunset
        double angle = (timeOfDay - 0.25) * 2 * Math.PI;
        // Sun by day; a dim "moon" light from above at night (keeps shapes readable).
        float height = Math.max(Math.abs(elevation), 0.35f);
        sunDirection.set((float) Math.cos(angle), height, -0.35f).normalize();
        tint.set(NIGHT_TINT).lerp(new Vector3f(1f, 1f, 1f), daylight).lerp(DUSK_TINT, dusk * daylight);
        skyColor.set(NIGHT_SKY).lerp(DAY_SKY, daylight).lerp(DUSK_SKY, dusk * Math.min(1f, daylight + 0.3f));
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** Sets the lighting and fog uniforms on a bound shader. */
    public void apply(Shader shader, Camera camera) {
        shader.setUniform("uSunDirection", sunDirection);
        shader.setUniform("uAmbient", ambient);
        shader.setUniform("uLightTint", tint);
        shader.setUniform("uFogColor", skyColor);
        shader.setUniform("uFogStart", fogStart);
        shader.setUniform("uFogEnd", fogEnd);
        shader.setUniform("uCameraPos", camera.position());
    }
}

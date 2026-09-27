package evolvia.render;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Scene-wide lighting and fog shared by all world shaders: one directional sun light,
 * a constant ambient term and distance fog that fades into the sky color.
 */
public final class Lighting {

    private final Vector3f sunDirection = new Vector3f(-0.45f, 0.8f, -0.35f).normalize();
    private final Vector3f skyColor = new Vector3f(0.62f, 0.77f, 0.90f);
    private final float ambient = 0.4f;
    private final float fogStart = 320f;
    private final float fogEnd = 900f;

    public Vector3fc skyColor() {
        return skyColor;
    }

    /** Sets the lighting and fog uniforms on a bound shader. */
    public void apply(Shader shader, Camera camera) {
        shader.setUniform("uSunDirection", sunDirection);
        shader.setUniform("uAmbient", ambient);
        shader.setUniform("uFogColor", skyColor);
        shader.setUniform("uFogStart", fogStart);
        shader.setUniform("uFogEnd", fogEnd);
        shader.setUniform("uCameraPos", camera.position());
    }
}

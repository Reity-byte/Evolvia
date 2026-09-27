package evolvia.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Perspective camera looking from {@code position} at {@code target}.
 * Controls (RTS movement, zoom, rotation) come in phase 1.
 */
public final class Camera {

    private final Vector3f position = new Vector3f(0, 0, 5);
    private final Vector3f target = new Vector3f();
    private final Vector3f up = new Vector3f(0, 1, 0);

    private float fovYRadians = (float) Math.toRadians(60);
    private float near = 0.1f;
    private float far = 1000f;
    private float aspect = 16f / 9f;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();

    public void lookAt(float eyeX, float eyeY, float eyeZ, float targetX, float targetY, float targetZ) {
        position.set(eyeX, eyeY, eyeZ);
        target.set(targetX, targetY, targetZ);
    }

    /** Updates the aspect ratio from the framebuffer size; ignores a minimized (0-size) window. */
    public void setViewport(int width, int height) {
        if (width > 0 && height > 0) {
            aspect = (float) width / height;
        }
    }

    public Matrix4fc projection() {
        return projection.setPerspective(fovYRadians, aspect, near, far);
    }

    public Matrix4fc view() {
        return view.setLookAt(position, target, up);
    }
}

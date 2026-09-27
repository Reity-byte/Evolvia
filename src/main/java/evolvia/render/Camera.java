package evolvia.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Perspective camera looking from {@code position} at {@code target}.
 * Movement is driven by {@link CameraController}.
 */
public final class Camera {

    private static final float FOV_Y_RADIANS = (float) Math.toRadians(55);
    private static final float NEAR = 0.5f;
    private static final float FAR = 3000f;

    private final Vector3f position = new Vector3f(0, 0, 5);
    private final Vector3f target = new Vector3f();
    private final Vector3f up = new Vector3f(0, 1, 0);

    private float aspect = 16f / 9f;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();

    public void lookAt(Vector3fc eye, Vector3fc center) {
        position.set(eye);
        target.set(center);
    }

    /** Updates the aspect ratio from the framebuffer size; ignores a minimized (0-size) window. */
    public void setViewport(int width, int height) {
        if (width > 0 && height > 0) {
            aspect = (float) width / height;
        }
    }

    public Vector3fc position() {
        return position;
    }

    public Matrix4fc projection() {
        return projection.setPerspective(FOV_Y_RADIANS, aspect, NEAR, FAR);
    }

    public Matrix4fc view() {
        return view.setLookAt(position, target, up);
    }
}

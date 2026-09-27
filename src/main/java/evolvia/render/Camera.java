package evolvia.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

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
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f clip = new Vector4f();
    private final int[] viewport = new int[4];

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

    /**
     * Ray through a point of the window (for mouse picking).
     *
     * @param mouseX       cursor X in window coordinates (0 = left)
     * @param mouseY       cursor Y in window coordinates (0 = top)
     * @param windowWidth  window width in the same coordinates
     * @param windowHeight window height in the same coordinates
     */
    public void pickRay(double mouseX, double mouseY, int windowWidth, int windowHeight, Vector3f originDest, Vector3f dirDest) {
        viewProjection.set(projection()).mul(view());
        viewport[2] = windowWidth;
        viewport[3] = windowHeight;
        viewProjection.unprojectRay((float) mouseX, (float) (windowHeight - mouseY), viewport, originDest, dirDest);
        dirDest.normalize();
    }

    /**
     * Projects a world point to framebuffer pixels (origin top-left).
     *
     * @return false if the point is behind the camera
     */
    public boolean project(float x, float y, float z, int framebufferWidth, int framebufferHeight, Vector3f dest) {
        viewProjection.set(projection()).mul(view());
        clip.set(x, y, z, 1f).mul(viewProjection);
        if (clip.w <= 0f) {
            return false;
        }
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        dest.set((ndcX * 0.5f + 0.5f) * framebufferWidth, (1f - (ndcY * 0.5f + 0.5f)) * framebufferHeight, clip.z / clip.w);
        return true;
    }
}

package evolvia.render;

import evolvia.core.Input;
import evolvia.core.Window;
import evolvia.world.Terrain;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import static org.lwjgl.glfw.GLFW.*;

/**
 * RTS-style camera: orbits a focus point on the ground.
 * <ul>
 *   <li>WASD or cursor at the window edge: pan</li>
 *   <li>mouse wheel: zoom</li>
 *   <li>middle mouse drag: rotate and tilt; Q/E: rotate (for trackpads without a middle button)</li>
 * </ul>
 * The focus point is kept inside the map and the camera never goes below the terrain.
 * Runs on real frame time, so it works while the game is paused.
 */
public final class CameraController {

    private static final float MIN_DISTANCE = 8f;
    private static final float MAX_DISTANCE = 280f;
    private static final float MIN_PITCH = (float) Math.toRadians(15);
    private static final float MAX_PITCH = (float) Math.toRadians(85);
    /** Pan speed as a fraction of the zoom distance per second (zoomed out = faster). */
    private static final float PAN_SPEED = 0.9f;
    private static final float ZOOM_STEP = 0.88f;
    private static final float KEY_ROTATE_SPEED = (float) Math.toRadians(90);
    private static final float MOUSE_ROTATE_SPEED = (float) Math.toRadians(0.3);
    private static final int EDGE_PAN_MARGIN_PX = 6;
    private static final float MIN_CLEARANCE = 1.5f;
    /** How fast the focus height follows the terrain (1/s). */
    private static final float HEIGHT_FOLLOW_RATE = 8f;
    /** Largest frame time used, so a hitch does not fling the camera. */
    private static final float MAX_FRAME_SECONDS = 0.1f;

    private final Camera camera;
    private Terrain terrain;

    private final Vector3f focus = new Vector3f();
    private final Vector3f eye = new Vector3f();
    private float yaw = (float) Math.toRadians(35);
    private float pitch = (float) Math.toRadians(50);
    private float distance = 150f;

    public CameraController(Camera camera, Terrain terrain) {
        this.camera = camera;
        setTerrain(terrain);
        focus.set(terrain.width() / 2f, groundHeight(terrain.width() / 2f, terrain.depth() / 2f), terrain.depth() / 2f);
        applyToCamera();
    }

    /** Switches to another terrain (e.g. a regenerated world), keeping the view. */
    public void setTerrain(Terrain terrain) {
        this.terrain = terrain;
    }

    /** Point on the ground the camera orbits around. */
    public Vector3fc focus() {
        return focus;
    }

    public void update(Input input, Window window, float frameSeconds) {
        float dt = Math.min(frameSeconds, MAX_FRAME_SECONDS);

        // Rotation
        if (input.isKeyDown(GLFW_KEY_Q)) {
            yaw -= KEY_ROTATE_SPEED * dt;
        }
        if (input.isKeyDown(GLFW_KEY_E)) {
            yaw += KEY_ROTATE_SPEED * dt;
        }
        if (input.isButtonDown(GLFW_MOUSE_BUTTON_MIDDLE)) {
            yaw -= (float) input.mouseDeltaX() * MOUSE_ROTATE_SPEED;
            pitch += (float) input.mouseDeltaY() * MOUSE_ROTATE_SPEED;
        }
        pitch = Math.clamp(pitch, MIN_PITCH, MAX_PITCH);

        // Zoom
        if (input.scrollY() != 0) {
            distance *= (float) Math.pow(ZOOM_STEP, input.scrollY());
            distance = Math.clamp(distance, MIN_DISTANCE, MAX_DISTANCE);
        }

        // Pan, relative to the camera's heading on the ground plane
        float forwardInput = 0;
        float rightInput = 0;
        if (input.isKeyDown(GLFW_KEY_W)) forwardInput += 1;
        if (input.isKeyDown(GLFW_KEY_S)) forwardInput -= 1;
        if (input.isKeyDown(GLFW_KEY_D)) rightInput += 1;
        if (input.isKeyDown(GLFW_KEY_A)) rightInput -= 1;
        if (window.isFocused() && window.isHovered() && !input.isButtonDown(GLFW_MOUSE_BUTTON_MIDDLE)) {
            double mx = input.mouseX();
            double my = input.mouseY();
            if (mx <= EDGE_PAN_MARGIN_PX) rightInput -= 1;
            if (mx >= window.width() - 1 - EDGE_PAN_MARGIN_PX) rightInput += 1;
            if (my <= EDGE_PAN_MARGIN_PX) forwardInput += 1;
            if (my >= window.height() - 1 - EDGE_PAN_MARGIN_PX) forwardInput -= 1;
        }
        if (forwardInput != 0 || rightInput != 0) {
            float sin = (float) Math.sin(yaw);
            float cos = (float) Math.cos(yaw);
            // forward = (-sin, 0, -cos), right = (cos, 0, -sin)
            float dx = -sin * forwardInput + cos * rightInput;
            float dz = -cos * forwardInput - sin * rightInput;
            float length = (float) Math.sqrt(dx * dx + dz * dz);
            float step = PAN_SPEED * distance * dt / length;
            focus.x = Math.clamp(focus.x + dx * step, 0f, terrain.width());
            focus.z = Math.clamp(focus.z + dz * step, 0f, terrain.depth());
        }

        // Focus height smoothly follows the ground (or the water surface)
        float targetHeight = groundHeight(focus.x, focus.z);
        focus.y += (targetHeight - focus.y) * (1f - (float) Math.exp(-HEIGHT_FOLLOW_RATE * dt));

        applyToCamera();
    }

    private void applyToCamera() {
        float horizontal = (float) Math.cos(pitch) * distance;
        eye.set(focus.x + (float) Math.sin(yaw) * horizontal,
                focus.y + (float) Math.sin(pitch) * distance,
                focus.z + (float) Math.cos(yaw) * horizontal);
        float minEyeHeight = groundHeight(eye.x, eye.z) + MIN_CLEARANCE;
        if (eye.y < minEyeHeight) {
            eye.y = minEyeHeight;
        }
        camera.lookAt(eye, focus);
    }

    /** Terrain or water surface height, whichever is higher. Outside the map: sea level. */
    private float groundHeight(float x, float z) {
        if (x < 0 || z < 0 || x > terrain.width() || z > terrain.depth()) {
            return terrain.seaLevel();
        }
        return Math.max(terrain.heightAt(x, z), terrain.seaLevel());
    }
}

package evolvia.ui;

import evolvia.core.Input;
import evolvia.core.Window;
import evolvia.render.Camera;
import evolvia.world.Terrain;
import org.joml.Vector3f;

/** Finds the point on the ground (terrain or water surface) under the mouse cursor. */
public final class GroundPicker {

    private static final float RAY_STEP = 0.5f;
    private static final float RAY_LENGTH = 3000f;

    private final Vector3f origin = new Vector3f();
    private final Vector3f direction = new Vector3f();

    /** Ground point under the cursor, or null if the cursor points past the map. */
    public Vector3f pick(Input input, Window window, Camera camera, Terrain terrain) {
        camera.pickRay(input.mouseX(), input.mouseY(), window.width(), window.height(), origin, direction);
        Vector3f point = new Vector3f();
        float previous = 0f;
        for (float distance = 0f; distance < RAY_LENGTH; distance += RAY_STEP) {
            origin.fma(distance, direction, point);
            if (point.y <= surface(terrain, point.x, point.z)) {
                // Refine between the last point above and this one.
                float low = previous;
                float high = distance;
                for (int i = 0; i < 8; i++) {
                    float mid = (low + high) * 0.5f;
                    origin.fma(mid, direction, point);
                    if (point.y <= surface(terrain, point.x, point.z)) {
                        high = mid;
                    } else {
                        low = mid;
                    }
                }
                origin.fma(high, direction, point);
                boolean onMap = point.x >= 0 && point.z >= 0 && point.x <= terrain.width() && point.z <= terrain.depth();
                return onMap ? point : null;
            }
            previous = distance;
        }
        return null;
    }

    private static float surface(Terrain terrain, float x, float z) {
        return Math.max(terrain.heightAt(x, z), terrain.seaLevel());
    }
}

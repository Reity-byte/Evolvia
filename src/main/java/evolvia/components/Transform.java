package evolvia.components;

import org.joml.Vector3f;

/** Position on the terrain (Y = ground height) and heading. */
public final class Transform {

    public final Vector3f position = new Vector3f();
    /** Rotation around Y in radians; 0 = facing +Z, direction (dx, dz) has yaw atan2(dx, dz). */
    public float yaw;
}

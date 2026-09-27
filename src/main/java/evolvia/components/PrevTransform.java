package evolvia.components;

import org.joml.Vector3f;

/** {@link Transform} at the start of the current tick, used to interpolate rendering between ticks. */
public final class PrevTransform {

    public final Vector3f position = new Vector3f();
    public float yaw;
}

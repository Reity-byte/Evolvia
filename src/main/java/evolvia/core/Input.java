package evolvia.core;

import java.util.Arrays;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Keyboard and mouse state collected from GLFW callbacks.
 * <p>
 * "Down" = held right now, "pressed" = went down during the current frame.
 * {@link #endFrame()} must be called once per frame after all input has been consumed.
 */
public final class Input {

    private final boolean[] keysDown = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] keysPressed = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] buttonsDown = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final boolean[] buttonsPressed = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];

    private double mouseX;
    private double mouseY;
    private double mouseDeltaX;
    private double mouseDeltaY;
    private double scrollY;
    private boolean mouseKnown;

    public Input(Window window) {
        long handle = window.handle();

        glfwSetKeyCallback(handle, (w, key, scancode, action, mods) -> {
            if (key < 0 || key > GLFW_KEY_LAST) {
                return;
            }
            if (action == GLFW_PRESS) {
                keysDown[key] = true;
                keysPressed[key] = true;
            } else if (action == GLFW_RELEASE) {
                keysDown[key] = false;
            }
        });

        glfwSetMouseButtonCallback(handle, (w, button, action, mods) -> {
            if (button < 0 || button > GLFW_MOUSE_BUTTON_LAST) {
                return;
            }
            if (action == GLFW_PRESS) {
                buttonsDown[button] = true;
                buttonsPressed[button] = true;
            } else if (action == GLFW_RELEASE) {
                buttonsDown[button] = false;
            }
        });

        glfwSetCursorPosCallback(handle, (w, x, y) -> {
            if (!mouseKnown) {
                mouseX = x;
                mouseY = y;
                mouseKnown = true;
            }
            mouseDeltaX += x - mouseX;
            mouseDeltaY += y - mouseY;
            mouseX = x;
            mouseY = y;
        });

        glfwSetScrollCallback(handle, (w, dx, dy) -> scrollY += dy);
    }

    public boolean isKeyDown(int key) {
        return keysDown[key];
    }

    public boolean isKeyPressed(int key) {
        return keysPressed[key];
    }

    public boolean isButtonDown(int button) {
        return buttonsDown[button];
    }

    public boolean isButtonPressed(int button) {
        return buttonsPressed[button];
    }

    public double mouseX() {
        return mouseX;
    }

    public double mouseY() {
        return mouseY;
    }

    public double mouseDeltaX() {
        return mouseDeltaX;
    }

    public double mouseDeltaY() {
        return mouseDeltaY;
    }

    /** Scroll wheel movement during the current frame. */
    public double scrollY() {
        return scrollY;
    }

    /** Clears per-frame state (pressed edges, deltas). */
    public void endFrame() {
        Arrays.fill(keysPressed, false);
        Arrays.fill(buttonsPressed, false);
        mouseDeltaX = 0;
        mouseDeltaY = 0;
        scrollY = 0;
    }
}

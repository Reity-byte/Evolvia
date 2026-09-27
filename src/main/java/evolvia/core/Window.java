package evolvia.core;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * GLFW window with an OpenGL 3.3 core profile context (forward-compatible, required on macOS).
 */
public final class Window {

    private final long handle;
    private int framebufferWidth;
    private int framebufferHeight;

    public Window(String title, int width, int height, boolean vsync) {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) {
            glfwTerminate();
            throw new IllegalStateException("Failed to create window: OpenGL 3.3 core profile is not supported");
        }

        centerOnPrimaryMonitor(width, height);

        glfwMakeContextCurrent(handle);
        glfwSwapInterval(vsync ? 1 : 0);
        GL.createCapabilities();

        // Framebuffer size can differ from window size (HiDPI / Retina), so track it separately.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetFramebufferSize(handle, w, h);
            framebufferWidth = w.get(0);
            framebufferHeight = h.get(0);
        }
        glfwSetFramebufferSizeCallback(handle, (window, w, h) -> {
            framebufferWidth = w;
            framebufferHeight = h;
        });

        glfwShowWindow(handle);
    }

    private void centerOnPrimaryMonitor(int width, int height) {
        GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (mode != null) {
            glfwSetWindowPos(handle, (mode.width() - width) / 2, (mode.height() - height) / 2);
        }
    }

    /** Refresh rate of the primary monitor in Hz, or 60 if unknown. */
    public int refreshRate() {
        GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        return mode != null && mode.refreshRate() > 0 ? mode.refreshRate() : 60;
    }

    public long handle() {
        return handle;
    }

    public int framebufferWidth() {
        return framebufferWidth;
    }

    public int framebufferHeight() {
        return framebufferHeight;
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    public void requestClose() {
        glfwSetWindowShouldClose(handle, true);
    }

    public void swapBuffers() {
        glfwSwapBuffers(handle);
    }

    public void pollEvents() {
        glfwPollEvents();
    }

    /** Destroys the window and terminates GLFW. */
    public void destroy() {
        glfwFreeCallbacks(handle);
        glfwDestroyWindow(handle);
        glfwTerminate();
        GLFWErrorCallback previous = glfwSetErrorCallback(null);
        if (previous != null) {
            previous.free();
        }
    }
}

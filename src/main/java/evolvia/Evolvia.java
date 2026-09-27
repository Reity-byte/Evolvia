package evolvia;

import evolvia.core.GameLoop;
import evolvia.core.Input;
import evolvia.core.LoopStats;
import evolvia.core.Time;
import evolvia.core.Window;
import evolvia.render.Camera;
import evolvia.render.Mesh;
import evolvia.render.Primitives;
import evolvia.render.SceneRenderer;
import evolvia.ui.DebugOverlay;
import org.joml.Matrix4f;

import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;

/**
 * Application: wires window, input, loop, simulation and rendering together.
 * <p>
 * Phase 0: the "simulation" is a single rotating cube, used to verify the fixed-timestep
 * loop and render interpolation.
 */
public final class Evolvia implements GameLoop.Handler {

    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    private static final float TWO_PI = (float) (Math.PI * 2);
    /** Phase 0 demo: 90 degrees per game second. */
    private static final float CUBE_RADIANS_PER_TICK = (float) Math.toRadians(90) / Time.TICKS_PER_SECOND;

    private final Time time = new Time();
    private final LoopStats stats = new LoopStats();
    private final Camera camera = new Camera();
    private final Matrix4f cubeModel = new Matrix4f();

    private Window window;
    private Input input;
    private SceneRenderer sceneRenderer;
    private DebugOverlay debugOverlay;
    private Mesh cube;
    private String contextInfo;

    // Phase 0 simulation state (per tick) — rendered with interpolation.
    private float cubeAngle;
    private float prevCubeAngle;

    public void run() {
        window = new Window("Evolvia", WINDOW_WIDTH, WINDOW_HEIGHT, true);
        try {
            input = new Input(window);
            contextInfo = SceneRenderer.describeContext();
            System.out.println(contextInfo);

            sceneRenderer = new SceneRenderer();
            debugOverlay = new DebugOverlay();
            cube = Primitives.coloredCube();
            camera.lookAt(2.2f, 1.6f, 2.8f, 0f, 0f, 0f);

            new GameLoop(window, input, time, stats, this).run();
        } finally {
            if (cube != null) {
                cube.close();
            }
            if (debugOverlay != null) {
                debugOverlay.close();
            }
            if (sceneRenderer != null) {
                sceneRenderer.close();
            }
            window.destroy();
        }
    }

    @Override
    public void handleInput() {
        if (input.isKeyPressed(GLFW_KEY_ESCAPE)) {
            window.requestClose();
        }
        if (input.isKeyPressed(GLFW_KEY_F3)) {
            debugOverlay.toggle();
        }
    }

    @Override
    public void update(long tick) {
        prevCubeAngle = cubeAngle;
        cubeAngle += CUBE_RADIANS_PER_TICK;
        if (cubeAngle >= TWO_PI) {
            cubeAngle -= TWO_PI;
            prevCubeAngle -= TWO_PI;
        }
    }

    @Override
    public void render(float alpha) {
        int width = window.framebufferWidth();
        int height = window.framebufferHeight();
        camera.setViewport(width, height);

        float angle = prevCubeAngle + (cubeAngle - prevCubeAngle) * alpha;
        cubeModel.rotationX(0.35f).rotateY(angle);

        sceneRenderer.beginFrame(width, height, camera);
        sceneRenderer.draw(cube, cubeModel);

        if (debugOverlay.isVisible()) {
            debugOverlay.render(debugText(), width, height);
        }
    }

    private String debugText() {
        return String.format(Locale.ROOT,
                "FPS: %d%nTPS: %d (target %d, speed %dx)%nTick: %.3f ms%nTicks total: %d%nDropped ticks: %d%n%s%n%nF3 overlay | ESC quit",
                stats.fps(),
                stats.tps(), Time.TICKS_PER_SECOND * time.speed().multiplier, time.speed().multiplier,
                stats.avgTickMillis(),
                time.tickCount(),
                time.droppedTicks(),
                contextInfo);
    }
}

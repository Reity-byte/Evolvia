package evolvia;

import evolvia.core.GameLoop;
import evolvia.core.Input;
import evolvia.core.LaunchOptions;
import evolvia.core.LoopStats;
import evolvia.core.Time;
import evolvia.core.Time.Speed;
import evolvia.core.Window;
import evolvia.data.DataLoader;
import evolvia.evolution.SpeciesDefinition;
import evolvia.render.Camera;
import evolvia.render.CameraController;
import evolvia.render.SceneRenderer;
import evolvia.ui.DebugOverlay;
import evolvia.world.Biome;
import evolvia.world.BiomeTable;
import evolvia.world.Terrain;
import evolvia.world.World;
import evolvia.world.WorldConfig;
import org.joml.Vector3fc;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_2;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;

/**
 * Application: wires data, world, window, input, loop and rendering together.
 */
public final class Evolvia implements GameLoop.Handler {

    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;

    private final LaunchOptions options;
    private final Time time = new Time();
    private final LoopStats stats = new LoopStats();
    private final Camera camera = new Camera();

    private WorldConfig worldConfig;
    private BiomeTable biomes;
    private SpeciesDefinition species;
    private World world;

    private Window window;
    private Input input;
    private SceneRenderer sceneRenderer;
    private DebugOverlay debugOverlay;
    private CameraController cameraController;
    private String contextInfo;
    private int frameCap;

    public Evolvia(LaunchOptions options) {
        this.options = options;
    }

    public void run() {
        // Load data and generate the world before opening the window, so bad data fails fast.
        worldConfig = DataLoader.loadWorldConfig();
        biomes = DataLoader.loadBiomes();
        species = DataLoader.loadSpecies();
        world = createWorld(options.seed().orElseGet(Evolvia::randomSeed));

        window = new Window("Evolvia", WINDOW_WIDTH, WINDOW_HEIGHT, true);
        try {
            input = new Input(window);
            contextInfo = SceneRenderer.describeContext();
            System.out.println(contextInfo);

            sceneRenderer = new SceneRenderer(world, worldConfig.water());
            debugOverlay = new DebugOverlay();
            cameraController = new CameraController(camera, world.terrain());

            frameCap = options.fpsCap().orElseGet(window::refreshRate);
            new GameLoop(window, input, time, stats, this, frameCap).run();
        } finally {
            if (debugOverlay != null) {
                debugOverlay.close();
            }
            if (sceneRenderer != null) {
                sceneRenderer.close();
            }
            window.destroy();
        }
    }

    private World createWorld(long seed) {
        long start = System.nanoTime();
        World created = World.create(worldConfig, biomes, species, seed);
        System.out.printf(Locale.ROOT, "World seed %d: %dx%d tiles, %d creatures, generated in %d ms%n",
                seed, created.terrain().width(), created.terrain().depth(), created.ecs().entityCount(),
                (System.nanoTime() - start) / 1_000_000);
        return created;
    }

    private static long randomSeed() {
        return ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);
    }

    @Override
    public void handleInput(float frameSeconds) {
        if (input.isKeyPressed(GLFW_KEY_ESCAPE)) {
            window.requestClose();
        }
        if (input.isKeyPressed(GLFW_KEY_F3)) {
            debugOverlay.toggle();
        }
        if (input.isKeyPressed(GLFW_KEY_F5)) {
            regenerateWorld();
        }
        if (input.isKeyPressed(GLFW_KEY_SPACE)) {
            time.togglePause();
        }
        if (input.isKeyPressed(GLFW_KEY_1)) {
            time.setSpeed(Speed.NORMAL);
        }
        if (input.isKeyPressed(GLFW_KEY_2)) {
            time.setSpeed(Speed.FAST);
        }
        if (input.isKeyPressed(GLFW_KEY_3)) {
            time.setSpeed(Speed.FASTEST);
        }
        cameraController.update(input, window, frameSeconds);
    }

    /** Debug: replaces the world with a new one from a random seed. */
    private void regenerateWorld() {
        world = createWorld(randomSeed());
        sceneRenderer.close();
        sceneRenderer = new SceneRenderer(world, worldConfig.water());
        cameraController.setTerrain(world.terrain());
    }

    @Override
    public void update(long tick) {
        world.tick((int) tick);
    }

    @Override
    public void render(float alpha) {
        int width = window.framebufferWidth();
        int height = window.framebufferHeight();
        camera.setViewport(width, height);
        sceneRenderer.render(camera, width, height, alpha);

        if (debugOverlay.isVisible()) {
            debugOverlay.render(debugText(), width, height);
        }
    }

    private String debugText() {
        Terrain terrain = world.terrain();
        Vector3fc focus = cameraController.focus();
        int tx = Math.clamp((int) focus.x(), 0, terrain.width() - 1);
        int tz = Math.clamp((int) focus.z(), 0, terrain.depth() - 1);
        Biome biome = terrain.biome(tx, tz);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "FPS: %d (cap %s)%n", stats.fps(), frameCap > 0 ? frameCap : "off"));
        sb.append(String.format(Locale.ROOT, "TPS: %d (target %d) | speed %s%n",
                stats.tps(), Time.TICKS_PER_SECOND * time.speed().multiplier,
                time.speed() == Speed.PAUSED ? "PAUSED" : time.speed().multiplier + "x"));
        sb.append(String.format(Locale.ROOT, "Tick: %.3f ms | ticks total %d | dropped %d%n",
                stats.avgTickMillis(), time.tickCount(), time.droppedTicks()));
        sb.append(String.format(Locale.ROOT, "Seed: %d | map %dx%d | entities %d%n",
                world.seed(), terrain.width(), terrain.depth(), world.ecs().entityCount()));
        sb.append(String.format(Locale.ROOT, "Focus: tile %d, %d | height %.1f | %s (fertility %.2f, temp %.2f, moist %.2f)%n",
                tx, tz, terrain.tileHeight(tx, tz), biome.id(), terrain.fertility(tx, tz),
                terrain.temperature(tx, tz), terrain.moisture(tx, tz)));
        sb.append(contextInfo).append("\n\n");
        sb.append("WASD / screen edge: pan | wheel: zoom | MMB drag, Q/E: rotate\n");
        sb.append("Space: pause | 1/2/3: speed 1x/3x/10x | F5: new world | F3: overlay | ESC: quit");
        return sb.toString();
    }
}

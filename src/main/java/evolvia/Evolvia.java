package evolvia;

import evolvia.core.GameLoop;
import evolvia.core.Input;
import evolvia.core.LaunchOptions;
import evolvia.core.LoopStats;
import evolvia.core.Time;
import evolvia.core.Time.Speed;
import evolvia.core.Window;
import evolvia.data.DataLoader;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import evolvia.render.Camera;
import evolvia.render.CameraController;
import evolvia.render.SceneRenderer;
import evolvia.ui.CreatureSelection;
import evolvia.ui.DebugOverlay;
import evolvia.ui.EvolutionPanel;
import evolvia.ui.PopulationGraph;
import evolvia.world.Biome;
import evolvia.world.BiomeTable;
import evolvia.world.DeathStats;
import evolvia.world.ResourceTable;
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
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F7;
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
    private final CreatureSelection selection = new CreatureSelection();
    private final EvolutionPanel evolutionPanel = new EvolutionPanel();

    private WorldConfig worldConfig;
    private BiomeTable biomes;
    private SpeciesDefinition species;
    private ResourceTable resources;
    private EvolutionTree evolutionTree;
    private World world;

    private Window window;
    private Input input;
    private SceneRenderer sceneRenderer;
    private DebugOverlay debugOverlay;
    private PopulationGraph populationGraph;
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
        resources = DataLoader.loadResources();
        evolutionTree = DataLoader.loadEvolutionTree(biomes);
        world = createWorld(options.seed().orElseGet(Evolvia::randomSeed));

        window = new Window("Evolvia", WINDOW_WIDTH, WINDOW_HEIGHT, true);
        try {
            input = new Input(window);
            contextInfo = SceneRenderer.describeContext();
            System.out.println(contextInfo);

            sceneRenderer = new SceneRenderer(world, worldConfig.water());
            debugOverlay = new DebugOverlay();
            populationGraph = new PopulationGraph();
            cameraController = new CameraController(camera, world.terrain());

            frameCap = options.fpsCap().orElseGet(window::refreshRate);
            new GameLoop(window, input, time, stats, this, frameCap).run();
        } finally {
            if (populationGraph != null) {
                populationGraph.close();
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

    private World createWorld(long seed) {
        long start = System.nanoTime();
        World created = World.create(worldConfig, biomes, species, evolutionTree, resources, seed);
        System.out.printf(Locale.ROOT, "World seed %d: %dx%d tiles, %d creatures, %d resource nodes, generated in %d ms%n",
                seed, created.terrain().width(), created.terrain().depth(), created.creatureCount(),
                created.resourceNodeCount(),
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
        if (input.isKeyPressed(GLFW_KEY_F6)) {
            world.emptyAllFood();
        }
        if (input.isKeyPressed(GLFW_KEY_F4)) {
            evolutionPanel.toggle();
        }
        if (input.isKeyPressed(GLFW_KEY_F7)) {
            world.species().addPoints(100f); // debug
        }
        evolutionPanel.handleInput(input, world);
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
        selection.handleInput(input, window, camera, world);
    }

    /** Debug: replaces the world with a new one from a random seed. */
    private void regenerateWorld() {
        world = createWorld(randomSeed());
        sceneRenderer.close();
        sceneRenderer = new SceneRenderer(world, worldConfig.water());
        cameraController.setTerrain(world.terrain());
        selection.clear();
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
        sceneRenderer.render(camera, width, height, alpha, selection.selected(world));
        selection.renderLabel(debugOverlay, camera, world, alpha, width, height);

        if (debugOverlay.isVisible() && !evolutionPanel.isVisible()) { // one debug panel at a time
            debugOverlay.render(debugText(), width, height);
            populationGraph.render(world.history(), debugOverlay, width, height);
        }
        evolutionPanel.render(debugOverlay, world, width, height);
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
        DeathStats deaths = world.deaths();
        sb.append(String.format(Locale.ROOT, "Seed: %d | map %dx%d | resource nodes %d%n",
                world.seed(), terrain.width(), terrain.depth(), world.resourceNodeCount()));
        sb.append(String.format(Locale.ROOT, "Population: %d | births %d | max generation %d%n",
                world.creatureCount(), world.births().total(), world.maxGeneration()));
        sb.append(String.format(Locale.ROOT, "Food: %.0f units | deaths: hunger %d, thirst %d, climate %d, old age %d%n",
                world.totalFood(), deaths.count(DeathStats.Cause.STARVATION), deaths.count(DeathStats.Cause.THIRST),
                deaths.count(DeathStats.Cause.EXPOSURE), deaths.count(DeathStats.Cause.OLD_AGE)));
        sb.append(String.format(Locale.ROOT, "Evolution: %.0f EP (+%.1f/min) | unlocked %d / %d (F4)%n",
                world.species().points(), world.evolutionSystem().pointsPerMinute(),
                world.species().unlockedNodes().size(), world.species().tree().size()));
        sb.append(String.format(Locale.ROOT, "Pathfinding: %d waiting | %d searches, %d tiles last tick%n",
                world.pathQueue().size(), world.pathfindingSystem().searchesLastTick(),
                world.pathfindingSystem().expansionsLastTick()));
        sb.append(String.format(Locale.ROOT, "Focus: tile %d, %d | height %.1f | %s (fertility %.2f, temp %.2f, moist %.2f)%n",
                tx, tz, terrain.tileHeight(tx, tz), biome.id(), terrain.fertility(tx, tz),
                terrain.temperature(tx, tz), terrain.moisture(tx, tz)));
        sb.append(contextInfo).append("\n\n");
        sb.append("WASD / screen edge: pan | wheel: zoom | MMB drag, Q/E: rotate\n");
        sb.append("LMB: select creature | Space: pause | 1/2/3: speed 1x/3x/10x\n");
        sb.append("F4: evolution | F5: new world | F6: empty all food | F7: +100 EP (debug) | F3: overlay | ESC: quit");
        return sb.toString();
    }
}

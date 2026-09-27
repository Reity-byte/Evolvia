package evolvia;

import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.GameLoop;
import evolvia.core.Input;
import evolvia.core.LaunchOptions;
import evolvia.core.LoopStats;
import evolvia.core.Time;
import evolvia.core.Time.Speed;
import evolvia.core.Window;
import evolvia.data.DataLoader;
import evolvia.ecs.ComponentStore;
import evolvia.evolution.EvolutionTree;
import evolvia.evolution.SpeciesDefinition;
import evolvia.render.Camera;
import evolvia.render.CameraController;
import evolvia.render.CreatureMeshBuilder;
import evolvia.render.SceneRenderer;
import evolvia.ui.CreatureSelection;
import evolvia.ui.DebugOverlay;
import evolvia.ui.EvolutionTreeView;
import evolvia.ui.Hud;
import evolvia.ui.PopulationGraph;
import evolvia.ui.Ui;
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
    private final EvolutionTreeView treeView = new EvolutionTreeView();
    private final Hud hud = new Hud();

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
    private Ui ui;
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
        CreatureMeshBuilder.validate(evolutionTree);
        world = createWorld(options.seed().orElseGet(Evolvia::randomSeed));

        window = new Window("Evolvia", WINDOW_WIDTH, WINDOW_HEIGHT, true);
        try {
            input = new Input(window);
            contextInfo = SceneRenderer.describeContext();
            System.out.println(contextInfo);

            sceneRenderer = new SceneRenderer(world, worldConfig.water());
            debugOverlay = new DebugOverlay();
            populationGraph = new PopulationGraph();
            ui = new Ui();
            cameraController = new CameraController(camera, world.terrain());
            focusOnPopulation();

            frameCap = options.fpsCap().orElseGet(window::refreshRate);
            new GameLoop(window, input, time, stats, this, frameCap).run();
        } finally {
            if (ui != null) {
                ui.close();
            }
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
            // Close the topmost open window first; quit when nothing is open.
            if (treeView.isVisible()) {
                treeView.close();
            } else if (selection.selected(world) >= 0) {
                selection.clear();
            } else if (hud.isSpeciesPanelVisible()) {
                hud.toggleSpeciesPanel();
            } else {
                window.requestClose();
            }
        }
        if (input.isKeyPressed(GLFW_KEY_F3)) {
            debugOverlay.toggle();
        }
        if (input.isKeyPressed(GLFW_KEY_F4)) {
            treeView.toggle();
        }
        if (input.isKeyPressed(GLFW_KEY_F5)) {
            regenerateWorld();
        }
        if (input.isKeyPressed(GLFW_KEY_F6)) {
            world.emptyAllFood();
        }
        if (input.isKeyPressed(GLFW_KEY_F7)) {
            world.species().addPoints(100f); // debug
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

        // UI first: it decides whether the mouse belongs to a panel or to the world.
        ui.beginFrame(input, window);
        hud.build(ui, world, time, treeView);
        if (treeView.isVisible()) {
            treeView.build(ui, world, Hud.BAR_HEIGHT);
        } else {
            selection.buildPanel(ui, world, Hud.BAR_HEIGHT);
        }
        boolean mouseOnUi = ui.wantsMouse();

        if (selection.isFollowing()) {
            Transform followed = world.ecs().get(selection.selected(world), Transform.class);
            if (followed != null) {
                cameraController.follow(followed.position.x, followed.position.z);
            }
        }
        cameraController.update(input, window, frameSeconds, !mouseOnUi, !treeView.isVisible());
        if (!mouseOnUi) {
            selection.handleInput(input, window, camera, world);
        }
    }

    /** Debug: replaces the world with a new one from a random seed. */
    private void regenerateWorld() {
        world = createWorld(randomSeed());
        sceneRenderer.close();
        sceneRenderer = new SceneRenderer(world, worldConfig.water());
        cameraController.setTerrain(world.terrain());
        focusOnPopulation();
        selection.clear();
    }

    /** Points the camera at the middle of the population (the player's creatures). */
    private void focusOnPopulation() {
        ComponentStore<SpeciesRef> creatures = world.ecs().store(SpeciesRef.class);
        if (creatures.size() == 0) {
            return;
        }
        double x = 0;
        double z = 0;
        for (int i = 0; i < creatures.size(); i++) {
            Transform t = world.ecs().get(creatures.entityAt(i), Transform.class);
            x += t.position.x;
            z += t.position.z;
        }
        cameraController.focusOn((float) (x / creatures.size()), (float) (z / creatures.size()), 45f);
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
        double simSeconds = (time.tickCount() + alpha) / Time.TICKS_PER_SECOND;
        sceneRenderer.render(camera, width, height, alpha, simSeconds, selection.selected(world));

        if (debugOverlay.isVisible() && !treeView.isVisible()) {
            selection.renderLabel(debugOverlay, camera, world, alpha, width, height);
            float left = hud.isSpeciesPanelVisible() ? 350f : 10f;
            debugOverlay.renderPanel(debugText(), left * ui.scale(), (Hud.BAR_HEIGHT + 12f) * ui.scale(), width, height);
            populationGraph.render(world.history(), debugOverlay, width, height);
        }
        if (!treeView.isVisible()) {
            selection.renderMarker(ui, camera, world, alpha, width, height);
        }
        ui.render(width, height);
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
        sb.append("F4: evolution tree | F5: new world | F6: empty all food | F7: +100 EP (debug) | F3: overlay | ESC: close / quit");
        return sb.toString();
    }
}

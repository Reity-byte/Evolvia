package evolvia;

import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.GameDirs;
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
import evolvia.god.DivinePower;
import evolvia.god.GodConfig;
import evolvia.god.GodPowers;
import evolvia.render.Camera;
import evolvia.render.CameraController;
import evolvia.render.CreatureMeshBuilder;
import evolvia.render.GodEffectsRenderer;
import evolvia.render.SceneRenderer;
import evolvia.save.SaveData;
import evolvia.save.SaveException;
import evolvia.save.SaveManager;
import evolvia.save.WorldCodec;
import evolvia.ui.CreatureSelection;
import evolvia.ui.DebugOverlay;
import evolvia.ui.EvolutionTreeView;
import evolvia.components.Believer;
import evolvia.components.GroupMember;
import evolvia.ui.GameMenu;
import evolvia.ui.GameOverView;
import evolvia.ui.MilestonePanel;
import evolvia.world.Milestones;
import evolvia.ui.GroundPicker;
import evolvia.ui.Hud;
import evolvia.ui.Notifications;
import evolvia.ui.PopulationGraph;
import evolvia.ui.PowerBar;
import evolvia.ui.Ui;
import evolvia.world.Biome;
import evolvia.world.BiomeTable;
import evolvia.world.DeathStats;
import evolvia.world.ResourceTable;
import evolvia.world.Terrain;
import evolvia.world.World;
import evolvia.world.WorldConfig;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F8;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F10;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_G;
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
    private final PowerBar powerBar = new PowerBar();
    private final GameMenu gameMenu = new GameMenu();
    private final Notifications notifications = new Notifications();
    private final MilestonePanel milestonePanel = new MilestonePanel();
    private final GameOverView gameOverView = new GameOverView();
    /** Autosave every 5 minutes of real time while the game runs (not while paused). */
    private static final float AUTOSAVE_SECONDS = 300f;
    private float autosaveTimer;
    /** Set by the background save writer; the open menu then reloads its list. */
    private volatile boolean savesChanged;
    private SaveManager saves;
    private final GroundPicker groundPicker = new GroundPicker();
    private GodEffectsRenderer.Brush brush;
    private static final double FLASH_SECONDS = 0.25;
    private GodPowers.Strike lastFlashedStrike;
    private long flashStartNanos;

    private WorldConfig worldConfig;
    private BiomeTable biomes;
    private SpeciesDefinition species;
    private ResourceTable resources;
    private EvolutionTree evolutionTree;
    private GodConfig godConfig;
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
        godConfig = DataLoader.loadGodConfig();
        // Resolved here, not in a static constant: the entry point decides where the data folder is.
        saves = new SaveManager(GameDirs.root().resolve("saves"));
        WorldCodec.Loaded startSave = null;
        if (options.load().isPresent()) {
            startSave = WorldCodec.restore(saves.load(options.load().get()), gameData());
            world = startSave.world();
            time.restore(startSave.tick(), startSave.speed());
        } else {
            world = createWorld(options.seed().orElseGet(Evolvia::randomSeed));
        }

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
            if (startSave != null && startSave.view() != null) {
                setView(startSave.view());
            } else {
                focusOnPopulation();
            }

            frameCap = options.fpsCap().orElseGet(window::refreshRate);
            new GameLoop(window, input, time, stats, this, frameCap).run();
            autosave(); // on quit
        } finally {
            saves.close(); // waits for the save being written
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
        World created = World.create(worldConfig, biomes, species, evolutionTree, resources, godConfig, seed);
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
            // Put away the selected power / close the topmost open window first; quit when nothing is open.
            if (gameMenu.isVisible()) {
                gameMenu.close();
            } else if (selection.hasPendingHand()) {
                selection.cancelHand();
            } else if (powerBar.armed() != null) {
                powerBar.disarm();
            } else if (treeView.isVisible()) {
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
            saveAsync(SaveManager.QUICK_SAVE, "Rychle uloženo");
        }
        if (input.isKeyPressed(GLFW_KEY_F9)) {
            load(SaveManager.QUICK_SAVE);
        }
        if (input.isKeyPressed(GLFW_KEY_F6)) {
            world.emptyAllFood();
        }
        if (input.isKeyPressed(GLFW_KEY_F7)) {
            world.species().addPoints(100f); // debug
        }
        if (input.isKeyPressed(GLFW_KEY_G)) {
            hud.toggleGroups();
        }
        if (input.isKeyPressed(GLFW_KEY_F10)) {
            world.evolveEveryone(); // debug: skip waiting for generations
        }
        if (input.isKeyPressed(GLFW_KEY_F8)) {
            world.godPowers().faith().add(100f); // debug
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
        if (hud.build(ui, world, time, treeView, gameMenu.isVisible())) {
            if (gameMenu.isVisible()) {
                gameMenu.close();
            } else {
                gameMenu.open(saves.list());
                treeView.close();
                powerBar.disarm();
            }
        }
        if (gameMenu.isVisible()) {
            if (savesChanged) {
                savesChanged = false;
                gameMenu.setSaves(saves.list());
            }
            GameMenu.Choice choice = gameMenu.build(ui, Hud.BAR_HEIGHT, input.scrollY());
            if (choice != null) {
                menuChoice(choice);
            }
        } else if (world.playerDefeated()) {
            time.setSpeed(Speed.PAUSED);
            GameOverView.Choice choice = gameOverView.build(ui, world, Hud.BAR_HEIGHT);
            if (choice == GameOverView.Choice.LOAD) {
                gameMenu.open(saves.list());
            } else if (choice == GameOverView.Choice.NEW_WORLD) {
                regenerateWorld();
                time.setSpeed(Speed.NORMAL);
            }
        } else if (treeView.isVisible()) {
            treeView.build(ui, world, Hud.BAR_HEIGHT, input.scrollY());
        } else {
            if (!hud.isSpeciesPanelVisible()) {
                milestonePanel.build(ui, world, Hud.BAR_HEIGHT);
            }
            selection.buildPanel(ui, world, Hud.BAR_HEIGHT, notifications);
            powerBar.build(ui, world);
            if (selection.hasPendingHand()) {
                powerBar.disarm();
            }
        }
        for (Milestones.Milestone m : world.milestones().takeAnnouncements()) {
            notifications.info(String.format(Locale.ROOT, "Cíl splněn: %s (+%.0f EP, +%.0f Víry)", m.name(), m.rewardEp(), m.rewardFaith()));
        }
        notifications.build(ui, Hud.BAR_HEIGHT);
        boolean mouseOnUi = ui.wantsMouse();

        if (time.speed() != Speed.PAUSED) {
            autosaveTimer += frameSeconds;
            if (autosaveTimer >= AUTOSAVE_SECONDS) {
                autosaveTimer = 0f;
                saveAsync(SaveManager.AUTOSAVE, "Automaticky uloženo");
            }
        }

        brush = null;
        if (!treeView.isVisible() && !gameMenu.isVisible() && !mouseOnUi && powerBar.armed() != null) {
            Vector3f ground = groundPicker.pick(input, window, camera, world.terrain());
            brush = powerBar.handleWorld(input, world, ground, frameSeconds);
        }

        if (selection.isFollowing()) {
            Transform followed = world.ecs().get(selection.selected(world), Transform.class);
            if (followed != null) {
                cameraController.follow(followed.position.x, followed.position.z);
            }
        }
        if (time.speed() == Speed.PAUSED) {
            world.applyGodPowersNow((int) time.tickCount()); // powers work during a pause too
        }
        cameraController.update(input, window, frameSeconds, !mouseOnUi, !treeView.isVisible());
        if (!mouseOnUi && selection.hasPendingHand()) {
            selection.handleHand(input, window, camera, world, notifications);
        } else if (!mouseOnUi && powerBar.armed() == null) {
            selection.handleInput(input, window, camera, world);
        }
    }

    /** Replaces the world with a new one from a random seed (menu: "Nový svět"). */
    private void regenerateWorld() {
        replaceWorld(createWorld(randomSeed()));
        focusOnPopulation();
    }

    private void replaceWorld(World replacement) {
        world = replacement;
        sceneRenderer.close();
        sceneRenderer = new SceneRenderer(world, worldConfig.water());
        cameraController.setTerrain(world.terrain());
        selection.clear();
        powerBar.disarm();
        treeView.close();
        autosaveTimer = 0f;
    }

    // ---------------------------------------------------------------- save games

    private WorldCodec.GameData gameData() {
        return new WorldCodec.GameData(worldConfig.water().shallowDepth(), biomes, species, evolutionTree, resources, godConfig);
    }

    private void menuChoice(GameMenu.Choice choice) {
        switch (choice) {
            case GameMenu.Choice.SaveNew ignored -> saveAsync(species.name() + " "
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")), "Uloženo");
            case GameMenu.Choice.QuickSave ignored -> saveAsync(SaveManager.QUICK_SAVE, "Rychle uloženo");
            case GameMenu.Choice.Load load -> {
                if (load(load.name())) {
                    gameMenu.close();
                }
            }
            case GameMenu.Choice.Delete delete -> {
                try {
                    saves.delete(delete.name());
                    notifications.info("Smazáno: " + delete.name());
                } catch (IOException e) {
                    notifications.error("Smazání selhalo: " + e.getMessage());
                }
                gameMenu.setSaves(saves.list());
            }
            case GameMenu.Choice.NewWorld ignored -> {
                regenerateWorld();
                gameMenu.close();
            }
            case GameMenu.Choice.Quit ignored -> window.requestClose(); // autosaves on the way out
        }
    }

    /** Takes a snapshot now (between ticks) and writes it in the background. */
    private void saveAsync(String name, String doneMessage) {
        SaveData data;
        try {
            data = WorldCodec.snapshot(world, name, time.tickCount(), time.speed(), view());
        } catch (RuntimeException e) {
            notifications.error("Uložení selhalo: " + e.getMessage());
            return;
        }
        saves.saveAsync(data).whenComplete((file, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                notifications.error("Uložení selhalo: " + cause.getMessage());
            } else {
                notifications.info(doneMessage);
                savesChanged = true;
            }
        });
    }

    /** Autosave when quitting: written before the game exits. */
    private void autosave() {
        try {
            saves.save(WorldCodec.snapshot(world, SaveManager.AUTOSAVE, time.tickCount(), time.speed(), view()));
        } catch (IOException | RuntimeException e) {
            System.err.println("Autosave on quit failed: " + e);
        }
    }

    /** Loads a save and replaces the world; returns false (with a message) if it failed. */
    private boolean load(String name) {
        WorldCodec.Loaded loaded;
        try {
            saves.flush(); // a save of the same name may still be being written
            loaded = WorldCodec.restore(saves.load(name), gameData());
        } catch (SaveException e) {
            notifications.error(e.getMessage());
            return false;
        }
        replaceWorld(loaded.world());
        time.restore(loaded.tick(), loaded.speed());
        if (loaded.view() != null) {
            setView(loaded.view());
        } else {
            focusOnPopulation();
        }
        notifications.info("Načteno: " + name);
        if (!loaded.skippedNodes().isEmpty()) {
            notifications.error("Evoluční uzly, které už hra nezná, byly přeskočeny: " + String.join(", ", loaded.skippedNodes()));
        }
        return true;
    }

    private SaveData.View view() {
        CameraController.View v = cameraController.view();
        return new SaveData.View(v.focusX(), v.focusZ(), v.yaw(), v.pitch(), v.distance());
    }

    private void setView(SaveData.View v) {
        cameraController.setView(new CameraController.View(v.focusX(), v.focusZ(), v.yaw(), v.pitch(), v.distance()));
    }

    /** Points the camera at the middle of the player's people (all creatures if there are none). */
    private void focusOnPopulation() {
        ComponentStore<SpeciesRef> creatures = world.ecs().store(SpeciesRef.class);
        double x = 0;
        double z = 0;
        int count = 0;
        for (int pass = 0; pass < 2 && count == 0; pass++) {
            for (int i = 0; i < creatures.size(); i++) {
                int entity = creatures.entityAt(i);
                if (pass == 0 && world.ecs().get(entity, Believer.class) == null) {
                    continue;
                }
                Transform t = world.ecs().get(entity, Transform.class);
                x += t.position.x;
                z += t.position.z;
                count++;
            }
        }
        if (count > 0) {
            cameraController.focusOn((float) (x / count), (float) (z / count), 45f);
        }
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
        sceneRenderer.setShowGroups(hud.showGroups(world));
        sceneRenderer.setSelectedGroup(selectedGroup());
        sceneRenderer.render(camera, width, height, alpha, simSeconds, selection.selected(world), brush);

        if (debugOverlay.isVisible() && !treeView.isVisible()) {
            selection.renderLabel(debugOverlay, camera, world, alpha, width, height);
            float left = hud.isSpeciesPanelVisible() ? 350f : 10f;
            debugOverlay.renderPanel(debugText(), left * ui.scale(), (Hud.BAR_HEIGHT + 12f) * ui.scale(), width, height);
            populationGraph.render(world.history(), debugOverlay, width, height);
        }
        if (!treeView.isVisible()) {
            selection.renderMarker(ui, camera, world, alpha, width, height);
        }
        lightningFlash();
        ui.render(width, height);
    }

    /** Herd of the selected creature (its territory is shown), or 0. */
    private int selectedGroup() {
        int entity = selection.selected(world);
        GroupMember member = entity >= 0 ? world.ecs().get(entity, GroupMember.class) : null;
        return member != null ? member.group : 0;
    }

    /** Brief white flash of the screen right after a lightning strike (real time, so it also fades while paused). */
    private void lightningFlash() {
        GodPowers.Strike newest = null;
        for (GodPowers.Strike strike : world.godPowers().recentStrikes()) {
            if (strike.power() == DivinePower.LIGHTNING) {
                newest = strike;
            }
        }
        if (newest != null && newest != lastFlashedStrike) {
            lastFlashedStrike = newest;
            flashStartNanos = System.nanoTime();
        }
        double age = (System.nanoTime() - flashStartNanos) / 1e9;
        if (lastFlashedStrike != null && age < FLASH_SECONDS) {
            int alpha = (int) (110 * (1 - age / FLASH_SECONDS));
            ui.draw().rect(0, 0, ui.width(), ui.height(), (alpha << 24) | 0xFFFFF0);
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
        DeathStats deaths = world.deaths();
        sb.append(String.format(Locale.ROOT, "Seed: %d | map %dx%d | resource nodes %d%n",
                world.seed(), terrain.width(), terrain.depth(), world.resourceNodeCount()));
        sb.append(String.format(Locale.ROOT, "Population: %d | births %d | max generation %d%n",
                world.creatureCount(), world.births().total(), world.maxGeneration()));
        sb.append(String.format(Locale.ROOT, "Food: %.0f units | deaths: hunger %d, thirst %d, climate %d, old age %d, lightning %d%n",
                world.totalFood(), deaths.count(DeathStats.Cause.STARVATION), deaths.count(DeathStats.Cause.THIRST),
                deaths.count(DeathStats.Cause.EXPOSURE), deaths.count(DeathStats.Cause.OLD_AGE),
                deaths.count(DeathStats.Cause.LIGHTNING)));
        sb.append(String.format(Locale.ROOT, "Herds: %d | members %d%n", world.groups().count(),
                world.ecs().store(evolvia.components.GroupMember.class).size()));
        sb.append(String.format(Locale.ROOT, "People %d | wild %d | fights: deaths %d, victories %d%n", world.believers(),
                world.creatureCount() - world.believers(), deaths.count(DeathStats.Cause.FIGHT), world.groups().playerVictories()));
        sb.append(String.format(Locale.ROOT, "Faith: %.0f (+%.1f/min) | believers %d | alignment %+.2f | rains %d%n",
                world.godPowers().faith().points(), world.godPowers().faith().perMinute(), world.believers(),
                world.godPowers().faith().alignment(), world.godPowers().rains().size()));
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
        sb.append("F4: evolution tree | F5/F9: quick save/load | F6: empty all food | F7: +100 EP, F8: +100 faith, F10: evolve all (debug) | F3: overlay | ESC: close / quit");
        return sb.toString();
    }
}

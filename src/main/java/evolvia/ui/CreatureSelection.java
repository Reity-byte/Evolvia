package evolvia.ui;

import evolvia.ai.ActionType;
import evolvia.components.Age;
import evolvia.components.Genome;
import evolvia.components.GroupMember;
import evolvia.components.AiState;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.components.PrevTransform;
import evolvia.components.Reproduction;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Input;
import evolvia.core.Time;
import evolvia.core.Window;
import evolvia.evolution.SpeciesDefinition;
import evolvia.render.Camera;
import evolvia.world.Groups;
import evolvia.world.World;
import org.joml.Vector3f;

import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * Selection of one creature (DESIGN.md §9): left click picks the creature nearest to the clicked
 * ground point; a panel shows its action, needs, age and genes (with F3 also a debug label above its
 * head). UI state only, not simulation.
 */
public final class CreatureSelection {

    /** How far from the clicked ground point a creature may be to get selected (tiles). */
    private static final float PICK_RADIUS = 2.5f;
    private final GroundPicker picker = new GroundPicker();
    private final Vector3f screen = new Vector3f();
    private int selected = -1;
    private boolean following;

    private static final float PANEL_WIDTH = 290f;

    /** Selects (or deselects) on a left click. */
    public void handleInput(Input input, Window window, Camera camera, World world) {
        if (!input.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            return;
        }
        Vector3f hit = picker.pick(input, window, camera, world.terrain());
        int picked = hit != null ? world.nearestCreature(hit.x, hit.z, PICK_RADIUS) : -1;
        if (picked != selected) {
            following = false;
        }
        selected = picked;
    }

    public void clear() {
        selected = -1;
        following = false;
    }

    /** True while the camera should follow the selected creature. */
    public boolean isFollowing() {
        return following && selected >= 0;
    }

    /** Selected creature, or -1 (also when it has died meanwhile). */
    public int selected(World world) {
        if (selected >= 0 && world.ecs().get(selected, SpeciesRef.class) == null) {
            clear(); // died
        }
        return selected;
    }

    /**
     * Lays out and draws the panel of the selected creature (right side, below the top bar).
     */
    public void buildPanel(Ui ui, World world, float top) {
        int entity = selected(world);
        if (entity < 0) {
            return;
        }
        AiState ai = world.ecs().get(entity, AiState.class);
        Needs needs = world.ecs().get(entity, Needs.class);
        Health health = world.ecs().get(entity, Health.class);
        Age age = world.ecs().get(entity, Age.class);
        Genome genome = world.ecs().get(entity, Genome.class);
        Reproduction reproduction = world.ecs().get(entity, Reproduction.class);
        SpeciesDefinition species = world.ecs().get(entity, SpeciesRef.class).species.stats();
        boolean adult = age.ageTicks >= SpeciesDefinition.secondsToTicks(species.reproduction().adultAgeSeconds());
        float minutesPerTick = 1f / Time.TICKS_PER_SECOND / 60f;

        float padding = 12f;
        float line = ui.regular.lineHeight() + 2f;
        float h = padding + ui.title.lineHeight() + line + 6f + 4 * (line + 2f) + 8f + 5 * line + padding;
        float x = ui.width() - PANEL_WIDTH - 10f;
        float y = top + 10f;
        ui.panel(x, y, PANEL_WIDTH, h);
        if (ui.button("×", x + PANEL_WIDTH - 30f, y + 6f, 24f, 24f, false)) {
            clear();
            return;
        }
        String followLabel = "Sledovat";
        float followWidth = ui.buttonWidth(followLabel);
        if (ui.button(followLabel, x + PANEL_WIDTH - 36f - followWidth, y + 6f, followWidth, 24f, following)) {
            following = !following;
        }
        float ty = y + padding;
        ui.text(ui.title, species.name() + " #" + entity, x + padding, ty, Ui.TEXT);
        ty += ui.title.lineHeight();
        String action = needs.sleeping ? Texts.action(ActionType.SLEEP) : Texts.action(ai != null ? ai.action : null);
        ui.text(ui.regular, action, x + padding, ty, Ui.TEXT_ACCENT);
        ty += line + 6f;

        float barX = x + padding + 70f;
        float barW = PANEL_WIDTH - 2 * padding - 70f - 40f;
        ty = need(ui, "Hlad", needs.hunger, 0xFFD9824A, x + padding, barX, barW, ty, line);
        ty = need(ui, "Žízeň", needs.thirst, 0xFF4A9AD9, x + padding, barX, barW, ty, line);
        ty = need(ui, "Energie", needs.energy, 0xFFD9C94A, x + padding, barX, barW, ty, line);
        ty = need(ui, "Zdraví", health.hp / Math.max(0.01f, health.maxHp), 0xFF5FBF6A, x + padding, barX, barW, ty, line);
        ty += 8f;
        ui.text(ui.regular, String.format(Locale.ROOT, "Věk %.1f z %.1f min (%s)", age.ageTicks * minutesPerTick,
                age.maxAgeTicks * minutesPerTick, adult ? "dospělý" : "mládě"), x + padding, ty, Ui.TEXT);
        ty += line;
        ui.text(ui.regular, String.format(Locale.ROOT, "Generace %d, potomků %d", genome.generation, reproduction.offspring),
                x + padding, ty, Ui.TEXT);
        ty += line;
        GroupMember member = world.ecs().get(entity, GroupMember.class);
        Groups.Group group = member != null ? world.groups().get(member.group) : null;
        String herd = group == null ? "Bez stáda"
                : String.format(Locale.ROOT, "Stádo #%d (%d bytostí), %s", group.id, group.size, group.leader == entity ? "vůdce" : "člen");
        ui.text(ui.regular, herd, x + padding, ty, group != null && group.leader == entity ? Ui.TEXT_ACCENT : Ui.TEXT);
        ty += line;
        ui.text(ui.regular, String.format(Locale.ROOT, "Geny: velikost %s, rychlost %s",
                Texts.percent(genome.size - 1f), Texts.percent(genome.speed - 1f)), x + padding, ty, Ui.TEXT_DIM);
        ty += line;
        ui.text(ui.small, "Klikni jinam pro zrušení výběru.", x + padding, ty + 2f, Ui.TEXT_DIM);
    }

    private static float need(Ui ui, String label, float value, int color, float x, float barX, float barW, float y, float line) {
        ui.text(ui.regular, label, x, y, Ui.TEXT_DIM);
        ui.bar(barX, y + 4f, barW, line - 8f, value, color);
        ui.text(ui.small, Math.round(Math.clamp(value, 0f, 1f) * 100f) + " %", barX + barW + 6f, y + 1f, Ui.TEXT_DIM);
        return y + line + 2f;
    }

    /** Adds a marker (downward arrow) above the selected creature to the UI; call while rendering. */
    public void renderMarker(Ui ui, Camera camera, World world, float alpha, int framebufferWidth, int framebufferHeight) {
        int entity = selected(world);
        if (entity < 0 || !projectHead(camera, world, entity, alpha, 0.5f, framebufferWidth, framebufferHeight)) {
            return;
        }
        float x = screen.x / ui.scale();
        float y = screen.y / ui.scale();
        for (int i = 0; i < 8; i++) { // triangle from horizontal strips
            float half = 8f - i;
            ui.draw().rect(x - half, y - 16f + i * 1.5f, half * 2f, 1.5f, 0xFFF2C75C);
        }
    }

    /** Projects a point above the creature's head to the screen (into {@code screen}); false if behind the camera. */
    private boolean projectHead(Camera camera, World world, int entity, float alpha, float extra, int framebufferWidth, int framebufferHeight) {
        Transform t = world.ecs().get(entity, Transform.class);
        PrevTransform p = world.ecs().get(entity, PrevTransform.class);
        float x = t.position.x;
        float y = t.position.y;
        float z = t.position.z;
        if (p != null) {
            x = p.position.x + (x - p.position.x) * alpha;
            y = p.position.y + (y - p.position.y) * alpha;
            z = p.position.z + (z - p.position.z) * alpha;
        }
        float headHeight = world.ecs().get(entity, SpeciesRef.class).species.stats().bodySize() + 0.3f + extra;
        return camera.project(x, y + headHeight, z, framebufferWidth, framebufferHeight, screen);
    }

    /** Draws the debug label (action and needs) above the selected creature's head. */
    public void renderLabel(DebugOverlay overlay, Camera camera, World world, float alpha, int framebufferWidth, int framebufferHeight) {
        int entity = selected(world);
        if (entity < 0) {
            return;
        }
        if (projectHead(camera, world, entity, alpha, 0.8f, framebufferWidth, framebufferHeight)) {
            overlay.renderLabel(describe(world, entity), screen.x, screen.y, framebufferWidth, framebufferHeight);
        }
    }

    /** Multi-line description of a creature: action, needs, health, age. */
    public static String describe(World world, int entity) {
        AiState ai = world.ecs().get(entity, AiState.class);
        Needs needs = world.ecs().get(entity, Needs.class);
        Health health = world.ecs().get(entity, Health.class);
        Age age = world.ecs().get(entity, Age.class);
        Genome genome = world.ecs().get(entity, Genome.class);
        Reproduction reproduction = world.ecs().get(entity, Reproduction.class);
        SpeciesDefinition species = world.ecs().get(entity, SpeciesRef.class).species.stats();
        boolean adult = age.ageTicks >= SpeciesDefinition.secondsToTicks(species.reproduction().adultAgeSeconds());
        String action = ai != null && ai.action != null ? ai.action.label() : "-";
        String path = ai != null && ai.pathStatus != AiState.PathStatus.NONE ? " (" + ai.pathStatus.name().toLowerCase(Locale.ROOT) + ")" : "";
        float minutesPerTick = 1f / Time.TICKS_PER_SECOND / 60f;
        return String.format(Locale.ROOT, "#%d %s%s%nhunger %.2f  thirst %.2f  energy %.2f%nhp %.2f  age %.1f / %.1f min (%s)%ngen %d  offspring %d  size %.2f  speed %.2f",
                entity, action, path,
                needs.hunger, needs.thirst, needs.energy,
                health.hp, age.ageTicks * minutesPerTick, age.maxAgeTicks * minutesPerTick, adult ? "adult" : "young",
                genome.generation, reproduction.offspring, genome.size, genome.speed);
    }
}

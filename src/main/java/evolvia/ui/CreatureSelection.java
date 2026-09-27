package evolvia.ui;

import evolvia.components.Age;
import evolvia.components.AiState;
import evolvia.components.Health;
import evolvia.components.Needs;
import evolvia.components.PrevTransform;
import evolvia.components.SpeciesRef;
import evolvia.components.Transform;
import evolvia.core.Input;
import evolvia.core.Time;
import evolvia.core.Window;
import evolvia.render.Camera;
import evolvia.world.Terrain;
import evolvia.world.World;
import org.joml.Vector3f;

import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * Debug selection of one creature: left click picks the creature nearest to the clicked ground
 * point; its current action and needs are shown above its head. UI state only, not simulation.
 */
public final class CreatureSelection {

    /** How far from the clicked ground point a creature may be to get selected (tiles). */
    private static final float PICK_RADIUS = 2.5f;
    private static final float RAY_STEP = 0.5f;
    private static final float RAY_LENGTH = 3000f;

    private final Vector3f origin = new Vector3f();
    private final Vector3f direction = new Vector3f();
    private final Vector3f screen = new Vector3f();
    private int selected = -1;

    /** Selects (or deselects) on a left click. */
    public void handleInput(Input input, Window window, Camera camera, World world) {
        if (!input.isButtonPressed(GLFW_MOUSE_BUTTON_LEFT)) {
            return;
        }
        camera.pickRay(input.mouseX(), input.mouseY(), window.width(), window.height(), origin, direction);
        Vector3f hit = groundHit(world.terrain());
        selected = hit != null ? world.nearestCreature(hit.x, hit.z, PICK_RADIUS) : -1;
    }

    public void clear() {
        selected = -1;
    }

    /** Selected creature, or -1 (also when it has died meanwhile). */
    public int selected(World world) {
        if (selected >= 0 && world.ecs().get(selected, SpeciesRef.class) == null) {
            selected = -1;
        }
        return selected;
    }

    /** Draws the action and needs label above the selected creature's head. */
    public void renderLabel(DebugOverlay overlay, Camera camera, World world, float alpha, int framebufferWidth, int framebufferHeight) {
        int entity = selected(world);
        if (entity < 0) {
            return;
        }
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
        float headHeight = world.ecs().get(entity, SpeciesRef.class).species.bodySize() + 0.3f;
        if (camera.project(x, y + headHeight, z, framebufferWidth, framebufferHeight, screen)) {
            overlay.renderLabel(describe(world, entity), screen.x, screen.y, framebufferWidth, framebufferHeight);
        }
    }

    /** Multi-line description of a creature: action, needs, health, age. */
    public static String describe(World world, int entity) {
        AiState ai = world.ecs().get(entity, AiState.class);
        Needs needs = world.ecs().get(entity, Needs.class);
        Health health = world.ecs().get(entity, Health.class);
        Age age = world.ecs().get(entity, Age.class);
        String action = ai != null && ai.action != null ? ai.action.label() : "-";
        String path = ai != null && ai.pathStatus != AiState.PathStatus.NONE ? " (" + ai.pathStatus.name().toLowerCase(Locale.ROOT) + ")" : "";
        float minutesPerTick = 1f / Time.TICKS_PER_SECOND / 60f;
        return String.format(Locale.ROOT, "#%d %s%s%nhunger %.2f  thirst %.2f  energy %.2f%nhp %.2f  age %.1f / %.1f min",
                entity, action, path,
                needs.hunger, needs.thirst, needs.energy,
                health.hp, age.ageTicks * minutesPerTick, age.maxAgeTicks * minutesPerTick);
    }

    /** First point where the ray hits the terrain or the water surface, or null. */
    private Vector3f groundHit(Terrain terrain) {
        Vector3f point = new Vector3f();
        float previous = 0f;
        for (float distance = 0f; distance < RAY_LENGTH; distance += RAY_STEP) {
            origin.fma(distance, direction, point);
            if (point.y <= surface(terrain, point.x, point.z)) {
                // Refine between the last point above and this one.
                float low = previous;
                float high = distance;
                for (int i = 0; i < 8; i++) {
                    float mid = (low + high) * 0.5f;
                    origin.fma(mid, direction, point);
                    if (point.y <= surface(terrain, point.x, point.z)) {
                        high = mid;
                    } else {
                        low = mid;
                    }
                }
                origin.fma(high, direction, point);
                boolean onMap = point.x >= 0 && point.z >= 0 && point.x <= terrain.width() && point.z <= terrain.depth();
                return onMap ? point : null;
            }
            previous = distance;
        }
        return null;
    }

    private static float surface(Terrain terrain, float x, float z) {
        return Math.max(terrain.heightAt(x, z), terrain.seaLevel());
    }
}

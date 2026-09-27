package evolvia.ui;

import evolvia.core.Input;
import evolvia.evolution.Effect;
import evolvia.evolution.EvolutionNode;
import evolvia.evolution.Species;
import evolvia.evolution.SpeciesDefinition;
import evolvia.world.World;

import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;

/**
 * Debug evolution panel (F4) until the real tree UI in phase 6: lists all nodes with their status,
 * Up/Down selects, Enter unlocks. Shows evolution points and the species' current stats.
 */
public final class EvolutionPanel {

    private static final float MARGIN_PX = 12f;
    /** Height of one text line in framebuffer pixels (debug font, scale 2). */
    private static final float LINE_PX = 24f;
    private static final float COLUMN_GAP_PX = 40f;

    private boolean visible;
    private int selected;
    private String message = "";

    public boolean isVisible() {
        return visible;
    }

    public void toggle() {
        visible = !visible;
    }

    public void handleInput(Input input, World world) {
        if (!visible) {
            return;
        }
        List<EvolutionNode> nodes = world.species().tree().nodes();
        if (nodes.isEmpty()) {
            return;
        }
        if (input.isKeyPressed(GLFW_KEY_DOWN)) {
            selected = (selected + 1) % nodes.size();
        }
        if (input.isKeyPressed(GLFW_KEY_UP)) {
            selected = (selected - 1 + nodes.size()) % nodes.size();
        }
        selected = Math.min(selected, nodes.size() - 1);
        if (input.isKeyPressed(GLFW_KEY_ENTER) || input.isKeyPressed(GLFW_KEY_KP_ENTER)) {
            EvolutionNode node = nodes.get(selected);
            try {
                world.unlock(node.id());
                message = "Odemceno: " + node.name();
            } catch (IllegalStateException e) {
                Species.Availability availability = world.species().availability(node, world);
                message = "Nelze odemknout " + node.name()
                        + (availability.reason() != null ? " - " + availability.reason() : "");
            }
        }
    }

    /**
     * Layout: header, then the branches in two columns (first half of the branches left), then details
     * of the selected node and the species' current stats - each on a dark panel.
     */
    public void render(DebugOverlay overlay, World world, int framebufferWidth, int framebufferHeight) {
        if (!visible) {
            return;
        }
        Species species = world.species();
        String header = String.format(Locale.ROOT, "EVOLUCE   EP %.0f  (+%.1f / min, celkem %.0f)%n"
                        + "Up/Down vybrat | Enter odemknout | F7 +100 EP | F4 zavrit",
                species.points(), world.evolutionSystem().pointsPerMinute(), species.pointsEarned());
        overlay.renderPanel(header, MARGIN_PX, MARGIN_PX, framebufferWidth, framebufferHeight);

        List<String> branches = species.tree().branches();
        int split = (branches.size() + 1) / 2;
        String left = column(species, world, branches.subList(0, split));
        String right = column(species, world, branches.subList(split, branches.size()));
        float top = MARGIN_PX + 2 * LINE_PX + 20;
        overlay.renderPanel(left, MARGIN_PX, top, framebufferWidth, framebufferHeight);
        overlay.renderPanel(right, MARGIN_PX + overlay.textWidth(left) + COLUMN_GAP_PX, top, framebufferWidth, framebufferHeight);

        int rows = Math.max(lineCount(left), lineCount(right));
        overlay.renderPanel(details(species, world), MARGIN_PX, top + rows * LINE_PX + 20, framebufferWidth, framebufferHeight);
    }

    private String column(Species species, World world, List<String> branches) {
        StringBuilder sb = new StringBuilder();
        List<EvolutionNode> nodes = species.tree().nodes();
        for (String branch : branches) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(branchName(branch));
            for (int i = 0; i < nodes.size(); i++) {
                EvolutionNode node = nodes.get(i);
                if (!node.branch().equals(branch)) {
                    continue;
                }
                sb.append('\n').append(i == selected ? "> " : "  ")
                        .append(marker(species.availability(node, world).status()))
                        .append(' ').append(node.name())
                        .append(String.format(Locale.ROOT, " (%d)", node.cost()));
            }
        }
        return sb.toString();
    }

    private String details(Species species, World world) {
        List<EvolutionNode> nodes = species.tree().nodes();
        StringBuilder sb = new StringBuilder();
        if (!nodes.isEmpty()) {
            EvolutionNode node = nodes.get(Math.min(selected, nodes.size() - 1));
            Species.Availability availability = species.availability(node, world);
            sb.append(node.name()).append(String.format(Locale.ROOT, " (%d EP): ", node.cost())).append(node.description()).append('\n');
            sb.append("  ").append(effects(node));
            if (availability.reason() != null) {
                sb.append('\n').append("  ").append(availability.status() == Species.NodeStatus.EXCLUDED ? "Vylouceno: " : "Zamceno: ")
                        .append(availability.reason());
            }
            sb.append('\n');
        }
        if (!message.isEmpty()) {
            sb.append(message).append('\n');
        }
        sb.append(stats(species));
        return sb.toString();
    }

    private static int lineCount(String text) {
        return (int) text.chars().filter(ch -> ch == '\n').count() + 1;
    }

    private static String stats(Species species) {
        SpeciesDefinition s = species.stats();
        return String.format(Locale.ROOT,
                "Druh: rychlost %.2f | dohled %.0f | velikost %.2f | zdravi %.2f%n"
                        + "Strava: rostliny %.2f, maso %.2f | pohodli %.2f-%.2f | schopnosti %s",
                s.speed(), s.senseRadius(), s.bodySize(), s.maxHealth(),
                s.diet().plantNutrition(), s.diet().meatNutrition(),
                s.climate().comfortMin(), s.climate().comfortMax(),
                species.abilities().isEmpty() ? "-" : String.join(", ", species.abilities()));
    }

    private static String effects(EvolutionNode node) {
        StringBuilder sb = new StringBuilder();
        for (Effect effect : node.effects()) {
            String part = switch (effect) {
                case Effect.StatAdd add -> String.format(Locale.ROOT, "%s %+.2f", add.stat().key(), add.value());
                case Effect.StatMul mul -> String.format(Locale.ROOT, "%s x%.2f", mul.stat().key(), mul.value());
                case Effect.UnlockAbility ability -> "schopnost " + ability.ability();
                case Effect.UnlockAction action -> "akce " + action.action();
                case Effect.Visual visual -> null; // not visible yet (phase 6)
            };
            if (part != null) {
                sb.append(sb.isEmpty() ? "" : ", ").append(part);
            }
        }
        return sb.toString();
    }

    private static String marker(Species.NodeStatus status) {
        return switch (status) {
            case UNLOCKED -> "[x]";
            case AVAILABLE -> "[+]";
            case LOCKED -> "[ ]";
            case EXCLUDED -> "[/]";
        };
    }

    private static String branchName(String branch) {
        return switch (branch) {
            case "body" -> "TELO";
            case "diet" -> "POTRAVA";
            case "adaptation" -> "ADAPTACE";
            case "mind" -> "MYSL";
            default -> branch.toUpperCase(Locale.ROOT);
        };
    }
}

package evolvia.render;

import evolvia.evolution.Effect;
import evolvia.evolution.EvolutionNode;
import evolvia.evolution.EvolutionTree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a species' creature mesh from primitives (DESIGN.md §7.4): body, head, snout, eyes, ears,
 * four legs, tail, plus the visual parts chosen by evolution ({@code visual} effects: part -> variant).
 * Built once per species and again when its unlocked nodes change; creatures are drawn instanced.
 * <p>
 * Model space: 1 unit = body size, origin on the ground under the body centre, +Z = forward.
 * Legs (and webbed feet, fur on the legs) swing around the hip while walking. No OpenGL here.
 */
public final class CreatureMeshBuilder {

    /** Supported variants per visual part; the first one is the default when evolution has not chosen one. */
    private static final Map<String, List<String>> VARIANTS = new LinkedHashMap<>();

    static {
        VARIANTS.put("legs", List.of("normal", "strong", "long"));
        VARIANTS.put("body", List.of("normal", "large"));
        VARIANTS.put("skin", List.of("normal", "thick", "sandy", "moist", "bare"));
        VARIANTS.put("fur", List.of("none", "thick", "white"));
        VARIANTS.put("feet", List.of("normal", "webbed"));
        VARIANTS.put("teeth", List.of("none", "flat", "mixed", "sharp"));
        VARIANTS.put("eyes", List.of("normal", "big"));
        VARIANTS.put("ears", List.of("normal", "alert"));
        VARIANTS.put("head", List.of("normal", "large"));
        VARIANTS.put("belly", List.of("normal", "round"));
    }

    private static final float[] EYE = {0.07f, 0.06f, 0.05f};
    private static final float[] WHITE = {0.93f, 0.92f, 0.86f};
    private static final float[] SNOW = {0.93f, 0.94f, 0.96f};
    private static final float[] SAND = {0.87f, 0.75f, 0.52f};
    private static final float[] MOIST = {0.33f, 0.58f, 0.50f};
    private static final float[] BARE = {0.93f, 0.66f, 0.60f};

    private CreatureMeshBuilder() {
    }

    /** Whether this part / variant combination can be drawn. */
    public static boolean supports(String part, String variant) {
        List<String> variants = VARIANTS.get(part);
        return variants != null && variants.contains(variant);
    }

    public static Set<String> parts() {
        return VARIANTS.keySet();
    }

    /**
     * Checks that every {@code visual} effect in the tree can be drawn.
     *
     * @throws IllegalArgumentException listing all unsupported parts / variants
     */
    public static void validate(EvolutionTree tree) {
        List<String> problems = new ArrayList<>();
        for (EvolutionNode node : tree.nodes()) {
            for (Effect effect : node.effects()) {
                if (effect instanceof Effect.Visual visual && !supports(visual.part(), visual.variant())) {
                    problems.add(node.id() + ": " + visual.part() + " / " + visual.variant());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Unsupported visual parts in the evolution tree (supported: "
                    + VARIANTS + "): " + String.join(", ", problems));
        }
    }

    /**
     * @param bodyRgb species color as 0xRRGGBB
     * @param visuals part -> variant chosen by evolution; missing parts use their default
     */
    public static MeshData build(int bodyRgb, Map<String, String> visuals) {
        for (Map.Entry<String, String> entry : visuals.entrySet()) {
            if (!supports(entry.getKey(), entry.getValue())) {
                throw new IllegalArgumentException("Unsupported visual part " + entry.getKey() + " / " + entry.getValue());
            }
        }
        String legs = variant(visuals, "legs");
        String bodyType = variant(visuals, "body");
        String skinType = variant(visuals, "skin");
        String fur = variant(visuals, "fur");
        String headType = variant(visuals, "head");

        float[] skin = skinColor(rgb(bodyRgb), skinType);
        PartMeshBuilder b = new PartMeshBuilder();

        // Proportions
        float legLength = switch (legs) {
            case "long" -> 0.42f;
            case "strong" -> 0.27f;
            default -> 0.24f;
        };
        float legWidth = legs.equals("strong") ? 0.15f : 0.10f;
        boolean large = bodyType.equals("large");
        float bodyW = large ? 0.48f : 0.40f;
        float bodyH = large ? 0.40f : 0.32f;
        float bodyL = large ? 0.72f : 0.64f;
        float bodyY = legLength + bodyH / 2f - 0.03f;
        float legX = bodyW / 2f - legWidth / 2f - 0.01f;
        float legZ = bodyL / 2f - legWidth / 2f - 0.05f;

        // Legs (diagonal pairs swing together)
        float[][] legPositions = {{legX, legZ, 1f}, {-legX, -legZ, 1f}, {-legX, legZ, -1f}, {legX, -legZ, -1f}};
        for (float[] leg : legPositions) {
            b.swing(legLength, leg[1], leg[2]).color(shade(skin, 0.82f))
                    .box(leg[0], (legLength + 0.04f) / 2f, leg[1], legWidth, legLength + 0.04f, legWidth);
            if (variant(visuals, "feet").equals("webbed")) {
                b.color(shade(mix(skin, MOIST, 0.4f), 0.7f))
                        .box(leg[0], 0.02f, leg[1] + 0.04f, legWidth * 1.9f, 0.04f, legWidth * 2.3f);
            }
            if (!fur.equals("none")) {
                b.color(furColor(skin, fur))
                        .box(leg[0], legLength * 0.78f, leg[1], legWidth + 0.05f, legLength * 0.5f, legWidth + 0.05f);
            }
        }
        b.rigid();

        // Body
        b.color(skin).box(0f, bodyY, 0f, bodyW, bodyH, bodyL);
        if (variant(visuals, "belly").equals("round")) {
            b.color(shade(skin, 1.08f)).box(0f, bodyY - bodyH / 2f - 0.02f, 0.02f, bodyW * 0.86f, 0.12f, bodyL * 0.66f);
        }
        switch (skinType) {
            case "thick" -> { // armour plates along the back
                for (int i = 0; i < 3; i++) {
                    float z = (i - 1) * bodyL * 0.28f;
                    b.color(shade(skin, 0.68f)).box(0f, bodyY + bodyH / 2f + 0.02f, z, bodyW * 0.72f, 0.06f, bodyL * 0.22f);
                }
            }
            case "moist" -> { // darker spots
                float[] spot = shade(skin, 0.62f);
                b.color(spot).box(bodyW * 0.18f, bodyY + bodyH / 2f + 0.005f, bodyL * 0.12f, 0.1f, 0.02f, 0.1f);
                b.color(spot).box(-bodyW * 0.2f, bodyY + bodyH / 2f + 0.005f, -bodyL * 0.2f, 0.08f, 0.02f, 0.08f);
                b.color(spot).box(bodyW / 2f + 0.005f, bodyY, -bodyL * 0.05f, 0.02f, 0.08f, 0.1f);
                b.color(spot).box(-bodyW / 2f - 0.005f, bodyY + 0.03f, bodyL * 0.1f, 0.02f, 0.08f, 0.08f);
            }
            default -> {
            }
        }
        if (!fur.equals("none")) {
            float[] coat = furColor(skin, fur);
            b.color(coat).box(0f, bodyY + 0.03f, -0.01f, bodyW + 0.08f, bodyH + 0.06f, bodyL * 0.94f);
            b.color(shade(coat, 0.9f)); // tufts on the back
            for (int i = 0; i < 3; i++) {
                b.box((i - 1) * 0.08f, bodyY + bodyH / 2f + 0.07f, -bodyL * 0.1f + i * 0.09f, 0.07f, 0.06f, 0.1f);
            }
        }

        // Tail
        b.color(shade(skin, 0.9f)).tiltedBox(0f, bodyY + bodyH * 0.12f, -bodyL / 2f - 0.11f, 0.07f, 0.07f, 0.26f, 0.45f);
        if (!fur.equals("none")) {
            b.color(furColor(skin, fur)).tiltedBox(0f, bodyY + bodyH * 0.02f, -bodyL / 2f - 0.2f, 0.12f, 0.12f, 0.16f, 0.45f);
        }

        // Head
        boolean bigHead = headType.equals("large");
        float headW = bigHead ? 0.34f : 0.28f;
        float headH = bigHead ? 0.34f : 0.28f;
        float headL = 0.28f;
        float headY = bodyY + bodyH * 0.3f + (bigHead ? 0.03f : 0f);
        float headZ = bodyL / 2f + headL * 0.3f;
        float front = headZ + headL / 2f;
        b.color(shade(skin, 1.04f)).box(0f, headY, headZ, headW, headH, headL);
        if (bigHead) { // high forehead
            b.color(shade(skin, 1.0f)).box(0f, headY + headH / 2f + 0.03f, headZ - 0.02f, headW * 0.8f, 0.07f, headL * 0.75f);
        }
        if (!fur.equals("none")) { // mane behind the head
            b.color(shade(furColor(skin, fur), 0.95f)).box(0f, headY - 0.01f, headZ - headL / 2f - 0.02f, headW + 0.1f, headH + 0.08f, 0.1f);
        }

        // Snout
        float snoutL = 0.12f;
        float snoutY = headY - headH * 0.22f;
        b.color(shade(skin, 0.92f)).box(0f, snoutY, front + snoutL / 2f, headW * 0.56f, headH * 0.42f, snoutL);
        float snoutFront = front + snoutL;
        float snoutBottom = snoutY - headH * 0.21f;
        switch (variant(visuals, "teeth")) {
            case "flat" -> b.color(WHITE).box(0f, snoutBottom - 0.015f, snoutFront - 0.03f, headW * 0.4f, 0.04f, 0.04f);
            case "mixed" -> {
                b.color(WHITE).box(0f, snoutBottom - 0.015f, snoutFront - 0.03f, headW * 0.36f, 0.035f, 0.04f);
                b.box(headW * 0.2f, snoutBottom - 0.03f, snoutFront - 0.025f, 0.03f, 0.07f, 0.03f);
                b.box(-headW * 0.2f, snoutBottom - 0.03f, snoutFront - 0.025f, 0.03f, 0.07f, 0.03f);
            }
            case "sharp" -> {
                b.color(WHITE).box(headW * 0.17f, snoutBottom - 0.05f, snoutFront - 0.025f, 0.04f, 0.13f, 0.04f);
                b.box(-headW * 0.17f, snoutBottom - 0.05f, snoutFront - 0.025f, 0.04f, 0.13f, 0.04f);
            }
            default -> {
            }
        }

        // Eyes on the front of the head, above the snout
        float eyeX = headW * 0.3f;
        float eyeY = headY + headH * 0.14f;
        if (variant(visuals, "eyes").equals("big")) {
            for (float side : new float[]{1f, -1f}) {
                b.color(WHITE).box(side * eyeX, eyeY, front + 0.01f, 0.11f, 0.11f, 0.03f);
                b.color(EYE).box(side * eyeX, eyeY - 0.005f, front + 0.03f, 0.06f, 0.07f, 0.02f);
            }
        } else {
            b.color(EYE).box(eyeX, eyeY, front + 0.01f, 0.06f, 0.06f, 0.03f);
            b.box(-eyeX, eyeY, front + 0.01f, 0.06f, 0.06f, 0.03f);
        }

        // Ears
        float earTop = headY + headH / 2f;
        if (variant(visuals, "ears").equals("alert")) {
            b.color(shade(skin, 0.95f));
            b.tiltedBox(headW * 0.3f, earTop + 0.09f, headZ - 0.03f, 0.07f, 0.2f, 0.04f, -0.25f);
            b.tiltedBox(-headW * 0.3f, earTop + 0.09f, headZ - 0.03f, 0.07f, 0.2f, 0.04f, -0.25f);
            b.color(shade(skin, 0.6f));
            b.tiltedBox(headW * 0.3f, earTop + 0.09f, headZ - 0.01f, 0.035f, 0.14f, 0.01f, -0.25f);
            b.tiltedBox(-headW * 0.3f, earTop + 0.09f, headZ - 0.01f, 0.035f, 0.14f, 0.01f, -0.25f);
        } else {
            b.color(shade(skin, 0.9f));
            b.box(headW * 0.32f, earTop + 0.03f, headZ - 0.04f, 0.06f, 0.07f, 0.04f);
            b.box(-headW * 0.32f, earTop + 0.03f, headZ - 0.04f, 0.06f, 0.07f, 0.04f);
        }
        return b.build();
    }

    /** Chosen variant of a part, or its default. */
    static String variant(Map<String, String> visuals, String part) {
        String chosen = visuals.get(part);
        return chosen != null ? chosen : VARIANTS.get(part).getFirst();
    }

    private static float[] skinColor(float[] base, String skin) {
        return switch (skin) {
            case "sandy" -> mix(base, SAND, 0.6f);
            case "moist" -> mix(base, MOIST, 0.55f);
            case "bare" -> mix(base, BARE, 0.45f);
            case "thick" -> shade(base, 0.9f);
            default -> base;
        };
    }

    private static float[] furColor(float[] skin, String fur) {
        return fur.equals("white") ? SNOW : shade(mix(skin, new float[]{0.45f, 0.32f, 0.2f}, 0.35f), 0.85f);
    }

    static float[] rgb(int rgb) {
        return new float[]{((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
    }

    private static float[] shade(float[] c, float factor) {
        return new float[]{Math.min(1f, c[0] * factor), Math.min(1f, c[1] * factor), Math.min(1f, c[2] * factor)};
    }

    private static float[] mix(float[] a, float[] b, float t) {
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }
}

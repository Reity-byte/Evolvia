package evolvia.render;

import evolvia.evolution.Effect;
import evolvia.evolution.EvolutionNode;
import evolvia.evolution.EvolutionTree;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a species' creature mesh from primitives (DESIGN.md §7.4, §11 phase 9b): torso, head, legs,
 * arms, tail, plus the visual parts chosen by evolution ({@code visual} effects: part -> variant).
 * Built once per species and again when its unlocked nodes change; creatures are drawn instanced.
 * <p>
 * The {@code posture} decides the skeleton: a four-legged animal, a half-upright one leaning on long
 * front limbs, or an upright two-legged one with free arms. The torso and everything on it (coat,
 * plates, spots, belly, tail) is built in the torso's own frame (x = side, y = back, z = along the
 * spine towards the head), which is simply tilted for each posture. The head always stays level.
 * <p>
 * Model space: 1 unit = body size, origin on the ground under the creature, +Z = forward.
 * Legs and arms swing around the hip / shoulder while walking. No OpenGL here.
 */
public final class CreatureMeshBuilder {

    /** Supported variants per visual part; the first one is the default when evolution has not chosen one. */
    private static final Map<String, List<String>> VARIANTS = new LinkedHashMap<>();

    static {
        VARIANTS.put("posture", List.of("quadruped", "semi", "upright"));
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
        VARIANTS.put("hands", List.of("paws", "nimble"));
        VARIANTS.put("face", List.of("muzzle", "human"));
        VARIANTS.put("horns", List.of("none", "antlers")); // wild game (phase 9f)
    }

    private static final float[] EYE = {0.07f, 0.06f, 0.05f};
    private static final float[] WHITE = {0.93f, 0.92f, 0.86f};
    private static final float[] SNOW = {0.93f, 0.94f, 0.96f};
    private static final float[] SAND = {0.87f, 0.75f, 0.52f};
    private static final float[] MOIST = {0.33f, 0.58f, 0.50f};
    private static final float[] BARE = {0.93f, 0.66f, 0.60f};
    private static final float[] ANTLER = {0.86f, 0.78f, 0.62f};
    private static final float SEMI_TILT = (float) Math.toRadians(35);
    private static final float TAIL_DROOP = 0.45f;

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

    /** Height of the creature's model in body sizes (top of the head or ears). */
    public static float height(MeshData mesh) {
        float max = 0f;
        int floats = mesh.floatsPerVertex();
        for (int v = 0; v < mesh.vertexCount(); v++) {
            max = Math.max(max, mesh.vertices()[v * floats + 1]);
        }
        return max;
    }

    /** The chosen look: variants and colors. */
    private record Look(String posture, String legs, String body, String skinType, String fur, String feet,
                        String teeth, String eyes, String ears, String head, String belly, String hands, String face,
                        String horns, float[] skin, float[] coat) {

        static Look of(int bodyRgb, Map<String, String> visuals) {
            String skinType = variant(visuals, "skin");
            String fur = variant(visuals, "fur");
            float[] skin = skinColor(rgb(bodyRgb), skinType);
            return new Look(variant(visuals, "posture"), variant(visuals, "legs"), variant(visuals, "body"), skinType, fur,
                    variant(visuals, "feet"), variant(visuals, "teeth"), variant(visuals, "eyes"), variant(visuals, "ears"),
                    variant(visuals, "head"), variant(visuals, "belly"), variant(visuals, "hands"), variant(visuals, "face"),
                    variant(visuals, "horns"), skin, furColor(skin, fur));
        }

        boolean furry() {
            return !fur.equals("none");
        }

        boolean large() {
            return body.equals("large");
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
        Look look = Look.of(bodyRgb, visuals);
        PartMeshBuilder b = new PartMeshBuilder();
        switch (look.posture()) {
            case "semi" -> semi(b, look);
            case "upright" -> upright(b, look);
            default -> quadruped(b, look);
        }
        return b.build();
    }

    // ---------------------------------------------------------------- postures

    private static void quadruped(PartMeshBuilder b, Look look) {
        float legLength = switch (look.legs()) {
            case "long" -> 0.42f;
            case "strong" -> 0.27f;
            default -> 0.24f;
        };
        float legWidth = look.legs().equals("strong") ? 0.15f : 0.10f;
        float w = look.large() ? 0.48f : 0.40f;
        float h = look.large() ? 0.40f : 0.32f;
        float l = look.large() ? 0.72f : 0.64f;
        float bodyY = legLength + h / 2f - 0.03f;
        float legX = w / 2f - legWidth / 2f - 0.01f;
        float legZ = l / 2f - legWidth / 2f - 0.05f;

        // Diagonal pairs swing together.
        float[][] legs = {{legX, legZ, 1f}, {-legX, -legZ, 1f}, {-legX, legZ, -1f}, {legX, -legZ, -1f}};
        for (float[] leg : legs) {
            leg(b, look, leg[0], leg[1], legLength, legWidth, leg[2], true);
        }
        b.rigid();

        Matrix4f frame = new Matrix4f().translation(0f, bodyY, 0f);
        torso(b, look, frame, w, h, l, 1f);
        head(b, look, frame.transformPosition(0f, h * 0.3f, l / 2f + 0.28f * 0.3f, new Vector3f()), look.face().equals("human"));
    }

    /** Half upright: tilted torso on the hind legs, leaning on long front limbs (knuckle walking). */
    private static void semi(PartMeshBuilder b, Look look) {
        float legLength = switch (look.legs()) {
            case "long" -> 0.52f;
            case "strong" -> 0.36f;
            default -> 0.33f;
        };
        float legWidth = look.legs().equals("strong") ? 0.15f : 0.11f;
        float w = look.large() ? 0.46f : 0.38f;
        float h = look.large() ? 0.38f : 0.30f;
        float l = look.large() ? 0.66f : 0.58f;
        float hipZ = -0.14f;
        Matrix4f frame = new Matrix4f().translation(0f, legLength, hipZ).rotateX(-SEMI_TILT).translate(0f, h * 0.1f, l / 2f - 0.06f);

        float legX = w / 2f - legWidth / 2f - 0.01f;
        Vector3f shoulder = frame.transformPosition(0f, -h * 0.15f, l / 2f - 0.08f, new Vector3f());
        float armWidth = legWidth * 0.9f;
        float armX = w / 2f + armWidth / 2f;
        // Hind legs and front limbs swing in diagonal pairs.
        leg(b, look, legX, hipZ, legLength, legWidth, 1f, true);
        leg(b, look, -legX, hipZ, legLength, legWidth, -1f, true);
        arm(b, look, armX, shoulder.y, shoulder.z + 0.03f, shoulder.y, armWidth, -1f);
        arm(b, look, -armX, shoulder.y, shoulder.z + 0.03f, shoulder.y, armWidth, 1f);
        b.rigid();

        torso(b, look, frame, w, h, l, 0.55f);
        head(b, look, frame.transformPosition(0f, h * 0.25f, l / 2f + 0.28f * 0.35f, new Vector3f()), look.face().equals("human"));
    }

    /** Upright on two legs, free arms, no tail. */
    private static void upright(PartMeshBuilder b, Look look) {
        float legLength = switch (look.legs()) {
            case "long" -> 0.62f;
            case "strong" -> 0.52f;
            default -> 0.50f;
        };
        float legWidth = look.legs().equals("strong") ? 0.14f : 0.11f;
        float w = look.large() ? 0.44f : 0.36f;
        float h = look.large() ? 0.30f : 0.24f;
        float l = look.large() ? 0.56f : 0.50f;
        float legX = w * 0.28f;
        leg(b, look, legX, 0f, legLength, legWidth, 1f, true);
        leg(b, look, -legX, 0f, legLength, legWidth, -1f, true);

        float shoulderY = legLength + l - 0.07f;
        float armWidth = look.legs().equals("strong") ? 0.10f : 0.09f;
        float armLength = 0.46f;
        float armX = w / 2f + armWidth / 2f + 0.005f;
        // Arms swing against the leg on the same side.
        arm(b, look, armX, shoulderY, 0f, armLength, armWidth, -1f);
        arm(b, look, -armX, shoulderY, 0f, armLength, armWidth, 1f);
        b.rigid();

        Matrix4f frame = new Matrix4f().translation(0f, legLength + l / 2f - 0.02f, 0f).rotateX((float) (-Math.PI / 2));
        torso(b, look, frame, w, h, l, 0f);
        float headH = look.head().equals("large") ? 0.34f : 0.28f;
        head(b, look, new Vector3f(0f, legLength + l + headH / 2f + 0.01f, 0.03f), look.face().equals("human"));
    }

    // ---------------------------------------------------------------- limbs

    /** A leg standing on the ground at (x, z), swinging around the hip. */
    private static void leg(PartMeshBuilder b, Look look, float x, float z, float length, float width, float swing, boolean feet) {
        b.swing(length, z, swing).color(shade(look.skin(), 0.82f))
                .box(x, (length + 0.04f) / 2f, z, width, length + 0.04f, width);
        if (feet && look.feet().equals("webbed")) {
            b.color(shade(mix(look.skin(), MOIST, 0.4f), 0.7f))
                    .box(x, 0.02f, z + 0.04f, width * 1.9f, 0.04f, width * 2.3f);
        }
        if (look.furry()) {
            b.color(look.coat()).box(x, length * 0.78f, z, width + 0.05f, length * 0.5f, width + 0.05f);
        }
    }

    /** An arm hanging from the shoulder at (x, shoulderY, z), swinging around the shoulder. */
    private static void arm(PartMeshBuilder b, Look look, float x, float shoulderY, float z, float length, float width, float swing) {
        float bottom = shoulderY - length;
        b.swing(shoulderY, z, swing).color(shade(look.skin(), 0.86f))
                .box(x, bottom + (length + 0.04f) / 2f, z, width, length + 0.04f, width);
        if (look.furry()) {
            b.color(look.coat()).box(x, shoulderY - length * 0.25f, z, width + 0.05f, length * 0.5f, width + 0.05f);
        }
        if (look.hands().equals("nimble")) {
            float[] palm = shade(look.skin(), 0.95f);
            b.color(palm).box(x, bottom + 0.02f, z + 0.01f, width * 1.15f, 0.1f, width * 1.3f);
            b.color(shade(palm, 0.9f)).box(x - Math.signum(x) * width * 0.55f, bottom + 0.05f, z + width * 0.5f, 0.035f, 0.06f, 0.035f);
        }
    }

    // ---------------------------------------------------------------- torso and head

    /**
     * The torso and everything on it, in the torso's frame (x side, y back, z spine towards the head).
     *
     * @param tail length factor of the tail (0 = none)
     */
    private static void torso(PartMeshBuilder b, Look look, Matrix4f frame, float w, float h, float l, float tail) {
        float[] skin = look.skin();
        local(b.color(skin), frame, 0f, 0f, 0f, w, h, l);
        if (look.belly().equals("round")) {
            local(b.color(shade(skin, 1.08f)), frame, 0f, -h / 2f - 0.02f, 0.02f, w * 0.86f, 0.12f, l * 0.66f);
        }
        switch (look.skinType()) {
            case "thick" -> { // armour plates along the back
                for (int i = 0; i < 3; i++) {
                    local(b.color(shade(skin, 0.68f)), frame, 0f, h / 2f + 0.02f, (i - 1) * l * 0.28f, w * 0.72f, 0.06f, l * 0.22f);
                }
            }
            case "moist" -> { // darker spots
                b.color(shade(skin, 0.62f));
                local(b, frame, w * 0.18f, h / 2f + 0.005f, l * 0.12f, 0.1f, 0.02f, 0.1f);
                local(b, frame, -w * 0.2f, h / 2f + 0.005f, -l * 0.2f, 0.08f, 0.02f, 0.08f);
                local(b, frame, w / 2f + 0.005f, 0f, -l * 0.05f, 0.02f, 0.08f, 0.1f);
                local(b, frame, -w / 2f - 0.005f, 0.03f, l * 0.1f, 0.02f, 0.08f, 0.08f);
            }
            default -> {
            }
        }
        if (look.furry()) {
            local(b.color(look.coat()), frame, 0f, 0.03f, -0.01f, w + 0.08f, h + 0.06f, l * 0.94f);
            b.color(shade(look.coat(), 0.9f)); // tufts on the back
            for (int i = 0; i < 3; i++) {
                local(b, frame, (i - 1) * 0.08f, h / 2f + 0.07f, -l * 0.1f + i * 0.09f, 0.07f, 0.06f, 0.1f);
            }
        }
        if (tail > 0f) {
            b.color(shade(skin, 0.9f)).box(new Matrix4f(frame).translate(0f, h * 0.12f, -l / 2f - 0.11f * tail)
                    .rotateX(TAIL_DROOP).scale(0.07f, 0.07f, 0.26f * tail));
            if (look.furry()) {
                b.color(look.coat()).box(new Matrix4f(frame).translate(0f, h * 0.02f, -l / 2f - 0.2f * tail)
                        .rotateX(TAIL_DROOP).scale(0.12f, 0.12f, 0.16f * tail));
            }
        }
    }

    /** A box in a frame: centre and size in the frame's coordinates. */
    private static void local(PartMeshBuilder b, Matrix4f frame, float x, float y, float z, float sx, float sy, float sz) {
        b.box(new Matrix4f(frame).translate(x, y, z).scale(sx, sy, sz));
    }

    /** Level head centred at {@code c}, facing +Z: a muzzle with snout, or a flat human face. */
    private static void head(PartMeshBuilder b, Look look, Vector3f c, boolean human) {
        float[] skin = look.skin();
        boolean bigHead = look.head().equals("large");
        float headW = bigHead ? 0.34f : 0.28f;
        float headH = bigHead ? 0.34f : 0.28f;
        float headL = human ? 0.26f : 0.28f;
        float headY = c.y + (bigHead ? 0.03f : 0f);
        float headZ = c.z;
        float front = headZ + headL / 2f;
        b.color(shade(skin, 1.04f)).box(0f, headY, headZ, headW, headH, headL);
        if (bigHead) { // high forehead
            b.color(shade(skin, 1.0f)).box(0f, headY + headH / 2f + 0.03f, headZ - 0.02f, headW * 0.8f, 0.07f, headL * 0.75f);
        }
        if (look.furry()) { // mane behind the head
            b.color(shade(look.coat(), 0.95f)).box(0f, headY - 0.01f, headZ - headL / 2f - 0.02f, headW + 0.1f, headH + 0.08f, 0.1f);
        }
        if (human) {
            humanFace(b, look, headW, headH, headY, headZ, front);
        } else {
            muzzle(b, look, headW, headH, headY, front);
            animalEars(b, look, headW, headH, headY, headZ);
            if (look.horns().equals("antlers")) {
                antlers(b, headW, headY + headH / 2f, headZ);
            }
        }
        eyes(b, look, headW, headY + headH * (human ? 0.1f : 0.14f), front);
    }

    private static void muzzle(PartMeshBuilder b, Look look, float headW, float headH, float headY, float front) {
        float snoutL = 0.12f;
        float snoutY = headY - headH * 0.22f;
        b.color(shade(look.skin(), 0.92f)).box(0f, snoutY, front + snoutL / 2f, headW * 0.56f, headH * 0.42f, snoutL);
        float snoutFront = front + snoutL;
        float snoutBottom = snoutY - headH * 0.21f;
        switch (look.teeth()) {
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
    }

    /** Flat face: nose, mouth, small ears on the sides; sharp teeth stay as small canines. */
    private static void humanFace(PartMeshBuilder b, Look look, float headW, float headH, float headY, float headZ, float front) {
        float[] skin = look.skin();
        b.color(shade(skin, 0.95f)).box(0f, headY - headH * 0.06f, front + 0.025f, 0.055f, 0.09f, 0.05f);
        float mouthY = headY - headH * 0.3f;
        b.color(shade(skin, 0.55f)).box(0f, mouthY, front + 0.004f, headW * 0.36f, 0.022f, 0.01f);
        if (look.teeth().equals("sharp")) {
            b.color(WHITE).box(headW * 0.12f, mouthY - 0.02f, front + 0.008f, 0.025f, 0.04f, 0.01f);
            b.box(-headW * 0.12f, mouthY - 0.02f, front + 0.008f, 0.025f, 0.04f, 0.01f);
        }
        boolean alert = look.ears().equals("alert");
        float earH = alert ? 0.14f : 0.09f;
        b.color(shade(skin, 0.92f));
        b.box(headW / 2f + 0.015f, headY + 0.01f + (alert ? 0.03f : 0f), headZ, 0.03f, earH, 0.07f);
        b.box(-headW / 2f - 0.015f, headY + 0.01f + (alert ? 0.03f : 0f), headZ, 0.03f, earH, 0.07f);
    }

    /** Branching antlers on top of the head (deer). */
    private static void antlers(PartMeshBuilder b, float headW, float top, float headZ) {
        b.color(ANTLER);
        for (float side : new float[]{1f, -1f}) {
            float x = side * headW * 0.22f;
            b.tiltedBox(x, top + 0.14f, headZ - 0.02f, 0.035f, 0.28f, 0.035f, -0.2f);
            b.box(x + side * 0.07f, top + 0.26f, headZ - 0.06f, 0.14f, 0.03f, 0.03f);
            b.box(x + side * 0.12f, top + 0.33f, headZ - 0.06f, 0.03f, 0.14f, 0.03f);
            b.box(x, top + 0.34f, headZ - 0.1f, 0.03f, 0.12f, 0.03f);
        }
    }

    private static void animalEars(PartMeshBuilder b, Look look, float headW, float headH, float headY, float headZ) {
        float[] skin = look.skin();
        float earTop = headY + headH / 2f;
        if (look.ears().equals("alert")) {
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
    }

    private static void eyes(PartMeshBuilder b, Look look, float headW, float eyeY, float front) {
        float eyeX = headW * 0.3f;
        if (look.eyes().equals("big")) {
            for (float side : new float[]{1f, -1f}) {
                b.color(WHITE).box(side * eyeX, eyeY, front + 0.01f, 0.11f, 0.11f, 0.03f);
                b.color(EYE).box(side * eyeX, eyeY - 0.005f, front + 0.03f, 0.06f, 0.07f, 0.02f);
            }
        } else {
            b.color(EYE).box(eyeX, eyeY, front + 0.01f, 0.06f, 0.06f, 0.03f);
            b.box(-eyeX, eyeY, front + 0.01f, 0.06f, 0.06f, 0.03f);
        }
    }

    // ---------------------------------------------------------------- colors

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

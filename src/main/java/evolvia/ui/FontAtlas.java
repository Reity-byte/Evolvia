package evolvia.ui;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.stb.STBTruetype.*;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * All UI fonts packed into one single-channel texture with stb_truetype, so the whole UI is one draw
 * call. Covers ASCII, Latin-1 and Latin Extended-A (Czech diacritics) plus common punctuation
 * (dashes, quotes, ellipsis, minus). Baked at the display's pixel density; {@link #ensureScale}
 * rebakes when the window moves to a screen with a different density.
 */
public final class FontAtlas implements AutoCloseable {

    /** Unicode ranges [from, to) to bake. */
    private static final int[][] RANGES = {{32, 127}, {160, 384}, {8211, 8213}, {8216, 8231}, {8722, 8723}};
    private static final int MAX_CODEPOINT = 8723;
    private static final int MAX_SIDE = 4096;

    private final List<Font> fonts = new ArrayList<>();
    private final Map<String, ByteBuffer> fontFiles = new HashMap<>();
    private int texture;
    private float bakedScale;

    /** Registers a font (a TTF on the classpath at a size in UI units); baked on the next {@link #ensureScale}. */
    public Font add(String resource, float size) {
        Font font = new Font(resource, size);
        fonts.add(font);
        fontFiles.computeIfAbsent(resource, FontAtlas::readResource);
        bakedScale = 0f;
        return font;
    }

    /** Rebakes the atlas if the pixel scale (pixels per UI unit) changed. Needs the OpenGL context. */
    public void ensureScale(float pixelScale) {
        if (pixelScale == bakedScale && texture != 0) {
            return;
        }
        Bitmap bitmap = bake(pixelScale);
        if (texture == 0) {
            texture = glGenTextures();
        }
        glBindTexture(GL_TEXTURE_2D, texture);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, bitmap.width, bitmap.height, 0, GL_RED, GL_UNSIGNED_BYTE, bitmap.pixels);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        memFree(bitmap.pixels);
        bakedScale = pixelScale;
    }

    public int texture() {
        return texture;
    }

    record Bitmap(ByteBuffer pixels, int width, int height) {
    }

    /**
     * Packs all fonts into a bitmap (no OpenGL) and updates their glyph metrics. The caller frees
     * the returned pixels with {@code MemoryUtil.memFree}.
     */
    Bitmap bake(float pixelScale) {
        int width = pixelScale <= 1.25f ? 1024 : 2048;
        for (int height = width / 2; height <= MAX_SIDE; height *= 2) {
            Bitmap bitmap = tryBake(pixelScale, width, height);
            if (bitmap != null) {
                return bitmap;
            }
        }
        throw new IllegalStateException("UI fonts do not fit into a " + width + "x" + MAX_SIDE + " atlas");
    }

    private Bitmap tryBake(float pixelScale, int width, int height) {
        ByteBuffer pixels = memAlloc(width * height);
        List<Font.Glyph[]> results = new ArrayList<>();
        try (STBTTPackContext context = STBTTPackContext.malloc()) {
            if (!stbtt_PackBegin(context, pixels, width, height, 0, 1)) {
                throw new IllegalStateException("stbtt_PackBegin failed");
            }
            // Horizontal oversampling keeps small text smooth on low-density screens.
            int oversample = pixelScale < 1.5f ? 2 : 1;
            stbtt_PackSetOversampling(context, oversample, 1);
            boolean fits = true;
            for (Font font : fonts) {
                Font.Glyph[] glyphs = new Font.Glyph[MAX_CODEPOINT];
                ByteBuffer file = fontFiles.get(font.resource());
                for (int[] range : RANGES) {
                    int count = range[1] - range[0];
                    try (STBTTPackedchar.Buffer chars = STBTTPackedchar.malloc(count)) {
                        if (!stbtt_PackFontRange(context, file, 0, font.size() * pixelScale, range[0], chars)) {
                            fits = false;
                            break;
                        }
                        for (int i = 0; i < count; i++) {
                            STBTTPackedchar c = chars.get(i);
                            glyphs[range[0] + i] = new Font.Glyph(
                                    c.xoff() / pixelScale, c.yoff() / pixelScale, c.xoff2() / pixelScale, c.yoff2() / pixelScale,
                                    c.x0() / (float) width, c.y0() / (float) height, c.x1() / (float) width, c.y1() / (float) height,
                                    c.xadvance() / pixelScale);
                        }
                    }
                }
                if (!fits) {
                    break;
                }
                results.add(glyphs);
            }
            stbtt_PackEnd(context);
            if (!fits) {
                memFree(pixels);
                return null;
            }
        }
        for (int i = 0; i < fonts.size(); i++) {
            Font font = fonts.get(i);
            float[] metrics = verticalMetrics(fontFiles.get(font.resource()), font.size());
            font.setMetrics(results.get(i), metrics[0], metrics[1]);
        }
        return new Bitmap(pixels, width, height);
    }

    /** Ascent and line height in units for a font at {@code size}. */
    private static float[] verticalMetrics(ByteBuffer file, float size) {
        try (MemoryStack stack = MemoryStack.stackPush(); STBTTFontinfo info = STBTTFontinfo.malloc()) {
            if (!stbtt_InitFont(info, file)) {
                throw new IllegalStateException("Invalid font file");
            }
            IntBuffer ascent = stack.mallocInt(1);
            IntBuffer descent = stack.mallocInt(1);
            IntBuffer lineGap = stack.mallocInt(1);
            stbtt_GetFontVMetrics(info, ascent, descent, lineGap);
            float scale = stbtt_ScaleForPixelHeight(info, size);
            return new float[]{ascent.get(0) * scale, (ascent.get(0) - descent.get(0) + lineGap.get(0)) * scale};
        }
    }

    /** Reads a classpath resource into a direct buffer (stb_truetype needs off-heap memory). */
    private static ByteBuffer readResource(String path) {
        try (InputStream in = FontAtlas.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Font resource not found: " + path);
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = memAlloc(bytes.length);
            buffer.put(bytes).flip();
            return buffer;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read font resource: " + path, e);
        }
    }

    @Override
    public void close() {
        if (texture != 0) {
            glDeleteTextures(texture);
            texture = 0;
        }
        for (ByteBuffer file : fontFiles.values()) {
            memFree(file);
        }
        fontFiles.clear();
    }
}

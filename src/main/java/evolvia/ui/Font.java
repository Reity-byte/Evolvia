package evolvia.ui;

/**
 * One font face at one size, baked into the shared glyph atlas by {@link FontAtlas}. Metrics are in
 * UI units (1 unit = 1 screen point, see {@link Ui}); the atlas itself is baked at the display's
 * pixel density so text stays sharp on HiDPI screens.
 */
public final class Font {

    /** Glyph quad relative to the pen position on the baseline (units) and its atlas texture coordinates. */
    record Glyph(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float advance) {
    }

    private final String resource;
    private final float size;
    private Glyph[] glyphs = new Glyph[0];
    private Glyph fallback;
    private float ascent;
    private float lineHeight;

    Font(String resource, float size) {
        this.resource = resource;
        this.size = size;
    }

    String resource() {
        return resource;
    }

    /** Nominal size in units (pixel height of the font's ascent - descent). */
    public float size() {
        return size;
    }

    /** Distance from the top of a line to its baseline. */
    public float ascent() {
        return ascent;
    }

    /** Recommended distance between baselines of consecutive lines. */
    public float lineHeight() {
        return lineHeight;
    }

    /** Width of a single line of text. */
    public float width(String text) {
        float width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += glyph(text.charAt(i)).advance();
        }
        return width;
    }

    Glyph glyph(char ch) {
        Glyph glyph = ch < glyphs.length ? glyphs[ch] : null;
        return glyph != null ? glyph : fallback;
    }

    /** Called by the atlas after (re)baking. */
    void setMetrics(Glyph[] glyphs, float ascent, float lineHeight) {
        this.glyphs = glyphs;
        this.ascent = ascent;
        this.lineHeight = lineHeight;
        Glyph question = '?' < glyphs.length ? glyphs['?'] : null;
        fallback = question != null ? question : new Glyph(0, 0, 0, 0, 0, 0, 0, 0, size * 0.5f);
    }
}

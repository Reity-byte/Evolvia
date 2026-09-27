package evolvia.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.system.MemoryUtil.memFree;

/** Font baking needs stb_truetype only (no OpenGL). */
class FontAtlasTest {

    private static FontAtlas atlas;
    private static Font regular;
    private static Font bold;

    @BeforeAll
    static void bake() {
        atlas = new FontAtlas();
        regular = atlas.add("fonts/DroidSans.ttf", 15f);
        bold = atlas.add("fonts/DroidSans-Bold.ttf", 19f);
        memFree(atlas.bake(1f).pixels());
    }

    @AfterAll
    static void close() {
        atlas.close(); // no texture was created, only frees the font files
    }

    @Test
    void czechLettersHaveTheirOwnGlyphs() {
        Font.Glyph fallback = regular.glyph('￿');
        for (char ch : "ěščřžýáíéúůťďňĚŠČŘŽÝÁÍÉÚŮŤĎŇ–„“…×".toCharArray()) {
            Font.Glyph glyph = regular.glyph(ch);
            assertNotSame(fallback, glyph, "missing glyph for " + ch);
            assertTrue(glyph.advance() > 0, "zero width " + ch);
        }
    }

    @Test
    void metricsAreInUiUnitsIndependentOfTheDisplayScale() {
        float width = regular.width("Evoluční strom");
        float lineHeight = regular.lineHeight();
        memFree(atlas.bake(2f).pixels());
        assertEquals(width, regular.width("Evoluční strom"), width * 0.03f);
        assertEquals(lineHeight, regular.lineHeight(), 0.01f);
        memFree(atlas.bake(1f).pixels());
        assertTrue(bold.lineHeight() > regular.lineHeight());
    }

    @Test
    void wrappedLinesFitTheWidth() {
        String text = "Pamatuje si, kde naposledy pila a jedla, a vrátí se tam, když nic nevidí.";
        List<String> lines = Ui.wrap(regular, text, 150f);
        assertTrue(lines.size() > 1);
        for (String line : lines) {
            assertTrue(regular.width(line) <= 150f, line);
        }
        assertEquals(text, String.join(" ", lines));
    }

    @Test
    void longNamesAreShortenedWithAnEllipsis() {
        String fitted = EvolutionTreeView.fit(regular, "Velmi dlouhý název evolučního uzlu", 100f);
        assertTrue(fitted.endsWith("…"));
        assertTrue(regular.width(fitted) <= 100f);
    }
}

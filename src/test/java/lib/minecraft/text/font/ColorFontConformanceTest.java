package lib.minecraft.text.font;

import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end conformance over the committed synthetic fixture: resolve a colour font through the
 * classpath loader, register it, lay out a mixed run, and assert advances, strike selection, native
 * (untinted) colours, and the {@code FontMetrics.stringWidth} flow-through.
 */
@DisplayName("Colour font end-to-end conformance over the committed fixture")
class ColorFontConformanceTest {

    @AfterEach
    void tearDown() {
        MinecraftFont.clear();
    }

    @Test
    @DisplayName("MinecraftColorFont.load resolves the font id and its .ttf from the classpath")
    void loadsFromClasspath() {
        MinecraftColorFont font = MinecraftColorFont.load(ColorFontFixtures.DEMO);
        assertThat(font.fontId(), is(ColorFontFixtures.DEMO));
        assertThat(font.sidecar().unitsPerEm(), is(1024));
        assertThat(font.reader().numGlyphs(), is(ColorFontFixtures.NUM_GLYPHS));
    }

    @Test
    @DisplayName("loading an unregistered font id fails loud, naming the font id")
    void loadUnknownFontIdFailsLoud() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> MinecraftColorFont.load(FontId.parse("synth:missing")));
        assertTrue(ex.getMessage().contains("synth:missing"));
    }

    @Test
    @DisplayName("the MinecraftFont registry registers and returns the same instance per font id")
    void registryStoresByFontId() {
        MinecraftColorFont font = ColorFontFixtures.demoFont();
        MinecraftFont.register(font);
        assertThat(MinecraftFont.get(ColorFontFixtures.DEMO).orElseThrow(), sameInstance(font));

        MinecraftFont.unregister(ColorFontFixtures.DEMO);
        assertThat(MinecraftFont.get(ColorFontFixtures.DEMO).isPresent(), is(false));
    }

    @Test
    @DisplayName("getOrLoad resolves once and caches the registration")
    void getOrLoadCaches() {
        MinecraftFont first = MinecraftFont.getOrLoad(ColorFontFixtures.DEMO);
        MinecraftFont second = MinecraftFont.getOrLoad(ColorFontFixtures.DEMO);
        assertThat(second, sameInstance(first));
    }

    @Test
    @DisplayName("a mixed run measures through the unified metrics and paints native colours")
    void mixedRunConformance() {
        MinecraftFont font = MinecraftFont.getOrLoad(ColorFontFixtures.DEMO);
        String text = new String(Character.toChars(ColorFontFixtures.CP_FLAT))
            + new String(Character.toChars(ColorFontFixtures.CP_NEG_SPACE))
            + new String(Character.toChars(ColorFontFixtures.CP_DOWNSCALED));

        // The one layout entry point, shared with the vanilla path.
        MinecraftGlyphVector vector = font.layout(text);

        // advances: 16 (flat) - 16 (neg space) + 8 (downscaled) = 8
        assertThat(vector.advanceX(), closeTo(8.0, 1e-9));
        assertThat(vector.advanceX(), is(font.metrics().stringAdvanceX(text)));
        assertThat(font.metrics().charWidth(ColorFontFixtures.CP_DOWNSCALED), is(8));
        assertThat(font.metrics().charWidth(ColorFontFixtures.CP_NEG_SPACE), is(-16));

        // strike selection per glyph
        assertThat(vector.positionedGlyph(0).strikePpem(), is(8));
        assertThat(vector.positionedGlyph(2).strikePpem(), is(16));

        // paint and confirm the downscaled glyph lands as native colour (untinted)
        PixelBuffer target = PixelBuffer.create(32, 32);
        target.fill(0);
        MinecraftGraphics graphics = new MinecraftGraphics(target);
        vector.paint(graphics, 0, 0, Color.MAGENTA);

        // glyph 2 starts at penX 0 (16 - 16); its ppem-16 strike top-left is red (200,30,30)
        assertThat(target.getPixel(0, 0), is(0xFFC81E1E));
    }

}

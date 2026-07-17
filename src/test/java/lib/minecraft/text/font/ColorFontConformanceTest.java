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
 * (untinted) colours, and the {@code FontMetrics.stringWidth} flow-through. Two assertions pin the
 * single-file bridge specifically: the same original codepoint resolves to different native art
 * under two font ids through the one merged {@code .ttf}, and two original codepoints that share a
 * content-deduped gid resolve to one shared strike surface.
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
    @DisplayName("the same PUA codepoint resolves to different native art per font id through the one merged file")
    void puaDisambiguationThroughSingleFile() {
        MinecraftColorFont demo = MinecraftColorFont.load(ColorFontFixtures.DEMO);
        MinecraftColorFont alt = MinecraftColorFont.load(ColorFontFixtures.ALT);

        // both font ids are served by the single merged .ttf named at the top level of the sidecar
        assertThat(demo.sidecar().file().orElseThrow(), is(ColorFontFixtures.MERGED_TTF));
        assertThat(alt.sidecar().file().orElseThrow(), is(ColorFontFixtures.MERGED_TTF));

        MinecraftGlyph demoGlyph = demo.glyph(ColorFontFixtures.CP_FLAT);
        MinecraftGlyph altGlyph = alt.glyph(ColorFontFixtures.CP_FLAT);

        // one original codepoint (E001) bridges to distinct stored codepoints -> distinct merged gids
        assertThat(demoGlyph.gid(), is(ColorFontFixtures.GID_FLAT));
        assertThat(altGlyph.gid(), is(ColorFontFixtures.GID_ALT_FLAT));

        // ...so the resolved native artwork differs even though the codepoint and file are identical
        assertThat(demoGlyph.bitmap().getPixel(0, 0), is(0xFFDC2828));   // demo E001 top-left red
        assertThat(altGlyph.bitmap().getPixel(0, 0), is(0xFF28C83C));    // alt E001 top-left green
    }

    @Test
    @DisplayName("two original codepoints sharing a content-deduped gid resolve to one shared strike surface")
    void dedupSharedGidResolvesSameArt() {
        MinecraftColorFont font = MinecraftColorFont.load(ColorFontFixtures.DEMO);

        MinecraftGlyph flat = font.glyph(ColorFontFixtures.CP_FLAT);
        MinecraftGlyph dup = font.glyph(ColorFontFixtures.CP_FLAT_DUP);

        // pack-wide content dedup collapsed the identical art of E001 and E003 onto one gid...
        assertThat(flat.gid(), is(ColorFontFixtures.GID_FLAT));
        assertThat(dup.gid(), is(ColorFontFixtures.GID_FLAT));

        // ...and both original codepoints decode that gid to the same cached strike, so the art is one surface
        assertThat(dup.bitmap(), sameInstance(flat.bitmap()));
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

package lib.minecraft.text.font;

import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@DisplayName("GlyphData carries colour bitmaps without changing the mono form")
class GlyphDataTest {

    @Test
    @DisplayName("the four-arg mono form is never colour, mirrors advanceWidth, and is kind MONO")
    void monoFormIsUnchanged() {
        PixelBuffer bitmap = PixelBuffer.create(2, 2);
        MinecraftFont.GlyphData glyph = new MinecraftFont.GlyphData(bitmap, 7, -1, -3);
        assertThat(glyph.color(), is(false));
        assertThat(glyph.signedAdvance(), is(7.0f));
        assertThat(glyph.advanceWidth(), is(7));
        assertThat(glyph.originX(), is(0));
        assertThat(glyph.originY(), is(0));
        assertThat(glyph.kind(), is(MinecraftGlyphVector.Kind.MONO));
    }

    @Test
    @DisplayName("a vanilla atlas glyph is monochrome and kind MONO")
    void vanillaAtlasGlyphIsMono() {
        MinecraftFont.GlyphData glyph = MinecraftFont.Vanilla.REGULAR.glyph('A');
        assertThat(glyph.color(), is(false));
        assertThat(glyph.signedAdvance(), is((float) glyph.advanceWidth()));
        assertThat(glyph.kind(), is(MinecraftGlyphVector.Kind.MONO));
    }

    @Test
    @DisplayName("the colour factory flags colour, keeps a fractional signed advance, a rounded advanceWidth, and is kind RASTER")
    void colourFactoryCarriesColourAndFractionalAdvance() {
        PixelBuffer bitmap = PixelBuffer.create(4, 4);
        MinecraftFont.GlyphData glyph = MinecraftFont.GlyphData.color(bitmap, 1.5f, 2, -4);
        assertThat(glyph.color(), is(true));
        assertThat(glyph.signedAdvance(), is(1.5f));
        assertThat(glyph.advanceWidth(), is(2));   // Math.round(1.5f) == 2
        assertThat(glyph.originX(), is(2));
        assertThat(glyph.originY(), is(-4));
        assertThat(glyph.bearingX(), is(2));   // origin doubles as bearing for colour blits
        assertThat(glyph.bearingY(), is(-4));
        assertThat(glyph.kind(), is(MinecraftGlyphVector.Kind.RASTER));
    }

    @Test
    @DisplayName("the space factory is an advance-only sentinel: not colour, never null bitmap, kind SPACE")
    void spaceFactoryIsAdvanceOnlySentinel() {
        MinecraftFont.GlyphData glyph = MinecraftFont.GlyphData.space(-16.0f);
        assertThat(glyph.color(), is(false));
        assertThat(glyph.signedAdvance(), is(-16.0f));
        assertThat(glyph.advanceWidth(), is(-16));
        assertThat(glyph.originX(), is(0));
        assertThat(glyph.originY(), is(0));
        assertThat(glyph.kind(), is(MinecraftGlyphVector.Kind.SPACE));
        assertThat(glyph.bitmap().width(), is(1));    // 1x1 transparent sentinel, never null
        assertThat(glyph.bitmap().height(), is(1));
    }

}

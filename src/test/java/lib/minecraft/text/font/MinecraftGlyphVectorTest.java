package lib.minecraft.text.font;

import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.io.StringReader;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MinecraftGlyphVector lays out and paints pack colour text")
class MinecraftGlyphVectorTest {

    private static String cp(int codepoint) {
        return new String(Character.toChars(codepoint));
    }

    @Test
    @DisplayName("pen positions come from cumulative sidecar advances, not GlyphVector positions")
    void layoutPositionsFromSidecar() {
        MinecraftColorFont font = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = font.layout(cp(ColorFontFixtures.CP_FLAT).repeat(3));

        assertThat(vector.glyphCount(), is(3));
        assertThat(vector.positionedGlyph(0).penX(), is(0.0));
        assertThat(vector.positionedGlyph(1).penX(), is(16.0));   // one em = 16 output px
        assertThat(vector.positionedGlyph(2).penX(), is(32.0));
        assertThat(vector.advanceX(), is(48.0));
        assertThat(vector.positionedGlyph(0).kind(), is(MinecraftGlyphVector.Kind.RASTER));
    }

    @Test
    @DisplayName("measure equals draw: MinecraftFontMetrics.stringAdvanceX == MinecraftGlyphVector.advanceX")
    void measureEqualsDraw() {
        MinecraftColorFont font = ColorFontFixtures.demoFont();
        String text = cp(ColorFontFixtures.CP_FLAT) + cp(ColorFontFixtures.CP_FRAC_SPACE) + cp(ColorFontFixtures.CP_DOWNSCALED);
        MinecraftGlyphVector vector = font.layout(text);

        assertThat(vector.advanceX(), is(font.metrics().stringAdvanceX(text)));
        assertThat(vector.advanceX(), closeTo(25.5, 1e-9));   // 16 + 1.5 + 8
    }

    @Test
    @DisplayName("a raster glyph paints its native strike pixels, byte-for-byte")
    void rasterBlitPixelIdentical() {
        MinecraftColorFont font = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = font.layout(cp(ColorFontFixtures.CP_FLAT));

        PixelBuffer target = PixelBuffer.create(16, 16);
        target.fill(0);
        MinecraftGraphics graphics = new MinecraftGraphics(target);
        vector.paint(graphics, 0, 0, Color.RED);

        PixelBuffer source = font.strike(1, 8).orElseThrow();
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++)
                assertThat("pixel (" + x + "," + y + ")", target.getPixel(x, y), is(source.getPixel(x, y)));
    }

    @Test
    @DisplayName("a non-zero sidecar origin offsets the raster blit on both axes independently")
    void rasterGlyphOriginOffsetsBlitPosition() {
        // The committed fixture uses origin [0,0] everywhere, so the sidecar-origin -> bearing ->
        // blit-offset flow is otherwise unexercised: an axis swap or a sign flip would pass every
        // other test. This pins it. origin [128, 192] font units = (2, 3) output px (128 units per
        // mcPixel, MC_PIXEL_SCALE = 2), and 2 != 3 so a swapped axis lands on a background pixel.
        ColorGlyphSidecar sidecar = ColorGlyphSidecar.parse(new StringReader("""
            {
              "schema_version": 1, "units_per_em": 1024, "graphic_type": "png ",
              "fonts": [{"font_id": "synth:demo", "file": "SynthColour-demo.ttf"}],
              "glyphs": [
                {"font_id": "synth:demo", "codepoint": 57345, "gid": 1,
                 "advance": 1024, "origin": [128, 192], "strike_ppem": 8}
              ]
            }
            """));
        MinecraftColorFont font = MinecraftColorFont.of(ColorFontFixtures.DEMO,
            ColorFontFixtures.bytes("SynthColour-demo.ttf"), sidecar, MinecraftFont.Vanilla.REGULAR);

        MinecraftGlyphVector vector = font.layout(cp(ColorFontFixtures.CP_FLAT));
        MinecraftGlyphVector.PositionedGlyph glyph = vector.positionedGlyph(0);
        assertThat(glyph.originX(), is(2));
        assertThat(glyph.originY(), is(3));

        PixelBuffer target = PixelBuffer.create(16, 16);
        target.fill(0);
        MinecraftGraphics graphics = new MinecraftGraphics(target);
        vector.paint(graphics, 0, 0, Color.WHITE);

        // The strike's opaque top-left pixel lands at (originX, originY).
        assertThat("origin offset applied", target.getPixel(2, 3), is(0xFFDC2828));
        assertThat("nothing at the un-offset origin", target.getPixel(0, 0), is(0));
        assertThat("a swapped axis would paint here", target.getPixel(3, 2), is(0));
    }

    @Test
    @DisplayName("a colour raster glyph is never tinted by the fill colour")
    void rasterGlyphNotTinted() {
        MinecraftColorFont font = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = font.layout(cp(ColorFontFixtures.CP_FLAT));

        PixelBuffer target = PixelBuffer.create(16, 16);
        target.fill(0);
        MinecraftGraphics graphics = new MinecraftGraphics(target);
        vector.paint(graphics, 0, 0, Color.GREEN);   // fill would tint a mono glyph green

        assertThat(target.getPixel(0, 0), is(0xFFDC2828));   // still the strike's red
        assertThat(target.getPixel(4, 4), is(0xFF283CDC));   // still the strike's blue
    }

    @Test
    @DisplayName("a negative-advance space provider moves the pen backward and paints nothing")
    void spaceProviderNegativeAdvance() {
        MinecraftColorFont font = ColorFontFixtures.demoFont();
        String text = cp(ColorFontFixtures.CP_FLAT) + cp(ColorFontFixtures.CP_NEG_SPACE) + cp(ColorFontFixtures.CP_FLAT);
        MinecraftGlyphVector vector = font.layout(text);

        assertThat(vector.positionedGlyph(1).kind(), is(MinecraftGlyphVector.Kind.SPACE));
        assertThat(vector.positionedGlyph(1).advance(), is(-16.0));
        assertThat(vector.positionedGlyph(2).penX(), is(0.0));   // 16 + (-16) back to origin
        assertThat(vector.positionedGlyph(1).glyph().kind(), is(MinecraftGlyphVector.Kind.SPACE));   // SPACE sentinel, not null
        assertThat(vector.advanceX(), is(16.0));
    }

    @Test
    @DisplayName("a fractional pen rounds identically for measure and draw")
    void fractionalAdvanceRoundingConsistent() {
        MinecraftColorFont font = ColorFontFixtures.demoFont();
        String text = cp(ColorFontFixtures.CP_FLAT) + cp(ColorFontFixtures.CP_FRAC_SPACE) + cp(ColorFontFixtures.CP_FLAT);
        MinecraftGlyphVector vector = font.layout(text);

        assertThat(vector.positionedGlyph(2).penX(), closeTo(17.5, 1e-9));
        assertThat(vector.advanceX(), closeTo(33.5, 1e-9));
        assertThat(Math.round((float) vector.advanceX()), is(Math.round((float) font.metrics().stringAdvanceX(text))));
    }

    @Test
    @DisplayName("a codepoint not in the sidecar falls back to the vanilla mono atlas")
    void monoFallbackForUnlistedCodepoint() {
        MinecraftColorFont font = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = font.layout("A");   // 0x41 is not a pack colour glyph

        MinecraftGlyphVector.PositionedGlyph glyph = vector.positionedGlyph(0);
        assertThat(glyph.kind(), is(MinecraftGlyphVector.Kind.MONO));
        assertThat(glyph.glyph().color(), is(false));
        assertThat(glyph.advance(), is((double) MinecraftFont.Vanilla.REGULAR.glyph('A').advanceWidth()));
    }

    @Test
    @DisplayName("the same PUA codepoint resolves to different artwork under different font ids")
    void puaCollisionDisambiguatedByFontId() {
        MinecraftColorFont demo = ColorFontFixtures.demoFont();
        MinecraftColorFont alt = ColorFontFixtures.altFont();

        PixelBuffer demoStrike = demo.strike(1, 8).orElseThrow();
        PixelBuffer altStrike = alt.strike(1, 8).orElseThrow();

        assertThat(demoStrike.getPixel(0, 0), is(0xFFDC2828));   // demo E001 top-left red
        assertThat(altStrike.getPixel(0, 0), is(0xFF28C83C));    // alt E001 top-left green (40,200,60)
        assertFalse(demoStrike.getPixel(0, 0) == altStrike.getPixel(0, 0));
    }

    @Test
    @DisplayName("strike selection follows the sidecar strike_ppem (downscaled art uses a larger ppem)")
    void strikeSelectionFollowsPpem() {
        MinecraftColorFont font = ColorFontFixtures.demoFont();
        MinecraftGlyphVector flat = font.layout(cp(ColorFontFixtures.CP_FLAT));
        MinecraftGlyphVector downscaled = font.layout(cp(ColorFontFixtures.CP_DOWNSCALED));

        assertThat(flat.positionedGlyph(0).strikePpem(), is(8));
        assertThat(downscaled.positionedGlyph(0).strikePpem(), is(16));

        // The ppem-16 strike carries the downscaled art (top-left red 200,30,30).
        assertThat(font.strike(4, 16).orElseThrow().getPixel(0, 0), is(0xFFC81E1E));
    }

    @Test
    @DisplayName("knockout subtracts overlapping mono outlines and is empty for raster glyphs")
    void knockoutAreaOnMonoOutlines() {
        MinecraftColorFont font = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = font.layout("AB" + cp(ColorFontFixtures.CP_FLAT));

        assertTrue(vector.outline(0).isPresent());   // mono A
        assertTrue(vector.outline(1).isPresent());   // mono B
        assertFalse(vector.outline(2).isPresent());  // raster glyph has no outline

        assertFalse(vector.knockout(0, 1).isEmpty());   // mono over mono -> real area
        assertTrue(vector.knockout(2, 1).isEmpty());     // raster over mono -> empty
        assertTrue(vector.knockout(0, 2).isEmpty());     // mono over raster -> empty
    }

    @Test
    @DisplayName("regression: Java2D zeroes GlyphVector advances for an sbix font - our layout must not trust them")
    void glyphVectorAdvanceIsZeroRegression() throws Exception {
        Font awtFont = Font.createFont(Font.TRUETYPE_FONT,
            new java.io.ByteArrayInputStream(ColorFontFixtures.bytes("SynthColour-demo.ttf"))).deriveFont(16.0f);
        FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, true);
        GlyphVector awt = awtFont.createGlyphVector(frc, cp(ColorFontFixtures.CP_FLAT).repeat(3));
        double awtAdvance = awt.getGlyphPosition(awt.getNumGlyphs()).getX();

        MinecraftGlyphVector ours = ColorFontFixtures.demoFont().layout(cp(ColorFontFixtures.CP_FLAT).repeat(3));

        // The AWT advance is zeroed by sbix; our sidecar-driven advance is the real width.
        assertThat(awtAdvance, is(0.0));
        assertThat(ours.advanceX(), greaterThan(0.0));
    }

    @Test
    @DisplayName("backcompat: drawString still renders vanilla text into the buffer")
    void drawStringStillRenders() {
        PixelBuffer target = PixelBuffer.create(64, 32);
        target.fill(0);
        MinecraftGraphics graphics = new MinecraftGraphics(target);
        graphics.setColor(Color.WHITE);
        graphics.drawString("Hi", 1, 10);

        int painted = 0;
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 64; x++)
                if ((target.getPixel(x, y) >>> 24) != 0) painted++;
        assertThat(painted, greaterThan(0));
    }

}

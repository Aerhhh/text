package lib.minecraft.text.font;

import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link MinecraftGlyphVector} {@code GlyphVector}-subclass contract: honest codes/positions/font,
 * stubbed outline-and-mutator surface, and the single-walk ownership that keeps measure, layout, and
 * draw agreeing. See the class javadoc of {@link MinecraftGlyphVector} for why the outline surface is
 * dead code and why no real-AWT interop test is possible (foreign vectors are reduced to glyph codes).
 */
@DisplayName("MinecraftGlyphVector honours the GlyphVector contract where our pipeline reads it")
class MinecraftGlyphVectorExtensionTest {

    private static String cp(int codepoint) {
        return new String(Character.toChars(codepoint));
    }

    private static Font awtFontFromFixture() throws Exception {
        return Font.createFont(Font.TRUETYPE_FONT,
            new ByteArrayInputStream(ColorFontFixtures.bytes(ColorFontFixtures.MERGED_TTF))).deriveFont(16.0f);
    }

    @Test
    @DisplayName("it is a java.awt.font.GlyphVector")
    void isAGlyphVector() {
        assertTrue(GlyphVector.class.isAssignableFrom(MinecraftGlyphVector.class));
        assertTrue(MinecraftFont.Vanilla.REGULAR.layout("Hi") instanceof GlyphVector);
    }

    @Test
    @DisplayName("getGlyphCode: a colour raster glyph reports its sidecar gid; a vanilla glyph resolves its cmap gid")
    void glyphCodesAreRealGidsForBothKinds() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector rasterVector = colour.layout(cp(ColorFontFixtures.CP_FLAT));
        assertThat("raster gid straight from the row", rasterVector.getGlyphCode(0), is(ColorFontFixtures.GID_FLAT));

        // A mono fallback glyph in a colour run resolves through the merged font's cmap (0/.notdef here).
        MinecraftGlyphVector monoInColour = colour.layout("A");
        assertThat(monoInColour.getGlyphCode(0), is(colour.glyphCode('A')));

        MinecraftFont.Vanilla vanilla = MinecraftFont.Vanilla.REGULAR;
        MinecraftGlyphVector vanillaVector = vanilla.layout("A");
        int cmapGid = vanilla.getActual().createGlyphVector(vanilla.fontRenderContext(), "A").getGlyphCode(0);
        assertThat(vanillaVector.getGlyphCode(0), is(cmapGid));
        assertThat("the vanilla font has a real glyph for 'A'", vanillaVector.getGlyphCode(0), greaterThan(0));
    }

    @Test
    @DisplayName("getGlyphCodes bulk-fills the same real gids as getGlyphCode")
    void glyphCodesBulkMatchesPerGlyph() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = colour.layout(cp(ColorFontFixtures.CP_FLAT).repeat(3));

        int[] bulk = vector.getGlyphCodes(0, vector.getNumGlyphs(), null);
        assertThat(bulk.length, is(3));
        for (int i = 0; i < 3; i++) assertThat(bulk[i], is(vector.getGlyphCode(i)));
        assertThat(bulk[0], is(ColorFontFixtures.GID_FLAT));
    }

    @Test
    @DisplayName("getGlyphPosition/getGlyphPositions report the walk pen positions; the end index is the total advance")
    void positionsMatchWalkPen() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = colour.layout(cp(ColorFontFixtures.CP_FLAT).repeat(3));

        for (int i = 0; i < vector.getNumGlyphs(); i++) {
            assertThat(vector.getGlyphPosition(i).getX(), is(vector.positionedGlyph(i).penX()));
            assertThat(vector.getGlyphPosition(i).getY(), is(0.0));
        }
        // The end position (index == numGlyphs) is the run's total advance.
        assertThat(vector.getGlyphPosition(3).getX(), closeTo(vector.advanceX(), 1e-9));

        float[] bulk = vector.getGlyphPositions(0, vector.getNumGlyphs() + 1, null);
        assertThat(bulk.length, is(8));
        assertThat((double) bulk[0], is(0.0));
        assertThat((double) bulk[2], is(16.0));
        assertThat((double) bulk[4], is(32.0));
        assertThat((double) bulk[6], closeTo(vector.advanceX(), 1e-6));   // end pen
        assertThat((double) bulk[1], is(0.0));   // y is always 0
    }

    @Test
    @DisplayName("getFont is honest for both kinds: vanilla hands back its own font, a colour font its merged ttf")
    void getFontHonestForBothKinds() {
        MinecraftFont.Vanilla vanilla = MinecraftFont.Vanilla.REGULAR;
        assertThat(vanilla.layout("Hi").getFont(), sameInstance(vanilla.getActual()));

        MinecraftFont.Color demo = ColorFontFixtures.demoFont();
        MinecraftFont.Color alt = ColorFontFixtures.altFont();
        Font colourFont = demo.layout(cp(ColorFontFixtures.CP_FLAT)).getFont();
        assertThat(colourFont, notNullValue());
        assertThat("gids match the merged font it hands back", colourFont.getNumGlyphs(), is(ColorFontFixtures.NUM_GLYPHS));
        // Same backing .ttf bytes -> one shared AWT font across every font id of the pack.
        assertThat(alt.layout(cp(ColorFontFixtures.CP_FLAT)).getFont(), sameInstance(colourFont));
    }

    @Test
    @DisplayName("getFontRenderContext, getNumGlyphs, and getGlyphTransform report real, legal data")
    void miscHonestSurface() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = colour.layout(cp(ColorFontFixtures.CP_FLAT) + "A");

        assertThat(vector.getFontRenderContext(), sameInstance(colour.fontRenderContext()));
        assertThat(vector.getNumGlyphs(), is(2));
        assertThat("no per-glyph transform is the legal null answer", vector.getGlyphTransform(0), nullValue());
    }

    @Test
    @DisplayName("getLogicalBounds spans the total advance and the font ascent/descent")
    void logicalBoundsFromMetrics() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = colour.layout(cp(ColorFontFixtures.CP_FLAT).repeat(2));
        MinecraftFontMetrics metrics = colour.metrics();

        Rectangle2D logical = vector.getLogicalBounds();
        assertThat(logical.getX(), is(0.0));
        assertThat(logical.getWidth(), closeTo(vector.advanceX(), 1e-9));
        assertThat(logical.getY(), closeTo(-metrics.getAscent(), 1e-9));
        assertThat(logical.getHeight(), closeTo(metrics.getAscent() + metrics.getDescent(), 1e-9));
    }

    @Test
    @DisplayName("getVisualBounds is the union of glyph ink boxes; space glyphs contribute nothing")
    void visualBoundsFromInk() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();

        // A single 8x8 flat raster glyph at origin -> ink box (0,0,8,8).
        Rectangle2D flat = colour.layout(cp(ColorFontFixtures.CP_FLAT)).getVisualBounds();
        assertThat(flat.getX(), is(0.0));
        assertThat(flat.getY(), is(0.0));
        assertThat(flat.getWidth(), is(8.0));
        assertThat(flat.getHeight(), is(8.0));

        // A lone space provider has no ink -> empty visual bounds.
        Rectangle2D spaceOnly = colour.layout(cp(ColorFontFixtures.CP_NEG_SPACE)).getVisualBounds();
        assertThat(spaceOnly.getWidth(), is(0.0));
        assertThat(spaceOnly.getHeight(), is(0.0));
    }

    @Test
    @DisplayName("getGlyphMetrics is honest-and-cheap: advance from signedAdvance, bounds from the bitmap box")
    void glyphMetricsFromPackData() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector vector = colour.layout(cp(ColorFontFixtures.CP_FLAT) + cp(ColorFontFixtures.CP_NEG_SPACE));

        assertThat((double) vector.getGlyphMetrics(0).getAdvanceX(), is(16.0));
        assertThat(vector.getGlyphMetrics(0).getBounds2D().getWidth(), is(8.0));
        assertThat(vector.getGlyphMetrics(0).getBounds2D().getHeight(), is(8.0));

        // A space glyph advances but has empty ink bounds.
        assertThat((double) vector.getGlyphMetrics(1).getAdvanceX(), is(-16.0));
        assertThat(vector.getGlyphMetrics(1).getBounds2D().getWidth(), is(0.0));
    }

    @Test
    @DisplayName("the outline/justification surface throws UnsupportedOperationException (dead code we own the draw for)")
    void outlineSurfaceStubbed() {
        MinecraftGlyphVector vector = MinecraftFont.Vanilla.REGULAR.layout("A");

        assertThrows(UnsupportedOperationException.class, vector::getOutline);
        assertThrows(UnsupportedOperationException.class, () -> vector.getOutline(1f, 2f));
        assertThrows(UnsupportedOperationException.class, () -> vector.getGlyphOutline(0));
        assertThrows(UnsupportedOperationException.class, () -> vector.getGlyphLogicalBounds(0));
        assertThrows(UnsupportedOperationException.class, () -> vector.getGlyphVisualBounds(0));
        assertThrows(UnsupportedOperationException.class, () -> vector.getGlyphJustificationInfo(0));
    }

    @Test
    @DisplayName("the position/transform mutators throw: positions are pack data, not caller mutations")
    void mutatorsThrow() {
        MinecraftGlyphVector vector = MinecraftFont.Vanilla.REGULAR.layout("A");
        assertThrows(UnsupportedOperationException.class, () -> vector.setGlyphPosition(0, new Point2D.Float(5f, 5f)));
        assertThrows(UnsupportedOperationException.class, () -> vector.setGlyphTransform(0, new AffineTransform()));
    }

    @Test
    @DisplayName("performDefaultLayout is a no-op: precomputed pack pens are never recomputed")
    void performDefaultLayoutIsNoOp() {
        MinecraftGlyphVector vector = ColorFontFixtures.demoFont().layout(cp(ColorFontFixtures.CP_FLAT).repeat(3));
        float[] before = vector.getGlyphPositions(0, vector.getNumGlyphs() + 1, null);
        vector.performDefaultLayout();
        float[] after = vector.getGlyphPositions(0, vector.getNumGlyphs() + 1, null);
        for (int i = 0; i < before.length; i++) assertThat(after[i], is(before[i]));
    }

    @Test
    @DisplayName("equals is value semantics over the font and the positioned glyph list")
    void equalsIsValueSemantics() {
        MinecraftFont.Color colour = ColorFontFixtures.demoFont();
        MinecraftGlyphVector a = colour.layout(cp(ColorFontFixtures.CP_FLAT).repeat(2));
        MinecraftGlyphVector b = colour.layout(cp(ColorFontFixtures.CP_FLAT).repeat(2));
        MinecraftGlyphVector c = colour.layout(cp(ColorFontFixtures.CP_FLAT));

        assertTrue(a.equals(b));
        assertTrue(a.equals(a));
        assertFalse(a.equals(c));
        assertFalse(a.equals((GlyphVector) MinecraftFont.Vanilla.REGULAR.layout("AA")));
    }

    @Test
    @DisplayName("drawGlyphVector rejects a foreign GlyphVector: AWT would reduce it to glyph codes and drop the pack layout")
    void drawGlyphVectorRejectsForeign() throws Exception {
        PixelBuffer target = PixelBuffer.create(16, 16);
        MinecraftGraphics graphics = new MinecraftGraphics(target);

        FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, true);
        GlyphVector foreign = awtFontFromFixture().createGlyphVector(frc, "A");   // a real StandardGlyphVector

        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> graphics.drawGlyphVector(foreign, 0f, 0f));
        assertThat(ex.getMessage(), notNullValue());

        // Our own vector goes through fine.
        MinecraftFont.Vanilla.REGULAR.layout("A").paint(graphics, 0, 8, Color.WHITE);
    }

    @Test
    @DisplayName("one walk, three surfaces: metrics width, vector advance, and drawString ink all trace to a single walk")
    void oneWalkThreeSurfaces() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        String text = "A" + cp(ColorFontFixtures.CP_FRAC_SPACE) + cp(ColorFontFixtures.CP_FLAT);   // mono + space + raster
        MinecraftGlyphVector vector = font.layout(text);

        // Surface 1 (measure) == Surface 2 (vector total advance).
        assertThat(font.metrics().stringWidth(text), is(Math.round((float) vector.advanceX())));

        // Surface 3 (draw): drawString and vector.paint are byte-identical, both consuming the one walk.
        PixelBuffer viaDraw = PixelBuffer.create(96, 48);
        viaDraw.fill(0);
        MinecraftGraphics g1 = new MinecraftGraphics(viaDraw);
        g1.setFont(font);
        g1.setColor(Color.WHITE);
        g1.drawString(text, 1, 16);

        PixelBuffer viaPaint = PixelBuffer.create(96, 48);
        viaPaint.fill(0);
        MinecraftGraphics g2 = new MinecraftGraphics(viaPaint);
        vector.paint(g2, 1, 16, Color.WHITE);

        for (int y = 0; y < 48; y++)
            for (int x = 0; x < 96; x++)
                assertThat("pixel (" + x + "," + y + ")", viaDraw.getPixel(x, y), is(viaPaint.getPixel(x, y)));

        // And the raster glyph's untinted ink lands exactly at the buffer column the walk pen predicts.
        int cx = 1 * MinecraftFont.MC_PIXEL_SCALE;
        int rasterCol = cx + (int) Math.round(vector.positionedGlyph(2).penX());
        boolean rasterInkAtWalkColumn = false;
        for (int y = 0; y < 48; y++)
            if (viaDraw.getPixel(rasterCol, y) == 0xFFDC2828) { rasterInkAtWalkColumn = true; break; }
        assertTrue(rasterInkAtWalkColumn, "the raster glyph's ink lands at the walk-predicted column " + rasterCol);
    }

}

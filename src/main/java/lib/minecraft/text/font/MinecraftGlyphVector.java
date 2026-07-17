package lib.minecraft.text.font;

import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphJustificationInfo;
import java.awt.font.GlyphMetrics;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A laid-out run of pack text as a {@link java.awt.font.GlyphVector} subclass whose positions and
 * glyph codes come from the pack, not from AWT.
 * <p>
 * The vector is honest everywhere our own pipeline or an honest introspection actually reads it.
 * {@link #getGlyphCodes glyph codes} are real gids (a colour glyph's sidecar gid; a mono or space
 * glyph's {@code cmap} gid in {@link #getFont() the backing font}); {@link #getGlyphPositions
 * positions} are the layout pens; {@link #getFont}, {@link #getFontRenderContext},
 * {@link #getNumGlyphs}, {@link #getLogicalBounds}, {@link #getVisualBounds}, and
 * {@link #getGlyphMetrics} all report real data.
 * <p>
 * <strong>Why the outline/transform surface is stubbed.</strong> The other half of the
 * {@code GlyphVector} contract - {@link #getOutline()}, {@link #getGlyphOutline(int)},
 * {@link #getGlyphLogicalBounds(int)}, {@link #getGlyphVisualBounds(int)},
 * {@link #getGlyphJustificationInfo(int)}, and the mutators {@link #setGlyphPosition} /
 * {@link #setGlyphTransform} - throws {@link UnsupportedOperationException}. It is dead code: the one
 * JDK consumer of a {@code GlyphVector}'s outlines and transforms is
 * {@code Graphics2D.drawGlyphVector}, and we own the single renderer that draws this type.
 * {@link MinecraftGraphics#drawGlyphVector} blits our {@link MinecraftGlyph} records directly and
 * never asks the vector for an outline, so implementing that surface would only add code no caller
 * reaches. Positions are pack data, not caller-adjustable, so the mutators throw rather than silently
 * corrupt a layout; {@link #getGlyphTransform} returns {@code null} - the legal "no transform" answer.
 * <p>
 * <strong>Interop note (measured on real AWT, not assumed).</strong> Handing this vector to a real
 * {@link java.awt.Graphics2D#drawGlyphVector} does not paint pack text, which is why the stubs cost
 * nothing in practice. A probe on JDK 21.0.10 and JDK 25.0.2 built a faithful foreign
 * {@code GlyphVector} subclass (real gids, custom wide-spread pen positions) and drew it through a
 * headless AWT {@code Graphics2D}. On both JDKs, AWT called only {@code getGlyphCodes(0, n)} - never
 * {@code getGlyphPositions}, {@code getGlyphPosition}, {@code getGlyphOutline}, or
 * {@code getGlyphTransform} - then rebuilt an internal {@code StandardGlyphVector} from those codes
 * and re-laid it out with the font's own default advances, <em>discarding the custom positions</em>
 * (a mono control's ink landed at the default {@code [0, 44, 88]}, not the injected
 * {@code [10, 160, 300]}). And because AWT rasterizes only the {@code glyf} outline, never the
 * {@code sbix} strike or {@code COLR} layers, every colour route produced zero ink under both a solid
 * paint and a gradient paint ({@code nonWhite = 0}). A {@code GlyphVector} subclass therefore cannot
 * smuggle pack positions <em>or</em> pack pixels into AWT: it reduces to its glyph codes alone. The
 * subclass exists for type compatibility and honest introspection, and this library paints it itself.
 * No AWT-interop test asserting our positions or pixels through {@code Graphics2D} is possible for
 * this reason.
 * <p>
 * Layout is uniform across both {@link MinecraftFont} kinds: {@link MinecraftFont#walk the walk}
 * resolves each codepoint through {@link MinecraftFont#glyph(int)} and accumulates pens from
 * {@link MinecraftGlyph#signedAdvance()} - a mono atlas glyph, a colour {@code sbix} strike, and a
 * space provider all arrive as a {@link MinecraftGlyph} whose {@link MinecraftGlyph#kind() kind}
 * drives the draw path. Because the same walk feeds {@link MinecraftFontMetrics}, {@link #advanceX()}
 * equals {@link MinecraftFontMetrics#stringAdvanceX(String)} for the same text. Advances accumulate
 * as a {@code double} so fractional and negative pens (space providers) are exact; rounding happens
 * only at blit time.
 */
public final class MinecraftGlyphVector extends GlyphVector {

    private final @NotNull MinecraftFont font;
    private final @NotNull List<MinecraftGlyph> glyphs;
    private final double advanceX;

    private MinecraftGlyphVector(@NotNull MinecraftFont font, @NotNull List<MinecraftGlyph> glyphs, double advanceX) {
        this.font = font;
        this.glyphs = glyphs;
        this.advanceX = advanceX;
    }

    /**
     * Lays out a run of text for any font by materializing {@link MinecraftFont#walk the walk} into a
     * positioned glyph list. The single layout entry point for both font kinds.
     * <p>
     * Each codepoint resolves through {@link MinecraftFont#glyph(int)}: a vanilla atlas glyph, a
     * colour {@code sbix} strike, or a space provider all arrive as a {@link MinecraftGlyph}. Pen
     * positions accumulate from {@link MinecraftGlyph#signedAdvance()} - never from
     * {@link java.awt.font.GlyphVector#getGlyphPosition}, which {@code sbix} zeroes - and each glyph is
     * stamped with its pen through {@link MinecraftGlyph#at(double)} for the vector-based callers that
     * read {@link #positionedGlyph(int)}.
     *
     * @param font the font to lay the text out in
     * @param text the text to lay out
     * @return the positioned glyph vector
     */
    static @NotNull MinecraftGlyphVector of(@NotNull MinecraftFont font, @NotNull String text) {
        List<MinecraftGlyph> glyphs = new ArrayList<>();
        double advance = font.walk(text, (glyph, penX) -> glyphs.add(glyph.at(penX)));
        return new MinecraftGlyphVector(font, List.copyOf(glyphs), advance);
    }

    /**
     * Paints the run at an mcPixel origin. Raster glyphs blit their native RGBA strike (untinted);
     * mono glyphs are tinted by {@code fill}; space glyphs paint nothing. Delegates to
     * {@link MinecraftGraphics#drawGlyphVector} so the mcPixel-to-buffer translation stays in one
     * place.
     *
     * @param graphics the target graphics
     * @param xMcPx the run origin X in mcPixels
     * @param yMcPx the run origin Y in mcPixels (baseline for mono glyphs)
     * @param fill the tint applied to mono glyphs
     */
    public void paint(@NotNull MinecraftGraphics graphics, int xMcPx, int yMcPx, @NotNull Color fill) {
        graphics.drawGlyphVector(this, xMcPx, yMcPx, fill);
    }

    /**
     * The total signed run advance in output pixels. Equals
     * {@link MinecraftFontMetrics#stringAdvanceX(String)} for the same text.
     *
     * @return the total advance in output pixels
     */
    public double advanceX() {
        return this.advanceX;
    }

    /**
     * The number of positioned glyphs. Same value as the {@code GlyphVector} contract's
     * {@link #getNumGlyphs()}; retained as the name the pack pipeline reads.
     *
     * @return the number of positioned glyphs
     */
    public int glyphCount() {
        return this.glyphs.size();
    }

    /**
     * Returns the positioned glyph at an index - a {@link MinecraftGlyph} stamped with its pen.
     *
     * @param index the glyph index
     * @return the positioned glyph
     */
    public @NotNull MinecraftGlyph positionedGlyph(int index) {
        return this.glyphs.get(index);
    }

    /**
     * Returns the mono glyph outline at an index, translated to its pen, for the knockout hook.
     * Raster and space glyphs have no fillable outline and return empty.
     * <p>
     * This is the pack pipeline's own outline accessor, distinct from the stubbed
     * {@link #getGlyphOutline(int)} {@code GlyphVector} method: it returns an {@link Optional} that is
     * empty (rather than throwing) for the raster and space glyphs that legitimately have no outline,
     * which is exactly what the knockout hook needs.
     *
     * @param index the glyph index
     * @return the translated outline, or empty for non-mono glyphs
     */
    public @NotNull Optional<Shape> outline(int index) {
        MinecraftGlyph glyph = this.glyphs.get(index);
        if (glyph.kind() != Kind.MONO) return Optional.empty();
        return this.font.monoOutline(glyph.codepoint(), glyph.penX());
    }

    /**
     * Computes the mono-glyph knockout: the under glyph's outline minus the over glyph's outline.
     * This is the overlap hook for negative-advance mono runs. When either glyph is raster or space
     * (no outline), the result is an empty area.
     *
     * @param overIndex the glyph drawn on top
     * @param underIndex the glyph drawn beneath
     * @return the knocked-out area, or an empty area when either glyph has no outline
     */
    public @NotNull Area knockout(int overIndex, int underIndex) {
        Optional<Shape> over = outline(overIndex);
        Optional<Shape> under = outline(underIndex);
        if (over.isEmpty() || under.isEmpty()) return new Area();

        Area area = new Area(under.get());
        area.subtract(new Area(over.get()));
        return area;
    }

    /**
     * The Minecraft font this vector was laid out for. Distinct from the {@code GlyphVector} contract's
     * {@link #getFont()}, which returns the backing AWT {@link java.awt.Font}.
     *
     * @return the Minecraft font this vector was laid out for
     */
    public @NotNull MinecraftFont font() {
        return this.font;
    }

    // --- java.awt.font.GlyphVector contract: honest surface ---

    /**
     * The backing AWT {@link java.awt.Font} - {@link MinecraftFont#awtFont()}. For a colour font this
     * is the merged {@code .ttf}; its {@code cmap} produces the gids {@link #getGlyphCode(int)}
     * reports, so the two agree. AWT will still paint that font's empty-{@code glyf} colour glyphs
     * blank (see the class interop note), which is the documented degradation.
     */
    @Override
    public @NotNull Font getFont() {
        return this.font.awtFont();
    }

    /**
     * The {@link FontRenderContext} the backing font resolves under - {@link
     * MinecraftFont#fontRenderContext()}. Vanilla's rasterization context, or an identity-transform
     * antialiased default for a colour font.
     */
    @Override
    public @NotNull FontRenderContext getFontRenderContext() {
        return this.font.fontRenderContext();
    }

    /**
     * No-op: this vector's positions are precomputed pack data, so there is nothing to lay out. The
     * pens were fixed by {@link MinecraftFont#walk} at construction and never come from AWT default
     * layout.
     */
    @Override
    public void performDefaultLayout() {
        // Positions are pack data, fixed at layout time - nothing to (re)compute.
    }

    @Override
    public int getNumGlyphs() {
        return this.glyphs.size();
    }

    /**
     * The real glyph id of the glyph at an index. A colour raster glyph reports its sidecar
     * {@link MinecraftGlyph#gid() gid} straight from the row; a mono or space glyph (which carries no
     * sidecar gid) resolves its codepoint through {@link #getFont() the backing font}'s {@code cmap}
     * via {@link MinecraftFont#glyphCode(int)}, yielding {@code 0} ({@code .notdef}) when the font has
     * no glyph for it.
     *
     * @param glyphIndex the glyph index
     * @return the glyph id
     */
    @Override
    public int getGlyphCode(int glyphIndex) {
        MinecraftGlyph glyph = this.glyphs.get(glyphIndex);
        int gid = glyph.gid();
        return gid >= 0 ? gid : this.font.glyphCode(glyph.codepoint());
    }

    @Override
    public int[] getGlyphCodes(int beginGlyphIndex, int numEntries, int[] codeReturn) {
        if (numEntries < 0) throw new IllegalArgumentException("numEntries must not be negative: " + numEntries);
        int[] out = codeReturn != null && codeReturn.length >= numEntries ? codeReturn : new int[numEntries];
        for (int i = 0; i < numEntries; i++) out[i] = getGlyphCode(beginGlyphIndex + i);
        return out;
    }

    /**
     * The pen position of the glyph at an index, in output-pixel space (the native buffer space:
     * {@link MinecraftFont#MC_PIXEL_SCALE} output pixels per mcPixel), with {@code y = 0} on the
     * baseline. An index equal to {@link #getNumGlyphs()} returns the run's end pen ({@link #advanceX()
     * the total advance}), matching the {@code GlyphVector} contract.
     *
     * @param glyphIndex the glyph index, {@code 0..getNumGlyphs()}
     * @return the pen position, {@code y = 0}
     */
    @Override
    public @NotNull Point2D getGlyphPosition(int glyphIndex) {
        double x = glyphIndex == this.glyphs.size() ? this.advanceX : this.glyphs.get(glyphIndex).penX();
        return new Point2D.Float((float) x, 0f);
    }

    /**
     * The pen positions of a range of glyphs as {@code (x, y)} pairs, in the same output-pixel space
     * as {@link #getGlyphPosition(int)} ({@code y} always {@code 0}). A range that includes
     * {@link #getNumGlyphs()} contributes the run's end pen.
     *
     * @param beginGlyphIndex the first glyph index
     * @param numEntries the number of positions
     * @param positionReturn a reusable array of at least {@code numEntries * 2}, or {@code null}
     * @return the positions as {@code x, y, x, y, ...}
     */
    @Override
    public float[] getGlyphPositions(int beginGlyphIndex, int numEntries, float[] positionReturn) {
        if (numEntries < 0) throw new IllegalArgumentException("numEntries must not be negative: " + numEntries);
        float[] out = positionReturn != null && positionReturn.length >= numEntries * 2 ? positionReturn : new float[numEntries * 2];
        for (int i = 0; i < numEntries; i++) {
            int index = beginGlyphIndex + i;
            double x = index == this.glyphs.size() ? this.advanceX : this.glyphs.get(index).penX();
            out[i * 2] = (float) x;
            out[i * 2 + 1] = 0f;
        }
        return out;
    }

    /**
     * The run's logical bounds: a box from the baseline origin spanning the {@link #advanceX() total
     * advance} horizontally and the font's ascent/descent vertically (output pixels, {@code y}
     * negative above the baseline). Derived from {@link MinecraftFontMetrics the shared metrics}, so
     * it agrees with the measure path.
     */
    @Override
    public @NotNull Rectangle2D getLogicalBounds() {
        MinecraftFontMetrics metrics = this.font.metrics();
        double ascent = metrics.getAscent();
        double descent = metrics.getDescent();
        return new Rectangle2D.Double(0, -ascent, this.advanceX, ascent + descent);
    }

    /**
     * The run's visual (ink) bounds: the union of each drawn glyph's bitmap box at its pen. Space
     * glyphs contribute nothing (advance-only, no ink). Empty when the run has no inked glyph.
     */
    @Override
    public @NotNull Rectangle2D getVisualBounds() {
        Rectangle2D bounds = null;
        for (MinecraftGlyph glyph : this.glyphs) {
            if (glyph.kind() == Kind.SPACE) continue;   // advance-only, no ink
            Rectangle2D box = new Rectangle2D.Double(
                glyph.penX() + glyph.bearingX(), glyph.bearingY(), glyph.bitmap().width(), glyph.bitmap().height());
            bounds = bounds == null ? box : bounds.createUnion(box);
        }
        return bounds != null ? bounds : new Rectangle2D.Double(0, 0, 0, 0);
    }

    /**
     * The per-glyph metrics at an index, built cheaply and honestly from the pack data: the advance is
     * the glyph's {@link MinecraftGlyph#signedAdvance() signed advance}, and the bounds are the glyph's
     * bitmap box relative to its own origin (empty for a space glyph). The glyph type is
     * {@link GlyphMetrics#STANDARD}.
     *
     * @param glyphIndex the glyph index
     * @return the glyph metrics
     */
    @Override
    public @NotNull GlyphMetrics getGlyphMetrics(int glyphIndex) {
        MinecraftGlyph glyph = this.glyphs.get(glyphIndex);
        Rectangle2D bounds = glyph.kind() == Kind.SPACE
            ? new Rectangle2D.Float(0, 0, 0, 0)
            : new Rectangle2D.Float(glyph.bearingX(), glyph.bearingY(), glyph.bitmap().width(), glyph.bitmap().height());
        return new GlyphMetrics(glyph.signedAdvance(), bounds, GlyphMetrics.STANDARD);
    }

    /**
     * {@code null}: no glyph carries a per-glyph transform. This is the legal "no transform" answer
     * defined by the {@code GlyphVector} contract, not a stub.
     *
     * @param glyphIndex the glyph index
     * @return {@code null}
     */
    @Override
    public AffineTransform getGlyphTransform(int glyphIndex) {
        return null;
    }

    /**
     * Value equality over the backing font and the positioned glyph list.
     *
     * @param set the vector to compare against
     * @return whether the two vectors carry the same font and glyphs
     */
    @Override
    public boolean equals(GlyphVector set) {
        if (this == set) return true;
        if (!(set instanceof MinecraftGlyphVector other)) return false;
        return this.font.equals(other.font) && this.glyphs.equals(other.glyphs);
    }

    // --- java.awt.font.GlyphVector contract: stubbed dead surface ---

    @Override
    public @NotNull Shape getOutline() {
        throw outlineSurfaceUnsupported();   // we own the draw entry point; outlines are never read
    }

    @Override
    public @NotNull Shape getOutline(float x, float y) {
        throw outlineSurfaceUnsupported();   // we own the draw entry point; outlines are never read
    }

    @Override
    public @NotNull Shape getGlyphOutline(int glyphIndex) {
        throw outlineSurfaceUnsupported();   // we own the draw entry point; outlines are never read
    }

    @Override
    public @NotNull Shape getGlyphLogicalBounds(int glyphIndex) {
        throw outlineSurfaceUnsupported();   // per-glyph outline geometry is never read on the draw path
    }

    @Override
    public @NotNull Shape getGlyphVisualBounds(int glyphIndex) {
        throw outlineSurfaceUnsupported();   // per-glyph outline geometry is never read on the draw path
    }

    @Override
    public @NotNull GlyphJustificationInfo getGlyphJustificationInfo(int glyphIndex) {
        throw outlineSurfaceUnsupported();   // no justification: pack layout is fixed, not stretched
    }

    @Override
    public void setGlyphPosition(int glyphIndex, Point2D newPosition) {
        throw positionsAreImmutable();   // positions are pack data, not caller mutations
    }

    @Override
    public void setGlyphTransform(int glyphIndex, AffineTransform newTransform) {
        throw positionsAreImmutable();   // positions are pack data, not caller mutations
    }

    private static @NotNull UnsupportedOperationException outlineSurfaceUnsupported() {
        return new UnsupportedOperationException(
            "MinecraftGlyphVector does not expose glyph outlines: MinecraftGraphics owns the draw entry point and "
                + "blits the MinecraftGlyph bitmaps directly, so this surface is unreachable dead code. See the class javadoc.");
    }

    private static @NotNull UnsupportedOperationException positionsAreImmutable() {
        return new UnsupportedOperationException(
            "MinecraftGlyphVector positions are pack layout data, fixed at layout time and not caller-adjustable.");
    }

    /**
     * The kind of a positioned glyph.
     */
    public enum Kind {

        /**
         * A colour {@code sbix} strike blit (native RGBA, never tinted).
         */
        RASTER,

        /**
         * A space-provider advance - moves the pen, paints nothing.
         */
        SPACE,

        /**
         * A vanilla mono-atlas glyph (tinted white bitmap).
         */
        MONO

    }

}

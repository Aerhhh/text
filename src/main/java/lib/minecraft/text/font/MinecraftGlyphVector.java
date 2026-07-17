package lib.minecraft.text.font;

import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A laid-out run of text: its own positioning-and-painting type, deliberately NOT a
 * {@link java.awt.font.GlyphVector} subclass.
 * <p>
 * Painting is not polymorphic over {@code GlyphVector}: AWT does not paint the vector you hand it, it
 * extracts the glyph codes and positions and rasterizes through its own native pipeline - and that
 * pipeline paints an {@code sbix} strike blank while zeroing the advances (both probe-proven). Nor
 * can the abstract contract - vector outlines, point-space {@link java.awt.font.GlyphMetrics}, a
 * backing {@link java.awt.Font} - be satisfied honestly for empty-{@code glyf} raster glyphs whose
 * pixels live only in {@code sbix}. And the one renderer that consumes this type,
 * {@link MinecraftGraphics}, blits from {@link MinecraftGlyph} bitmaps and never calls
 * {@code drawGlyphVector}. Extending {@code GlyphVector} would therefore only make broken AWT calls
 * compile while adding dead API surface, so this type owns positioning and painting itself.
 * <p>
 * It does so uniformly for both {@link MinecraftFont} kinds: layout walks codepoints, resolves each
 * through {@link MinecraftFont#glyph(int)}, and accumulates pens from
 * {@link MinecraftGlyph#signedAdvance()}. There is no vanilla-vs-colour branch - a mono atlas glyph,
 * a colour {@code sbix} strike, and a space provider all arrive as a {@link MinecraftGlyph} whose
 * {@link MinecraftGlyph#kind() kind} drives the draw path.
 * <p>
 * Because the very same {@link MinecraftFont#glyph(int)} feeds {@link MinecraftFontMetrics}, the
 * measure path and the draw path agree exactly: {@link #advanceX()} equals
 * {@link MinecraftFontMetrics#stringAdvanceX(String)} for the same text. Advances accumulate as
 * {@code double} so fractional and negative pens (space providers) are exact; rounding happens only
 * at blit time.
 */
public final class MinecraftGlyphVector {

    private final @NotNull MinecraftFont font;
    private final @NotNull List<MinecraftGlyph> glyphs;
    private final double advanceX;

    private MinecraftGlyphVector(@NotNull MinecraftFont font, @NotNull List<MinecraftGlyph> glyphs, double advanceX) {
        this.font = font;
        this.glyphs = glyphs;
        this.advanceX = advanceX;
    }

    /**
     * Lays out a run of text for any font. The single layout entry point for both font kinds.
     * <p>
     * Each codepoint resolves through {@link MinecraftFont#glyph(int)}: a vanilla atlas glyph, a
     * colour {@code sbix} strike, or a space provider all arrive as a {@link MinecraftGlyph}. Pen
     * positions accumulate from {@link MinecraftGlyph#signedAdvance()} - never from
     * {@link java.awt.font.GlyphVector#getGlyphPosition}, which {@code sbix} zeroes - so the loop is
     * identical whichever kind the font is. Each cached glyph is stamped with its pen through
     * {@link MinecraftGlyph#at(double)}.
     *
     * @param font the font to lay the text out in
     * @param text the text to lay out
     * @return the positioned glyph vector
     */
    static @NotNull MinecraftGlyphVector of(@NotNull MinecraftFont font, @NotNull String text) {
        List<MinecraftGlyph> glyphs = new ArrayList<>();

        double pen = 0.0;
        int i = 0;
        while (i < text.length()) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);

            MinecraftGlyph glyph = font.glyph(codepoint);
            glyphs.add(glyph.at(pen));
            pen += glyph.signedAdvance();
        }

        return new MinecraftGlyphVector(font, List.copyOf(glyphs), pen);
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
     * @return the font this vector was laid out for
     */
    public @NotNull MinecraftFont font() {
        return this.font;
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

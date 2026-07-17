package lib.minecraft.text.font;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * An {@code sbix} table makes Java2D zero every {@code GlyphVector} advance and paint nothing, so a
 * wrapper around the AWT type would buy only zeroed metrics and a blank raster. Instead this type
 * owns both jobs itself, and it does so uniformly for both {@link MinecraftFont} kinds: layout walks
 * codepoints, resolves each through {@link MinecraftFont#glyph(int)}, and accumulates pens from
 * {@link MinecraftFont.GlyphData#signedAdvance()}. There is no vanilla-vs-colour branch - a mono
 * atlas glyph, a colour {@code sbix} strike, and a space provider all arrive as a
 * {@link MinecraftFont.GlyphData} whose {@link MinecraftFont.GlyphData#kind() kind} drives the draw
 * path.
 * <p>
 * Because the very same {@link MinecraftFont#glyph(int)} feeds {@link MinecraftFontMetrics}, the
 * measure path and the draw path agree exactly: {@link #advanceX()} equals
 * {@link MinecraftFontMetrics#stringAdvanceX(String)} for the same text. Advances accumulate as
 * {@code double} so fractional and negative pens (space providers) are exact; rounding happens only
 * at blit time.
 */
public final class MinecraftGlyphVector {

    private final @NotNull MinecraftFont font;
    private final @NotNull List<PositionedGlyph> glyphs;
    private final double advanceX;

    private MinecraftGlyphVector(@NotNull MinecraftFont font, @NotNull List<PositionedGlyph> glyphs, double advanceX) {
        this.font = font;
        this.glyphs = glyphs;
        this.advanceX = advanceX;
    }

    /**
     * Lays out a run of text for any font. The single layout entry point for both font kinds.
     * <p>
     * Each codepoint resolves through {@link MinecraftFont#glyph(int)}: a vanilla atlas glyph, a
     * colour {@code sbix} strike, or a space provider all arrive as a {@link MinecraftFont.GlyphData}.
     * Pen positions accumulate from {@link MinecraftFont.GlyphData#signedAdvance()} - never from
     * {@link java.awt.font.GlyphVector#getGlyphPosition}, which {@code sbix} zeroes - so the loop is
     * identical whichever kind the font is.
     *
     * @param font the font to lay the text out in
     * @param text the text to lay out
     * @return the positioned glyph vector
     */
    static @NotNull MinecraftGlyphVector of(@NotNull MinecraftFont font, @NotNull String text) {
        List<PositionedGlyph> glyphs = new ArrayList<>();

        double pen = 0.0;
        int i = 0;
        while (i < text.length()) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);

            MinecraftFont.GlyphData glyph = font.glyph(codepoint);
            double advance = glyph.signedAdvance();
            glyphs.add(new PositionedGlyph(
                codepoint, glyph.kind(), pen, advance, glyph,
                glyph.gid(), glyph.strikePpem(), glyph.originX(), glyph.originY()));
            pen += advance;
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
     * Returns the positioned glyph at an index.
     *
     * @param index the glyph index
     * @return the positioned glyph
     */
    public @NotNull PositionedGlyph positionedGlyph(int index) {
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
        PositionedGlyph glyph = this.glyphs.get(index);
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

    /**
     * A single positioned glyph in a laid-out run.
     *
     * @param codepoint the Unicode codepoint
     * @param kind the glyph kind
     * @param penX the cumulative pen position in output pixels (may be negative)
     * @param advance the signed advance in output pixels
     * @param glyph the resolved glyph data (never {@code null}; a space provider is a
     * {@link Kind#SPACE} sentinel)
     * @param gid the strike glyph id, or {@code -1} for non-raster glyphs
     * @param strikePpem the strike ppem, or {@code -1} for non-raster glyphs
     * @param originX the origin X offset in output pixels
     * @param originY the origin Y offset in output pixels
     */
    public record PositionedGlyph(
        int codepoint,
        @NotNull Kind kind,
        double penX,
        double advance,
        @Nullable MinecraftFont.GlyphData glyph,
        int gid,
        int strikePpem,
        int originX,
        int originY
    ) {}

}

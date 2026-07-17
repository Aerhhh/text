package lib.minecraft.text.font;

import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A laid-out run of pack colour text: its own positioning-and-painting type, deliberately NOT a
 * {@link java.awt.font.GlyphVector} subclass.
 * <p>
 * An {@code sbix} table makes Java2D zero every {@code GlyphVector} advance and paint nothing, so a
 * wrapper around the AWT type would buy only zeroed metrics and a blank raster. Instead this type
 * owns both jobs itself: it lays out pens from the <em>sidecar</em> advances (raster and space
 * glyphs) and the vanilla {@link MinecraftFont.GlyphData#advanceWidth() atlas advances} (mono
 * fallback), and it paints by blitting the {@code sbix} strike PNG for raster glyphs (native RGBA,
 * never tinted) and the tinted white atlas for mono glyphs. The AWT {@code GlyphVector} is used
 * only to source mono glyph outlines for the knockout hook.
 * <p>
 * Measurement and painting share one advance source ({@link ColorFontMetrics#advanceOf(int)}), so
 * {@link #advanceX()} equals {@link ColorFontMetrics#stringAdvanceX(String)} - the measure-equals-
 * draw invariant. Advances accumulate as {@code double} so fractional and negative pens (space
 * providers) are exact; rounding happens only at blit time.
 */
public final class MinecraftGlyphVector {

    private final @NotNull MinecraftColorFont font;
    private final @NotNull List<PositionedGlyph> glyphs;
    private final double advanceX;

    private MinecraftGlyphVector(@NotNull MinecraftColorFont font, @NotNull List<PositionedGlyph> glyphs, double advanceX) {
        this.font = font;
        this.glyphs = glyphs;
        this.advanceX = advanceX;
    }

    /**
     * Lays out a run of text for a colour font.
     * <p>
     * Each codepoint resolves against the font's sidecar: a raster row blits from the {@code sbix}
     * strike, a space row advances only (possibly backward), and an unlisted codepoint falls back
     * to the vanilla mono atlas. Pen positions come exclusively from these advances - never from
     * {@link GlyphVector#getGlyphPosition}, which {@code sbix} zeroes.
     *
     * @param font the colour font
     * @param text the text to lay out
     * @return the positioned glyph vector
     */
    public static @NotNull MinecraftGlyphVector layout(@NotNull MinecraftColorFont font, @NotNull String text) {
        ColorGlyphSidecar sidecar = font.sidecar();
        int unitsPerEm = sidecar.unitsPerEm();
        List<PositionedGlyph> glyphs = new ArrayList<>();

        double pen = 0.0;
        int i = 0;
        while (i < text.length()) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);

            Optional<GlyphRow> rowOptional = sidecar.lookup(font.fontId(), codepoint);
            if (rowOptional.isPresent()) {
                GlyphRow row = rowOptional.get();
                double advance = FontUnits.toOutputPixels(row.advance(), unitsPerEm);
                if (row.isSpace()) {
                    glyphs.add(new PositionedGlyph(codepoint, Kind.SPACE, pen, advance, null, -1, -1, 0, 0));
                } else {
                    int ppem = resolvePpem(font, row);
                    int originX = (int) Math.round(FontUnits.toOutputPixels(row.originX(), unitsPerEm));
                    int originY = (int) Math.round(FontUnits.toOutputPixels(row.originY(), unitsPerEm));
                    MinecraftFont.GlyphData glyph = font.strikes().strike(row.gid(), ppem)
                        .map(image -> MinecraftFont.GlyphData.color(PixelBuffer.wrap(toArgb(image)), (float) advance, originX, originY))
                        .orElse(null);
                    glyphs.add(new PositionedGlyph(codepoint, Kind.RASTER, pen, advance, glyph, row.gid(), ppem, originX, originY));
                }
                pen += advance;
            } else {
                MinecraftFont.GlyphData glyph = font.monoFallback().glyph(codepoint);
                double advance = glyph.advanceWidth();
                glyphs.add(new PositionedGlyph(codepoint, Kind.MONO, pen, advance, glyph, -1, -1, 0, 0));
                pen += advance;
            }
        }

        return new MinecraftGlyphVector(font, List.copyOf(glyphs), pen);
    }

    private static int resolvePpem(@NotNull MinecraftColorFont font, @NotNull GlyphRow row) {
        Integer declared = row.strikePpem();
        if (declared != null) return declared;
        int[] available = font.strikes().reader().strikePpems();
        return available.length > 0 ? available[0] : 0;
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
     * {@link ColorFontMetrics#stringAdvanceX(String)} for the same text.
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
     * Raster and space glyphs have no fillable outline (empty {@code glyf}) and return empty.
     *
     * @param index the glyph index
     * @return the translated outline, or empty for non-mono glyphs
     */
    public @NotNull Optional<Shape> outline(int index) {
        PositionedGlyph glyph = this.glyphs.get(index);
        if (glyph.kind() != Kind.MONO) return Optional.empty();

        MinecraftFont mono = this.font.monoFallback();
        FontRenderContext frc = mono.getFontMetrics().getAwtFrc();
        GlyphVector vector = mono.getActual().createGlyphVector(frc, new String(Character.toChars(glyph.codepoint())));
        Shape outline = vector.getGlyphOutline(0);
        return Optional.of(AffineTransform.getTranslateInstance(glyph.penX(), 0).createTransformedShape(outline));
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
     * @return the colour font this vector was laid out for
     */
    public @NotNull MinecraftColorFont font() {
        return this.font;
    }

    private static @NotNull BufferedImage toArgb(@NotNull BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) return source;
        BufferedImage argb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        argb.getGraphics().drawImage(source, 0, 0, null);
        return argb;
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
         * A vanilla mono-atlas fallback glyph (tinted white bitmap).
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
     * @param glyph the resolved glyph data, or {@code null} for space glyphs and missing strikes
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

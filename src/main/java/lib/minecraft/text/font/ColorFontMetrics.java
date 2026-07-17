package lib.minecraft.text.font;

import org.jetbrains.annotations.NotNull;

/**
 * Advance and line metrics for a single pack colour font, bound to one {@link FontId}.
 * <p>
 * Every advance flows through {@link #advanceOf(int)}: a codepoint listed in the sidecar for this
 * font id takes its signed sidecar advance (converted once via {@link FontUnits#toOutputPixels}),
 * and any other codepoint falls back to the vanilla {@link MinecraftFont} atlas. Because
 * {@link MinecraftGlyphVector} lays out from the very same method, the measure path and the draw
 * path agree exactly - {@code stringAdvanceX} equals the vector's total advance.
 */
public final class ColorFontMetrics {

    private final @NotNull FontId fontId;
    private final @NotNull ColorGlyphSidecar sidecar;
    private final @NotNull MinecraftFont monoFallback;

    ColorFontMetrics(@NotNull FontId fontId, @NotNull ColorGlyphSidecar sidecar, @NotNull MinecraftFont monoFallback) {
        this.fontId = fontId;
        this.sidecar = sidecar;
        this.monoFallback = monoFallback;
    }

    /**
     * The signed advance of a single codepoint in output pixels. Sidecar rows win; otherwise the
     * vanilla mono-fallback glyph advance is used.
     *
     * @param codepoint the Unicode codepoint
     * @return the advance in output pixels (may be negative or fractional for sidecar rows)
     */
    public double advanceOf(int codepoint) {
        return this.sidecar.lookup(this.fontId, codepoint)
            .map(row -> FontUnits.toOutputPixels(row.advance(), this.sidecar.unitsPerEm()))
            .orElseGet(() -> (double) this.monoFallback.glyph(codepoint).advanceWidth());
    }

    /**
     * Alias for {@link #advanceOf(int)} matching the design's metric vocabulary.
     *
     * @param codepoint the Unicode codepoint
     * @return the advance in output pixels
     */
    public double advanceX(int codepoint) {
        return advanceOf(codepoint);
    }

    /**
     * The total signed advance of a string in output pixels, summed over its codepoints. Iterates
     * by codepoint (not char) so supplementary-plane PUA glyphs measure correctly.
     *
     * @param text the text to measure
     * @return the total advance in output pixels
     */
    public double stringAdvanceX(@NotNull String text) {
        double total = 0.0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            total += advanceOf(cp);
            i += Character.charCount(cp);
        }
        return total;
    }

    /**
     * The rounded, AWT-compatible integer advance of a single codepoint in output pixels.
     *
     * @param codepoint the Unicode codepoint
     * @return the rounded advance
     */
    public int charWidth(int codepoint) {
        return Math.round((float) advanceOf(codepoint));
    }

    /**
     * The line height in mcPixels, taken from the mono fallback so colour and vanilla text share a
     * baseline grid.
     *
     * @return the line height in mcPixels
     */
    public int lineHeightMcPixels() {
        return this.monoFallback.getFontMetrics().getHeightMcPixels();
    }

    /**
     * @return the font id these metrics are bound to
     */
    public @NotNull FontId fontId() {
        return this.fontId;
    }

}

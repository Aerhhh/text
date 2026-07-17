package lib.minecraft.text.font;

import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.awt.Font;
import java.awt.FontMetrics;

/**
 * A {@link FontMetrics} implementation shared by both {@link MinecraftFont} kinds. Every advance
 * flows through {@link #advanceOf(int)} - {@code font.glyph(cp).signedAdvance()} - so the measure
 * path and the layout/draw path agree exactly: {@link #stringAdvanceX(String)} equals the
 * {@link MinecraftGlyphVector#advanceX() vector advance} for the same text.
 * <p>
 * The AWT rendering context is not held here: it lives on {@link MinecraftFont.Vanilla}, the only
 * kind that rasterizes glyphs. This type carries just the underlying AWT {@link Font} (needed for
 * the {@link FontMetrics} superclass) and the cached line geometry; the overridden measurement
 * methods make the superclass font inert.
 */
@Getter
public final class MinecraftFontMetrics extends FontMetrics {

    @Getter(AccessLevel.NONE)
    private final @NotNull MinecraftFont mcFont;

    private final int ascent;
    private final int descent;
    private final int height;

    /**
     * Builds metrics bound to a font. The {@link FontMetrics} superclass requires the AWT font at
     * construction, so this is the only entry point - there is no separate factory.
     *
     * @param mcFont the font these metrics measure
     * @param awtFont the underlying AWT font (vanilla's own font, or a colour font's mono-fallback
     * font) passed to the {@link FontMetrics} superclass
     * @param ascent the ascent in output pixels
     * @param descent the descent in output pixels
     * @param height the line height in output pixels
     */
    public MinecraftFontMetrics(@NotNull MinecraftFont mcFont, @NotNull Font awtFont, int ascent, int descent, int height) {
        super(awtFont);
        this.mcFont = mcFont;
        this.ascent = ascent;
        this.descent = descent;
        this.height = height;
    }

    /**
     * The signed advance of a single codepoint in output pixels, taken from the glyph itself. May be
     * negative or fractional for colour space/raster glyphs; equals the integer advance for mono
     * glyphs.
     *
     * @param codepoint the Unicode codepoint
     * @return the signed advance in output pixels
     */
    public double advanceOf(int codepoint) {
        return this.mcFont.glyph(codepoint).signedAdvance();
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
     * The total signed advance of a string in output pixels, summed over its codepoints. Iterates by
     * codepoint (not char) so supplementary-plane PUA glyphs measure correctly.
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

    @Override
    public int stringWidth(@NotNull String str) {
        return Math.round((float) stringAdvanceX(str));
    }

    @Override
    public int charWidth(int codepoint) {
        return Math.round((float) advanceOf(codepoint));
    }

    @Override
    public int charWidth(char ch) {
        return Math.round((float) advanceOf(ch));
    }

    /**
     * Returns the ascent in mcPixels - the logical font unit where one mcPixel equals
     * {@link MinecraftFont#MC_PIXEL_SCALE} native output pixels.
     *
     * @return the ascent in mcPixels
     */
    public int getAscentMcPixels() {
        return this.ascent / MinecraftFont.MC_PIXEL_SCALE;
    }

    /**
     * Returns the descent in mcPixels.
     *
     * @return the descent in mcPixels
     */
    public int getDescentMcPixels() {
        return this.descent / MinecraftFont.MC_PIXEL_SCALE;
    }

    /**
     * Returns the total line height (ascent + descent + leading) in mcPixels.
     *
     * @return the height in mcPixels
     */
    public int getHeightMcPixels() {
        return this.height / MinecraftFont.MC_PIXEL_SCALE;
    }

    /**
     * The line height in mcPixels. Alias for {@link #getHeightMcPixels()} matching the colour-font
     * metric vocabulary, so colour and vanilla text share a baseline grid.
     *
     * @return the line height in mcPixels
     */
    public int lineHeightMcPixels() {
        return getHeightMcPixels();
    }

    /**
     * @return the font id these metrics are bound to
     */
    public @NotNull FontId fontId() {
        return this.mcFont.fontId();
    }

}

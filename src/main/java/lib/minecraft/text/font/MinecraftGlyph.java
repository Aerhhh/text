package lib.minecraft.text.font;

import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

/**
 * One glyph, end to end: the resolved bitmap and positioning metrics a font hands back from
 * {@link MinecraftFont#glyph(int)}, plus the pen position a layout assigns to a single occurrence of
 * it. The same type is what the per-font glyph cache stores, what
 * {@link MinecraftGlyphVector#of(MinecraftFont, String) layout} walks, and what
 * {@link MinecraftGraphics} blits - there is no separate "glyph data" versus "positioned glyph"
 * split.
 * <p>
 * The {@link #kind()} drives the draw path, and every kind is reached through the same
 * {@link MinecraftFont#glyph(int)} surface:
 * <ul>
 *   <li>{@link MinecraftGlyphVector.Kind#MONO} - a vanilla white-on-transparent atlas bitmap, tinted
 *   at draw time; {@link #signedAdvance} equals the integer {@link #advanceWidth}. The five-argument
 *   constructor produces this form and is the only shape the vanilla rasterizer uses, so the mono
 *   path is unchanged byte-for-byte.</li>
 *   <li>{@link MinecraftGlyphVector.Kind#RASTER} - a native RGBA colour ({@code sbix}) strike,
 *   blitted untinted; it may carry a fractional/negative {@link #signedAdvance}, a non-zero origin,
 *   and the {@link #gid}/{@link #strikePpem} the artwork came from.</li>
 *   <li>{@link MinecraftGlyphVector.Kind#SPACE} - an advance-only sentinel over a 1x1 transparent
 *   bitmap; it moves the pen (possibly backward) and paints nothing.</li>
 * </ul>
 * <p>
 * A resolved glyph is never {@code null}. A codepoint the pack does not define falls back to the
 * vanilla mono atlas; a pack space provider is a {@link MinecraftGlyphVector.Kind#SPACE} sentinel;
 * and a colour row whose strike fails to decode degrades to that same advance-only sentinel (the pen
 * still moves, nothing is painted). There is no path that yields a null glyph.
 * <p>
 * <strong>Pen position.</strong> {@link #penX} is the cumulative pen at which this occurrence sits,
 * in output pixels; it may be negative once a space provider walks the pen backward. The per-font
 * cache stores one canonical instance per codepoint with {@code penX == 0}; a layout stamps each
 * occurrence with {@link #at(double)}, which returns a copy carrying only the pen (records have no
 * withers). A cached glyph is a value, never used as a map key, so the extra field never affects
 * cache identity.
 *
 * @param codepoint the Unicode codepoint this glyph renders
 * @param bitmap the glyph pixels (white-on-transparent for mono, native RGBA for colour, 1x1
 * transparent for space)
 * @param advanceWidth the integer horizontal cursor advance after this glyph, in output pixels
 * @param bearingX the left bearing - horizontal offset from cursor to left edge of bitmap
 * @param bearingY the top bearing - vertical offset from baseline to top edge of bitmap
 * @param color whether the bitmap is native colour artwork (never tinted) rather than mono
 * @param signedAdvance the signed, possibly fractional advance in output pixels; equals
 * {@link #advanceWidth} for mono glyphs
 * @param originX the glyph origin X offset in output pixels (0 for mono/space glyphs)
 * @param originY the glyph origin Y offset in output pixels (0 for mono/space glyphs)
 * @param gid the {@code sbix} glyph id the strike came from, or {@code -1} for mono/space glyphs
 * @param strikePpem the {@code sbix} strike ppem, or {@code -1} for mono/space glyphs
 * @param kind the glyph kind driving the draw path
 * @param penX the cumulative pen position of this occurrence in output pixels (may be negative);
 * {@code 0} for the canonical cached instance
 */
public record MinecraftGlyph(
    int codepoint,
    @NotNull PixelBuffer bitmap,
    int advanceWidth,
    int bearingX,
    int bearingY,
    boolean color,
    float signedAdvance,
    int originX,
    int originY,
    int gid,
    int strikePpem,
    MinecraftGlyphVector.@NotNull Kind kind,
    double penX
) {

    /**
     * A shared 1x1 fully-transparent bitmap backing every {@link MinecraftGlyphVector.Kind#SPACE}
     * sentinel. Immutable in practice - the blit path only reads glyph bitmaps - so one instance is
     * safe to share.
     */
    private static final @NotNull PixelBuffer SPACE_BITMAP = PixelBuffer.create(1, 1);

    /**
     * Constructs a monochrome glyph: {@code color = false}, {@code signedAdvance = advanceWidth}, a
     * zero origin, a zero {@link #penX}, and {@link MinecraftGlyphVector.Kind#MONO}. This is the
     * vanilla rasterization form.
     *
     * @param codepoint the Unicode codepoint
     * @param bitmap the white-on-transparent glyph pixels
     * @param advanceWidth the integer horizontal cursor advance
     * @param bearingX the left bearing
     * @param bearingY the top bearing
     */
    public MinecraftGlyph(int codepoint, @NotNull PixelBuffer bitmap, int advanceWidth, int bearingX, int bearingY) {
        this(codepoint, bitmap, advanceWidth, bearingX, bearingY, false, advanceWidth, 0, 0, -1, -1, MinecraftGlyphVector.Kind.MONO, 0.0);
    }

    /**
     * Constructs a colour glyph from a native RGBA bitmap and sidecar-sourced positioning, without
     * strike provenance.
     *
     * @param codepoint the Unicode codepoint
     * @param bitmap the native RGBA artwork
     * @param signedAdvance the signed, possibly fractional advance in output pixels
     * @param originX the origin X offset in output pixels
     * @param originY the origin Y offset in output pixels
     * @return the colour glyph
     */
    public static @NotNull MinecraftGlyph color(int codepoint, @NotNull PixelBuffer bitmap, float signedAdvance, int originX, int originY) {
        return color(codepoint, bitmap, signedAdvance, originX, originY, -1, -1);
    }

    /**
     * Constructs a colour glyph from a native RGBA bitmap, sidecar-sourced positioning, and the
     * {@code sbix} strike it was decoded from. The origin doubles as the blit bearing.
     *
     * @param codepoint the Unicode codepoint
     * @param bitmap the native RGBA artwork
     * @param signedAdvance the signed, possibly fractional advance in output pixels
     * @param originX the origin X offset in output pixels
     * @param originY the origin Y offset in output pixels
     * @param gid the strike glyph id
     * @param strikePpem the strike ppem
     * @return the colour glyph
     */
    public static @NotNull MinecraftGlyph color(int codepoint, @NotNull PixelBuffer bitmap, float signedAdvance, int originX, int originY, int gid, int strikePpem) {
        return new MinecraftGlyph(codepoint, bitmap, Math.round(signedAdvance), originX, originY, true, signedAdvance, originX, originY, gid, strikePpem, MinecraftGlyphVector.Kind.RASTER, 0.0);
    }

    /**
     * Constructs an advance-only {@link MinecraftGlyphVector.Kind#SPACE} sentinel: a 1x1 transparent
     * bitmap that moves the pen by {@code signedAdvance} (possibly backward) and paints nothing.
     *
     * @param codepoint the Unicode codepoint
     * @param signedAdvance the signed, possibly fractional advance in output pixels
     * @return the space glyph
     */
    public static @NotNull MinecraftGlyph space(int codepoint, float signedAdvance) {
        return new MinecraftGlyph(codepoint, SPACE_BITMAP, Math.round(signedAdvance), 0, 0, false, signedAdvance, 0, 0, -1, -1, MinecraftGlyphVector.Kind.SPACE, 0.0);
    }

    /**
     * Returns a copy of this glyph positioned at {@code penX}. Layout calls this once per occurrence
     * to stamp a canonical cached glyph (which carries {@code penX == 0}) with its pen; every other
     * field is carried through unchanged.
     *
     * @param penX the cumulative pen position in output pixels (may be negative)
     * @return a copy of this glyph at the given pen
     */
    public @NotNull MinecraftGlyph at(double penX) {
        return new MinecraftGlyph(codepoint, bitmap, advanceWidth, bearingX, bearingY, color, signedAdvance, originX, originY, gid, strikePpem, kind, penX);
    }

}

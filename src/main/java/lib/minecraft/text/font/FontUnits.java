package lib.minecraft.text.font;

/**
 * The single font-unit conversion shared by colour-glyph layout and metrics.
 * <p>
 * Sidecar advances and origins are expressed in font units where {@code unitsPerEm / 8} units make
 * one mcPixel (the font generator bakes {@code 128 units = 1 mcPixel} at {@code unitsPerEm = 1024}).
 * Converting once, in one place, is what keeps the measure path and the draw path in exact
 * agreement - the {@code measure == draw} invariant depends on both summing the same values.
 */
public final class FontUnits {

    private FontUnits() {}

    /**
     * Converts a value in font units to output (buffer) pixels - the same space
     * {@link MinecraftFont.GlyphData#advanceWidth() mono advances} and the
     * {@link MinecraftGraphics#drawString drawString cursor} live in.
     * <p>
     * The conversion is {@code units / (unitsPerEm / 8) * }{@link MinecraftFont#MC_PIXEL_SCALE}:
     * the first step yields mcPixels, the second scales mcPixels to native output pixels. The
     * result is kept as a {@code double} so fractional and negative advances accumulate without
     * per-glyph rounding; callers round only at blit time.
     *
     * @param units the value in font units (may be negative or fractional)
     * @param unitsPerEm the sidecar units-per-em
     * @return the value in output pixels
     */
    public static double toOutputPixels(double units, int unitsPerEm) {
        double mcPixels = units / (unitsPerEm / 8.0);
        return mcPixels * MinecraftFont.MC_PIXEL_SCALE;
    }

}

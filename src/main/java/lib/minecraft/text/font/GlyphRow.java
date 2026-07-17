package lib.minecraft.text.font;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

/**
 * A single colour-glyph sidecar row, keyed by {@code (font_id, codepoint)}.
 * <p>
 * The row carries what a vanilla {@code cmap} plus {@code uint16 hmtx} cannot: the signed - and
 * possibly fractional - advance, the glyph origin, and the {@code sbix} strike the artwork lives
 * in. Two row shapes exist:
 * <ul>
 *   <li><b>raster</b> rows have a non-null {@link #gid()} and {@link #strikePpem()} and point at a
 *   PNG strike in the per-font {@code .ttf}.</li>
 *   <li><b>space</b> rows have a null {@link #gid()} and {@link #strikePpem()}; they carry only a
 *   signed advance (space providers, which may be negative or fractional) and mint no glyph.</li>
 * </ul>
 * The {@link #gid()} is the authoritative cross-reference into the font's glyph order - glyph
 * names are renamed to {@code uniXXXX} when a {@code post} format 3.0 font is reopened, so a row's
 * {@link #glyphName()} is advisory only.
 *
 * @param fontId the owning font id
 * @param codepoint the original Unicode codepoint this row maps (the pack's own codepoint)
 * @param storedCodepoint the synthetic plane-15/16 codepoint the merged font actually carries in
 * its {@code cmap}, or {@code null} for space rows; distinct font ids that reuse an original
 * codepoint get distinct stored codepoints, which is how one merged font resolves the collision
 * @param glyphName the advisory glyph name, or {@code null} for space rows
 * @param gid the glyph id in the font's glyph order, or {@code null} for space rows
 * @param advance the signed advance in font units (verbatim; may be negative or fractional)
 * @param originX the glyph origin X in font units
 * @param originY the glyph origin Y in font units
 * @param strikePpem the {@code sbix} strike ppem the artwork lives in, or {@code null} for space rows
 * @param unknown any sidecar members not modelled here, retained verbatim for forward compatibility
 */
public record GlyphRow(
    @NotNull FontId fontId,
    int codepoint,
    @Nullable Integer storedCodepoint,
    @Nullable String glyphName,
    @Nullable Integer gid,
    double advance,
    int originX,
    int originY,
    @Nullable Integer strikePpem,
    @NotNull Map<String, JsonElement> unknown
) {

    /**
     * Whether this is a space-provider row - advance only, no glyph or strike.
     *
     * @return {@code true} when {@link #gid()} is {@code null}
     */
    public boolean isSpace() {
        return this.gid == null;
    }

    /**
     * The glyph id as an {@link Optional}, empty for space rows.
     *
     * @return the glyph id, or empty
     */
    public @NotNull Optional<Integer> gidOptional() {
        return Optional.ofNullable(this.gid);
    }

    /**
     * The strike ppem as an {@link Optional}, empty for space rows.
     *
     * @return the strike ppem, or empty
     */
    public @NotNull Optional<Integer> strikePpemOptional() {
        return Optional.ofNullable(this.strikePpem);
    }

    /**
     * The stored codepoint as an {@link Optional}, empty for space rows.
     *
     * @return the stored codepoint, or empty
     */
    public @NotNull Optional<Integer> storedCodepointOptional() {
        return Optional.ofNullable(this.storedCodepoint);
    }

}

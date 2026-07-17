package lib.minecraft.text.font;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parsed, immutable view of a {@code colour-glyphs.json} sidecar.
 * <p>
 * The sidecar is the versioned, forward-compatible contract between the font generator and the
 * runtime. It carries the per-{@code (font_id, codepoint)} rows the vanilla font tables cannot
 * express - signed and fractional advances, glyph origins, and the {@code sbix} strike each glyph
 * lives in - plus the {@code font_id -> file} mapping for the per-font {@code .ttf} files.
 * <p>
 * Parsing is tolerant: any member not modelled here is retained verbatim (see
 * {@link GlyphRow#unknown()} and {@link #unknown()}) so a newer generator can add optional fields
 * without breaking an older reader. A {@code schema_version} above {@link #MAX_SUPPORTED_SCHEMA}
 * is rejected with a named {@link UnsupportedSchemaException}.
 */
public final class ColorGlyphSidecar {

    /**
     * The highest {@code schema_version} this reader understands. A sidecar declaring a higher
     * version is rejected fail-loud, because a bumped version signals a breaking shape change
     * (additive optional fields keep the version and are absorbed by the unknown-field tolerance).
     */
    public static final int MAX_SUPPORTED_SCHEMA = 1;

    /**
     * Default units-per-em assumed when a sidecar omits {@code units_per_em}. Mirrors the font
     * generator's {@code UNITS_PER_EM} so advances scale to the same mcPixel width as
     * {@link MinecraftFont}.
     */
    public static final int DEFAULT_UNITS_PER_EM = 1024;

    private final int schemaVersion;
    private final @Nullable String generatorVersion;
    private final int unitsPerEm;
    private final @NotNull String graphicType;
    private final @NotNull Map<FontId, String> files;
    private final @NotNull Map<FontIdCp, GlyphRow> index;
    private final @NotNull Map<String, JsonElement> unknown;

    private ColorGlyphSidecar(
        int schemaVersion,
        @Nullable String generatorVersion,
        int unitsPerEm,
        @NotNull String graphicType,
        @NotNull Map<FontId, String> files,
        @NotNull Map<FontIdCp, GlyphRow> index,
        @NotNull Map<String, JsonElement> unknown
    ) {
        this.schemaVersion = schemaVersion;
        this.generatorVersion = generatorVersion;
        this.unitsPerEm = unitsPerEm;
        this.graphicType = graphicType;
        this.files = files;
        this.index = index;
        this.unknown = unknown;
    }

    /**
     * Parses a sidecar from a JSON reader.
     *
     * @param json the JSON source
     * @return the parsed, immutable sidecar
     * @throws UnsupportedSchemaException when {@code schema_version} exceeds {@link #MAX_SUPPORTED_SCHEMA}
     */
    public static @NotNull ColorGlyphSidecar parse(@NotNull Reader json) {
        JsonObject root = JsonParser.parseReader(json).getAsJsonObject();

        int schemaVersion = root.has("schema_version") ? root.get("schema_version").getAsInt() : 0;
        if (schemaVersion > MAX_SUPPORTED_SCHEMA)
            throw new UnsupportedSchemaException(schemaVersion, MAX_SUPPORTED_SCHEMA);

        String generatorVersion = root.has("generator_version") ? root.get("generator_version").getAsString() : null;
        int unitsPerEm = root.has("units_per_em") ? root.get("units_per_em").getAsInt() : DEFAULT_UNITS_PER_EM;
        String graphicType = root.has("graphic_type") ? root.get("graphic_type").getAsString() : "png ";

        Map<FontId, String> files = new HashMap<>();
        if (root.has("fonts")) {
            for (JsonElement fontElement : root.getAsJsonArray("fonts")) {
                JsonObject font = fontElement.getAsJsonObject();
                if (!font.has("font_id") || !font.has("file")) continue;
                files.put(FontId.parse(font.get("font_id").getAsString()), font.get("file").getAsString());
            }
        }

        Map<FontIdCp, GlyphRow> index = new HashMap<>();
        if (root.has("glyphs")) {
            for (JsonElement glyphElement : root.getAsJsonArray("glyphs")) {
                GlyphRow row = parseRow(glyphElement.getAsJsonObject());
                index.put(new FontIdCp(row.fontId(), row.codepoint()), row);
            }
        }

        Map<String, JsonElement> unknown = retainUnknown(root, TOP_LEVEL_KNOWN);
        return new ColorGlyphSidecar(schemaVersion, generatorVersion, unitsPerEm, graphicType, files, index, unknown);
    }

    private static @NotNull GlyphRow parseRow(@NotNull JsonObject glyph) {
        FontId fontId = FontId.parse(glyph.get("font_id").getAsString());
        int codepoint = glyph.get("codepoint").getAsInt();
        String glyphName = optionalString(glyph, "glyphName", "glyph_name");
        Integer gid = glyph.has("gid") && !glyph.get("gid").isJsonNull() ? glyph.get("gid").getAsInt() : null;
        double advance = glyph.has("advance") ? glyph.get("advance").getAsDouble() : 0.0;

        int originX = 0;
        int originY = 0;
        if (glyph.has("origin") && glyph.get("origin").isJsonArray()) {
            JsonArray origin = glyph.getAsJsonArray("origin");
            if (origin.size() >= 2) {
                originX = origin.get(0).getAsInt();
                originY = origin.get(1).getAsInt();
            }
        } else {
            if (glyph.has("originOffsetX")) originX = glyph.get("originOffsetX").getAsInt();
            if (glyph.has("originOffsetY")) originY = glyph.get("originOffsetY").getAsInt();
        }

        Integer strikePpem = glyph.has("strike_ppem") && !glyph.get("strike_ppem").isJsonNull()
            ? glyph.get("strike_ppem").getAsInt() : null;

        Map<String, JsonElement> unknown = retainUnknown(glyph, ROW_KNOWN);
        return new GlyphRow(fontId, codepoint, glyphName, gid, advance, originX, originY, strikePpem, unknown);
    }

    /**
     * Looks up the row for a {@code (font_id, codepoint)} pair.
     *
     * @param fontId the owning font id
     * @param codepoint the Unicode codepoint
     * @return the matching row, or empty when the codepoint is not defined for that font id
     */
    public @NotNull Optional<GlyphRow> lookup(@NotNull FontId fontId, int codepoint) {
        return Optional.ofNullable(this.index.get(new FontIdCp(fontId, codepoint)));
    }

    /**
     * Returns the {@code .ttf} file basename for a font id.
     *
     * @param fontId the font id
     * @return the file basename, or empty when the font id is not registered
     */
    public @NotNull Optional<String> fileFor(@NotNull FontId fontId) {
        return Optional.ofNullable(this.files.get(fontId));
    }

    /**
     * @return the declared schema version
     */
    public int schemaVersion() {
        return this.schemaVersion;
    }

    /**
     * @return the generator version string, or empty when absent
     */
    public @NotNull Optional<String> generatorVersion() {
        return Optional.ofNullable(this.generatorVersion);
    }

    /**
     * @return the units-per-em advances and origins are expressed in
     */
    public int unitsPerEm() {
        return this.unitsPerEm;
    }

    /**
     * @return the {@code sbix} graphic type (e.g. {@code "png "})
     */
    public @NotNull String graphicType() {
        return this.graphicType;
    }

    /**
     * @return top-level sidecar members not modelled here, retained for forward compatibility
     */
    public @NotNull Map<String, JsonElement> unknown() {
        return this.unknown;
    }

    private static @Nullable String optionalString(@NotNull JsonObject object, @NotNull String... keys) {
        for (String key : keys)
            if (object.has(key) && !object.get(key).isJsonNull()) return object.get(key).getAsString();
        return null;
    }

    private static @NotNull Map<String, JsonElement> retainUnknown(@NotNull JsonObject object, @NotNull Set<String> known) {
        Map<String, JsonElement> unknown = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet())
            if (!known.contains(entry.getKey())) unknown.put(entry.getKey(), entry.getValue());
        return unknown;
    }

    private static final Set<String> TOP_LEVEL_KNOWN =
        Set.of("schema_version", "generator_version", "source_date_epoch", "units_per_em", "graphic_type", "fonts", "glyphs");

    private static final Set<String> ROW_KNOWN =
        Set.of("font_id", "codepoint", "glyphName", "glyph_name", "gid", "advance", "origin", "originOffsetX", "originOffsetY", "strike_ppem");

    /**
     * Composite {@code (font_id, codepoint)} index key.
     *
     * @param fontId the font id
     * @param codepoint the codepoint
     */
    private record FontIdCp(@NotNull FontId fontId, int codepoint) {}

    /**
     * Thrown when a sidecar declares a {@code schema_version} newer than this reader supports.
     */
    public static final class UnsupportedSchemaException extends RuntimeException {

        private final int declaredVersion;
        private final int maxSupportedVersion;

        UnsupportedSchemaException(int declaredVersion, int maxSupportedVersion) {
            super("Unsupported colour-glyph sidecar schema_version " + declaredVersion
                + " (this reader supports up to " + maxSupportedVersion + "); regenerate the sidecar or upgrade the runtime.");
            this.declaredVersion = declaredVersion;
            this.maxSupportedVersion = maxSupportedVersion;
        }

        /**
         * @return the schema version the sidecar declared
         */
        public int declaredVersion() {
            return this.declaredVersion;
        }

        /**
         * @return the highest schema version this reader supports
         */
        public int maxSupportedVersion() {
            return this.maxSupportedVersion;
        }

    }

}

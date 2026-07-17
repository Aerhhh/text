package lib.minecraft.text.font;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ColorGlyphSidecar parses the versioned colour-glyph contract")
class ColorGlyphSidecarTest {

    private static ColorGlyphSidecar parse(String json) {
        return ColorGlyphSidecar.parse(new StringReader(json));
    }

    @Test
    @DisplayName("looks up rows by (font_id, codepoint) with signed advance, origin and strike")
    void lookupByFontIdAndCodepoint() {
        ColorGlyphSidecar sidecar = parse("""
            {
              "schema_version": 1, "units_per_em": 1024, "graphic_type": "png ",
              "fonts": [{"font_id": "ns:a", "file": "A.ttf"}],
              "glyphs": [
                {"font_id": "ns:a", "codepoint": 57344, "glyphName": "uE000", "gid": 3,
                 "advance": 512, "origin": [1, -2], "strike_ppem": 8}
              ]
            }
            """);

        GlyphRow row = sidecar.lookup(FontId.parse("ns:a"), 57344).orElseThrow();
        assertThat(row.gid(), is(3));
        assertThat(row.advance(), is(512.0));
        assertThat(row.originX(), is(1));
        assertThat(row.originY(), is(-2));
        assertThat(row.strikePpem(), is(8));
        assertThat(row.isSpace(), is(false));
        assertThat(sidecar.fileFor(FontId.parse("ns:a")), is(Optional.of("A.ttf")));
        assertThat(sidecar.unitsPerEm(), is(1024));
        assertThat(sidecar.graphicType(), is("png "));
    }

    @Test
    @DisplayName("a wrong font_id does not resolve a colliding codepoint")
    void wrongFontIdDoesNotResolve() {
        ColorGlyphSidecar sidecar = parse("""
            {
              "schema_version": 1,
              "glyphs": [
                {"font_id": "ns:a", "codepoint": 57345, "gid": 1, "advance": 100, "strike_ppem": 8},
                {"font_id": "ns:b", "codepoint": 57345, "gid": 1, "advance": 200, "strike_ppem": 8}
              ]
            }
            """);

        assertThat(sidecar.lookup(FontId.parse("ns:a"), 57345).orElseThrow().advance(), is(100.0));
        assertThat(sidecar.lookup(FontId.parse("ns:b"), 57345).orElseThrow().advance(), is(200.0));
        assertThat(sidecar.lookup(FontId.parse("ns:c"), 57345), is(Optional.empty()));
    }

    @Test
    @DisplayName("space rows carry a null gid and strike and may be negative or fractional")
    void spaceRowsAreAdvanceOnly() {
        ColorGlyphSidecar sidecar = parse("""
            {
              "schema_version": 1,
              "glyphs": [
                {"font_id": "ns:a", "codepoint": 57360, "glyphName": null, "gid": null,
                 "advance": -16384, "origin": [0, 0], "strike_ppem": null},
                {"font_id": "ns:a", "codepoint": 57361, "gid": null, "advance": 4.5, "strike_ppem": null}
              ]
            }
            """);

        GlyphRow negative = sidecar.lookup(FontId.parse("ns:a"), 57360).orElseThrow();
        assertThat(negative.isSpace(), is(true));
        assertThat(negative.gid(), is((Integer) null));
        assertThat(negative.advance(), is(-16384.0));

        GlyphRow fractional = sidecar.lookup(FontId.parse("ns:a"), 57361).orElseThrow();
        assertThat(fractional.advance(), closeTo(4.5, 1e-9));
        assertThat(fractional.isSpace(), is(true));
    }

    @Test
    @DisplayName("unknown top-level and per-glyph members are tolerated and retained")
    void unknownFieldsRetained() {
        ColorGlyphSidecar sidecar = parse("""
            {
              "schema_version": 1, "future_top": {"a": 1},
              "glyphs": [
                {"font_id": "ns:a", "codepoint": 57344, "gid": 1, "advance": 10, "strike_ppem": 8,
                 "future_glyph": "keep-me"}
              ]
            }
            """);

        assertThat(sidecar.unknown(), hasKey("future_top"));
        GlyphRow row = sidecar.lookup(FontId.parse("ns:a"), 57344).orElseThrow();
        assertThat(row.unknown(), hasKey("future_glyph"));
        assertThat(row.unknown().get("future_glyph").getAsString(), is("keep-me"));
    }

    @Test
    @DisplayName("a schema_version above the supported maximum is rejected fail-loud")
    void schemaVersionAboveMaxRejected() {
        String json = "{\"schema_version\": " + (ColorGlyphSidecar.MAX_SUPPORTED_SCHEMA + 1) + ", \"glyphs\": []}";
        ColorGlyphSidecar.UnsupportedSchemaException ex =
            assertThrows(ColorGlyphSidecar.UnsupportedSchemaException.class, () -> parse(json));
        assertThat(ex.declaredVersion(), is(ColorGlyphSidecar.MAX_SUPPORTED_SCHEMA + 1));
        assertThat(ex.maxSupportedVersion(), is(ColorGlyphSidecar.MAX_SUPPORTED_SCHEMA));
        assertTrue(ex.getMessage().contains("schema_version"));
    }

    @Test
    @DisplayName("the supported schema_version parses")
    void supportedSchemaVersionParses() {
        ColorGlyphSidecar sidecar = parse("{\"schema_version\": " + ColorGlyphSidecar.MAX_SUPPORTED_SCHEMA + ", \"glyphs\": []}");
        assertThat(sidecar.schemaVersion(), is(ColorGlyphSidecar.MAX_SUPPORTED_SCHEMA));
    }

    @Test
    @DisplayName("accepts the sbix-style originOffsetX/originOffsetY row shape")
    void acceptsOriginOffsetShape() {
        ColorGlyphSidecar sidecar = parse("""
            {
              "schema_version": 1,
              "glyphs": [
                {"font_id": "ns:a", "codepoint": 57344, "gid": 1, "advance": 10,
                 "originOffsetX": 5, "originOffsetY": 7, "strike_ppem": 8}
              ]
            }
            """);

        GlyphRow row = sidecar.lookup(FontId.parse("ns:a"), 57344).orElseThrow();
        assertThat(row.originX(), is(5));
        assertThat(row.originY(), is(7));
    }

    @Test
    @DisplayName("FontId round-trips namespace:path and defaults to minecraft")
    void fontIdParsing() {
        assertThat(FontId.parse("ns:a/b").namespace(), is("ns"));
        assertThat(FontId.parse("ns:a/b").path(), is("a/b"));
        assertThat(FontId.parse("bare").namespace(), is("minecraft"));
        assertThat(FontId.parse("ns:a").toString(), is("ns:a"));
    }

    @Test
    @DisplayName("the committed fixture sidecar exposes both font ids")
    void fixtureSidecarHasBothFontIds() {
        ColorGlyphSidecar sidecar = ColorFontFixtures.sidecar();
        assertThat(sidecar.fileFor(ColorFontFixtures.DEMO), is(Optional.of("SynthColour-demo.ttf")));
        assertThat(sidecar.fileFor(ColorFontFixtures.ALT), is(Optional.of("SynthColour-alt.ttf")));
        assertThat(sidecar.unitsPerEm(), is(1024));
        assertThat(sidecar.lookup(ColorFontFixtures.DEMO, ColorFontFixtures.CP_FLAT).orElseThrow().gid(), is(1));
    }

    @Test
    @DisplayName("codepoints reuse assertion: the committed fixture reuses E001 across both font ids")
    void fixtureReusesCodepointAcrossFontIds() {
        ColorGlyphSidecar sidecar = ColorFontFixtures.sidecar();
        assertThat(sidecar.lookup(ColorFontFixtures.DEMO, ColorFontFixtures.CP_FLAT).isPresent(), is(true));
        assertThat(sidecar.lookup(ColorFontFixtures.ALT, ColorFontFixtures.CP_FLAT).isPresent(), is(true));
    }

}

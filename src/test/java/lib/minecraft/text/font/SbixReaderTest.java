package lib.minecraft.text.font;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@DisplayName("SbixReader extracts strike payloads from the sbix table")
class SbixReaderTest {

    @Test
    @DisplayName("reports numGlyphs and every strike ppem")
    void reportsGlyphCountAndStrikes() {
        SbixReader reader = new SbixReader(ColorFontFixtures.bytes("SynthColour-demo.ttf"));
        assertThat(reader.numGlyphs(), is(6));
        assertThat(reader.strikePpems().length, is(2));
        Integer[] ppems = {reader.strikePpems()[0], reader.strikePpems()[1]};
        assertThat(ppems, arrayContainingInAnyOrder(8, 16));
    }

    @Test
    @DisplayName("returns the PNG payload for a glyph in its strike and null for an empty strike slot")
    void returnsPngForPresentStrikeAndNullForEmpty() {
        SbixReader reader = new SbixReader(ColorFontFixtures.bytes("SynthColour-demo.ttf"));
        // gid 1 (flat) lives in strike ppem 8, not in ppem 16
        assertThat(reader.strikePng(1, 8), notNullValue());
        assertThat(reader.graphicType(1, 8), is("png "));
        assertThat(reader.strikePng(1, 16), nullValue());
        // gid 4 (downscaled) lives in strike ppem 16
        assertThat(reader.strikePng(4, 16), notNullValue());
        assertThat(reader.strikePng(4, 8), nullValue());
    }

    @Test
    @DisplayName("the extracted PNG payload is a valid PNG (magic bytes intact)")
    void extractedPayloadIsValidPng() {
        SbixReader reader = new SbixReader(ColorFontFixtures.bytes("SynthColour-demo.ttf"));
        byte[] png = reader.strikePng(1, 8);
        assertThat(png, notNullValue());
        // PNG signature: 89 50 4E 47 0D 0A 1A 0A
        byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] head = new byte[8];
        System.arraycopy(png, 0, head, 0, 8);
        assertArrayEquals(signature, head);
    }

    @Test
    @DisplayName("a dupe record resolves to the referenced glyph's payload")
    void dupeRecordResolvesReference() {
        SbixReader reader = ColorFontFixtures.edgeReader();
        // gid 1 is the real red PNG; gid 2 is a dupe referencing gid 1
        byte[] direct = reader.strikePng(1, 8);
        byte[] viaDupe = reader.strikePng(2, 8);
        assertThat(direct, notNullValue());
        assertThat(viaDupe, notNullValue());
        assertArrayEquals(direct, viaDupe);
    }

    @Test
    @DisplayName("a non-png graphic type is reported and yields no PNG payload")
    void nonPngGraphicTypeSkipped() {
        SbixReader reader = ColorFontFixtures.edgeReader();
        assertThat(reader.graphicType(3, 8), is("jpg "));
        assertThat(reader.strikePng(3, 8), nullValue());
    }

    @Test
    @DisplayName("a font without an sbix table is rejected")
    void rejectsFontWithoutSbix() {
        // Truncated garbage is not a valid sfnt; construction fails fast.
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
            () -> new SbixReader(new byte[]{0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
    }

}

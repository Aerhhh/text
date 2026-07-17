package lib.minecraft.text.font;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * A pure-Java reader for the {@code sbix} table - the bitmap-strike storage colour pack fonts use.
 * <p>
 * Java2D neither paints nor spaces {@code sbix} glyphs (it renders blank and zeroes
 * {@code GlyphVector} advances), so a consumer that wants the artwork must extract the strike PNGs
 * itself. This reader does exactly that with nothing beyond {@code java.nio}-style byte access: it
 * walks the sfnt table directory, reads {@code maxp.numGlyphs}, locates {@code sbix}, and for any
 * {@code (gid, ppem)} returns the raw PNG payload byte-for-byte. Decoding and caching are left to
 * {@link MinecraftColorFont}'s internal strike cache so this class stays dependency-free.
 * <p>
 * The per-glyph offset array of each strike has {@code numGlyphs + 1} entries covering every glyph
 * in glyph order; an empty glyph has {@code offset[gid + 1] == offset[gid]}. A {@code dupe} record
 * references another glyph id (resolved here); a non-{@code png } graphic type is reported but not
 * decoded.
 */
public final class SbixReader {

    private final byte[] data;
    private final Map<String, int[]> directory = new HashMap<>();
    private final int numGlyphs;
    private final int sbixOffset;

    /**
     * Parses the sfnt directory and locates the {@code sbix} table.
     *
     * @param ttf the raw TrueType font bytes
     * @throws IllegalArgumentException when the font has no {@code maxp} or {@code sbix} table
     */
    public SbixReader(byte[] ttf) {
        this.data = ttf;
        parseDirectory();
        int[] maxp = this.directory.get("maxp");
        if (maxp == null) throw new IllegalArgumentException("Font has no 'maxp' table; not a valid sfnt.");
        this.numGlyphs = u16(maxp[0] + 4);
        int[] sbix = this.directory.get("sbix");
        if (sbix == null) throw new IllegalArgumentException("Font has no 'sbix' table; not a colour bitmap font.");
        this.sbixOffset = sbix[0];
    }

    private void parseDirectory() {
        int numTables = u16(4);
        int p = 12;
        for (int i = 0; i < numTables; i++, p += 16)
            this.directory.put(tag(p), new int[]{(int) u32(p + 8), (int) u32(p + 12)});
    }

    /**
     * @return the number of glyphs in the font (from {@code maxp})
     */
    public int numGlyphs() {
        return this.numGlyphs;
    }

    /**
     * Returns every strike ppem present, in table order.
     *
     * @return the strike ppems
     */
    public int @NotNull [] strikePpems() {
        long numStrikes = u32(this.sbixOffset + 4);
        int[] ppems = new int[(int) numStrikes];
        for (int s = 0; s < numStrikes; s++) {
            int strikeAbs = (int) (this.sbixOffset + u32(this.sbixOffset + 8 + s * 4));
            ppems[s] = u16(strikeAbs);
        }
        return ppems;
    }

    /**
     * Returns the raw PNG payload for a glyph in a given strike, resolving {@code dupe} references.
     *
     * @param gid the glyph id
     * @param ppem the strike ppem
     * @return the PNG bytes, or {@code null} when the glyph is empty in that strike or its graphic
     * type is not {@code png }
     */
    public byte @Nullable [] strikePng(int gid, int ppem) {
        Record record = record(gid, ppem, 0);
        if (record == null || !record.graphicType.equals("png ")) return null;
        return record.imageData;
    }

    /**
     * Returns the {@code sbix} graphic type of a glyph in a given strike (e.g. {@code "png "},
     * {@code "jpg "}), resolving {@code dupe} references.
     *
     * @param gid the glyph id
     * @param ppem the strike ppem
     * @return the four-character graphic type, or {@code null} when the glyph is empty in that strike
     */
    public @Nullable String graphicType(int gid, int ppem) {
        Record record = record(gid, ppem, 0);
        return record == null ? null : record.graphicType;
    }

    /**
     * Returns the raw {@code sbix} origin offset of a glyph in a given strike. This is the
     * spec-defined int16 pixel offset consumed by native renderers; the runtime positions from the
     * sidecar origin instead, so this is exposed for tooling/diagnostics only.
     *
     * @param gid the glyph id
     * @param ppem the strike ppem
     * @return the {@code [x, y]} offset, or {@code null} when the glyph is empty in that strike
     */
    public int @Nullable [] rawOriginOffset(int gid, int ppem) {
        Record record = record(gid, ppem, 0);
        return record == null ? null : new int[]{record.originX, record.originY};
    }

    private @Nullable Record record(int gid, int ppem, int depth) {
        if (depth > MAX_DUPE_DEPTH || gid < 0 || gid >= this.numGlyphs) return null;
        int strikeAbs = strikeOffset(ppem);
        if (strikeAbs < 0) return null;

        int glyphOffArray = strikeAbs + 4;
        long start = u32(glyphOffArray + gid * 4);
        long end = u32(glyphOffArray + (gid + 1) * 4);
        int dataLen = (int) (end - start);
        if (dataLen <= 0) return null;   // empty glyph in this strike

        int recAbs = (int) (strikeAbs + start);
        int originX = s16(recAbs);
        int originY = s16(recAbs + 2);
        String graphicType = tag(recAbs + 4);
        int imgStart = recAbs + 8;
        int imgLen = dataLen - 8;

        if (graphicType.equals("dupe")) {
            int referenced = u16(imgStart);
            return record(referenced, ppem, depth + 1);
        }

        byte[] imageData = new byte[Math.max(0, imgLen)];
        System.arraycopy(this.data, imgStart, imageData, 0, imageData.length);
        return new Record(originX, originY, graphicType, imageData);
    }

    private int strikeOffset(int ppem) {
        long numStrikes = u32(this.sbixOffset + 4);
        for (int s = 0; s < numStrikes; s++) {
            int strikeAbs = (int) (this.sbixOffset + u32(this.sbixOffset + 8 + s * 4));
            if (u16(strikeAbs) == ppem) return strikeAbs;
        }
        return -1;
    }

    // --- primitive readers (big-endian) ---

    private int u16(int offset) {
        return ((this.data[offset] & 0xff) << 8) | (this.data[offset + 1] & 0xff);
    }

    private int s16(int offset) {
        int value = u16(offset);
        return value >= 0x8000 ? value - 0x10000 : value;
    }

    private long u32(int offset) {
        return ((long) (this.data[offset] & 0xff) << 24) | ((this.data[offset + 1] & 0xff) << 16)
            | ((this.data[offset + 2] & 0xff) << 8) | (this.data[offset + 3] & 0xff);
    }

    private @NotNull String tag(int offset) {
        return new String(new char[]{
            (char) (this.data[offset] & 0xff), (char) (this.data[offset + 1] & 0xff),
            (char) (this.data[offset + 2] & 0xff), (char) (this.data[offset + 3] & 0xff)
        });
    }

    private static final int MAX_DUPE_DEPTH = 8;

    private record Record(int originX, int originY, @NotNull String graphicType, byte @NotNull [] imageData) {}

}

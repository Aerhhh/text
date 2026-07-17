package lib.minecraft.text.font;

import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@code sbix} strike-cache relocation from {@link BufferedImage} to {@link PixelBuffer}.
 * <p>
 * The conversion must be pixel-semantics-neutral (proved by decoding every committed fixture strike
 * both ways and comparing every pixel), and it must actually shed memory (proved by a deterministic
 * retained-size comparison over the fixture strike set). The retained-size model walks each
 * representation's object graph through public getters and sums shallow sizes via declared-field slot
 * counting - no {@code setAccessible}, no GC heuristics - so the number is reproducible run to run.
 */
@DisplayName("sbix strikes cache as PixelBuffer: pixel-identical to BufferedImage, and smaller")
class StrikePixelBufferConversionTest {

    /**
     * The committed fixture strikes at realistic glyph sizes (8x8 and 16x16), by merged gid. The
     * 256x256 pathological strike (gid 6) is measured separately.
     */
    private static final @NotNull List<int[]> REALISTIC_STRIKES = List.of(
        new int[]{ColorFontFixtures.GID_FLAT, 8},         // demo E001, 8x8
        new int[]{ColorFontFixtures.GID_AA, 8},           // demo E002, 8x8
        new int[]{ColorFontFixtures.GID_MID, 8},          // demo E004, 16x16
        new int[]{ColorFontFixtures.GID_DOWNSCALED, 16}); // demo E005, 16x16 at ppem 16

    private static final int[] TALL_STRIKE = {ColorFontFixtures.GID_TALL, 8};   // demo E006, 256x256

    // --- (a) pixel-exact equivalence ---

    @Test
    @DisplayName("every fixture strike caches pixel-identically as PixelBuffer and as BufferedImage")
    void pixelExactAcrossEveryFixtureStrike() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        SbixReader reader = new SbixReader(ColorFontFixtures.bytes(ColorFontFixtures.MERGED_TTF));

        int comparedStrikes = 0;
        for (int[] strike : concat(REALISTIC_STRIKES, TALL_STRIKE)) {
            int gid = strike[0];
            int ppem = strike[1];

            // The BufferedImage cache path: decode, then wrap to ARGB at use time (the pre-refactor
            // runtime). The PixelBuffer cache path moves that same wrap into the cache. Comparing the
            // two proves the relocation is pixel-semantics-neutral.
            BufferedImage reference = toArgb(decodeBufferedImage(reader, gid, ppem));
            PixelBuffer buffer = font.strike(gid, ppem).orElseThrow();

            assertThat("width gid=" + gid, buffer.width(), is(reference.getWidth()));
            assertThat("height gid=" + gid, buffer.height(), is(reference.getHeight()));
            for (int y = 0; y < reference.getHeight(); y++)
                for (int x = 0; x < reference.getWidth(); x++)
                    assertThat("gid=" + gid + " ppem=" + ppem + " pixel (" + x + "," + y + ")",
                        buffer.getPixel(x, y), is(reference.getRGB(x, y)));
            comparedStrikes++;
        }

        assertThat(comparedStrikes, is(REALISTIC_STRIKES.size() + 1));
    }

    /**
     * Mirrors {@link MinecraftFont.Color}'s private {@code toArgb}: normalise any decoded image to
     * {@code TYPE_INT_ARGB} so its {@code getRGB} equals the pixels the runtime wraps into a
     * {@link PixelBuffer}. Identity for images that already decode to {@code TYPE_INT_ARGB}.
     */
    private static @NotNull BufferedImage toArgb(@NotNull BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) return source;
        BufferedImage argb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        argb.getGraphics().drawImage(source, 0, 0, null);
        return argb;
    }

    // --- (b) measured retained-size comparison ---

    @Test
    @DisplayName("caching PixelBuffer instead of BufferedImage sheds the per-strike object-graph overhead")
    void pixelBufferRetainsFewerBytesThanBufferedImage() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        SbixReader reader = new SbixReader(ColorFontFixtures.bytes(ColorFontFixtures.MERGED_TTF));

        long biTotal = 0;
        long pbTotal = 0;
        for (int[] strike : REALISTIC_STRIKES) {
            BufferedImage bi = decodeBufferedImage(reader, strike[0], strike[1]);
            PixelBuffer pb = font.strike(strike[0], strike[1]).orElseThrow();
            biTotal += Retained.bufferedImage(bi);
            pbTotal += Retained.pixelBuffer(pb);
        }

        int n = REALISTIC_STRIKES.size();
        long saved = biTotal - pbTotal;
        long biPer = biTotal / n;
        long pbPer = pbTotal / n;
        long savedPer = saved / n;
        double pct = 100.0 * saved / biTotal;

        // Pathological 256x256 strike: raw pixel bytes dominate, so the fixed overhead is a tiny slice.
        BufferedImage tallBi = decodeBufferedImage(reader, TALL_STRIKE[0], TALL_STRIKE[1]);
        PixelBuffer tallPb = font.strike(TALL_STRIKE[0], TALL_STRIKE[1]).orElseThrow();
        long tallBiBytes = Retained.bufferedImage(tallBi);
        long tallPbBytes = Retained.pixelBuffer(tallPb);
        double tallPct = 100.0 * (tallBiBytes - tallPbBytes) / tallBiBytes;

        System.out.printf(
            "[STRIKE-MEM] realistic set (%d strikes 8x8..16x16): BufferedImage=%d B (%d B/strike), "
                + "PixelBuffer=%d B (%d B/strike), saved=%d B (%d B/strike, %.1f%%). "
                + "Pathological 256x256: BufferedImage=%d B, PixelBuffer=%d B, saved %.1f%%.%n",
            n, biTotal, biPer, pbTotal, pbPer, saved, savedPer, pct,
            tallBiBytes, tallPbBytes, tallPct);

        // The realistic-set win is the fixed per-strike object graph the BufferedImage path carries.
        assertTrue(pbPer < biPer, "PixelBuffer should retain fewer bytes per realistic strike: pb=" + pbPer + " bi=" + biPer);
        assertTrue(savedPer > 0, "expected a positive per-strike saving, got " + savedPer);
        // The pathological strike is dominated by raw pixels, so its relative saving is a smaller slice.
        assertTrue(tallPct < pct, "pathological relative saving " + tallPct + "% should be below realistic " + pct + "%");
    }

    // --- helpers ---

    private static @NotNull BufferedImage decodeBufferedImage(@NotNull SbixReader reader, int gid, int ppem) {
        byte[] png = reader.strikePng(gid, ppem);
        if (png == null) throw new IllegalStateException("fixture strike gid=" + gid + " ppem=" + ppem + " is not a PNG");
        try (InputStream in = new ByteArrayInputStream(png)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) throw new IllegalStateException("ImageIO returned null for gid=" + gid + " ppem=" + ppem);
            return image;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static @NotNull List<int[]> concat(@NotNull List<int[]> head, int[] tail) {
        List<int[]> all = new java.util.ArrayList<>(head);
        all.add(tail);
        return all;
    }

    /**
     * A deterministic retained-size estimator. It walks an object graph through public getters and
     * sums shallow object sizes computed from declared-field slot sizes, using a standard
     * compressed-oops model (12-byte object header, 16-byte array header, 4-byte references,
     * 8-byte alignment). It reads no field values, so it needs no {@code setAccessible} and works
     * under the module system; the result depends only on class layout and pixel dimensions.
     */
    private static final class Retained {

        static long bufferedImage(@NotNull BufferedImage img) {
            WritableRaster raster = img.getRaster();
            DataBuffer db = raster.getDataBuffer();
            SampleModel sm = img.getSampleModel();
            ColorModel cm = img.getColorModel();

            long total = 0;
            total += shallow(img);
            total += shallow(raster);
            total += shallow(sm);
            total += shallow(cm);
            total += shallow(db);
            total += array(db.getSize() * db.getNumBanks(), elementBytes(db.getDataType()));
            // The colour model's per-component nBits int[] is part of the shed graph.
            if (cm.getNumComponents() > 0) total += array(cm.getNumComponents(), Integer.BYTES);
            return total;
        }

        static long pixelBuffer(@NotNull PixelBuffer pb) {
            return shallow(pb) + array(pb.data().length, Integer.BYTES);
        }

        private static long shallow(@NotNull Object o) {
            long size = 12;   // compressed-oops object header
            for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass())
                for (Field f : c.getDeclaredFields())
                    if (!Modifier.isStatic(f.getModifiers()))
                        size += slot(f.getType());
            return align8(size);
        }

        private static long slot(@NotNull Class<?> t) {
            if (t == long.class || t == double.class) return 8;
            if (t == int.class || t == float.class) return 4;
            if (t == short.class || t == char.class) return 2;
            if (t == byte.class || t == boolean.class) return 1;
            return 4;   // compressed-oops reference
        }

        private static long array(long length, int elementBytes) {
            return align8(16L + length * elementBytes);
        }

        private static int elementBytes(int dataType) {
            return switch (dataType) {
                case DataBuffer.TYPE_BYTE -> Byte.BYTES;
                case DataBuffer.TYPE_USHORT, DataBuffer.TYPE_SHORT -> Short.BYTES;
                case DataBuffer.TYPE_INT, DataBuffer.TYPE_FLOAT -> Integer.BYTES;
                case DataBuffer.TYPE_DOUBLE -> Double.BYTES;
                default -> Integer.BYTES;
            };
        }

        private static long align8(long n) {
            return (n + 7L) & ~7L;
        }

    }

}

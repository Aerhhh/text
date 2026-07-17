package lib.minecraft.text.font;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A single pack colour font, keyed by {@link FontId}, that sits beside the fixed
 * {@link MinecraftFont.Vanilla} enum as the second {@link MinecraftFont} implementation.
 * <p>
 * It binds the three things a colour font needs at draw time: a private {@code sbix} strike cache
 * over its per-font {@code .ttf} (an {@link SbixReader} plus a decode-once
 * {@code (gid, ppem) -> PixelBuffer} map), the parsed {@link ColorGlyphSidecar} view (advances,
 * origins, strike selection), and a vanilla {@link MinecraftFont} mono fallback for any codepoint
 * the pack does not define. Every codepoint resolves through {@link #glyph(int)} into a single
 * {@link GlyphData}, so downstream layout ({@link #layout(String)}), measurement ({@link #metrics()}),
 * and painting share one advance source and one glyph surface with the vanilla path.
 * <p>
 * Strikes are cached as {@link PixelBuffer} rather than {@link BufferedImage}: each strike is decoded
 * once via {@link ImageIO#read}, wrapped into an ARGB {@code int[]}, and the whole
 * {@code BufferedImage}/{@code Raster}/{@code ColorModel}/{@code SampleModel} object graph is then
 * discarded, shedding its fixed per-strike overhead for the process lifetime of the font.
 */
public final class MinecraftColorFont implements MinecraftFont {

    /**
     * Classpath / cache subdirectory the colour {@code .ttf} files and shared sidecar live under.
     */
    public static final @NotNull String RESOURCE_DIR = "colorfont";

    /**
     * Shared sidecar filename.
     */
    public static final @NotNull String SIDECAR_NAME = "colour-glyphs.json";

    private final @NotNull FontId fontId;
    private final @NotNull SbixReader reader;
    private final @NotNull ColorGlyphSidecar sidecar;
    private final @NotNull MinecraftFont monoFallback;
    private final @NotNull MinecraftFontMetrics metrics;

    /**
     * Per-codepoint glyph cache. Uniform {@link #glyph(int)} surface with the vanilla atlas over a
     * lower {@code sbix} strike tier.
     */
    private final @NotNull ConcurrentMap<Integer, GlyphData> glyphCache;

    /**
     * Decode-once {@code sbix} strike cache keyed {@code (gid << 16 | ppem)}. Each strike is decoded
     * exactly once into a {@link PixelBuffer}; an empty glyph or non-PNG record caches an empty
     * result so the miss is not re-attempted. Unbounded and process-lifetime - the glyph set per pack
     * is finite - and it garbage-collects with the font.
     */
    private final @NotNull ConcurrentMap<Long, Optional<PixelBuffer>> strikeCache;

    private MinecraftColorFont(
        @NotNull FontId fontId,
        @NotNull SbixReader reader,
        @NotNull ColorGlyphSidecar sidecar,
        @NotNull MinecraftFont monoFallback
    ) {
        this.fontId = fontId;
        this.reader = reader;
        this.sidecar = sidecar;
        this.monoFallback = monoFallback;
        this.glyphCache = Concurrent.newMap();
        this.strikeCache = Concurrent.newMap();

        MinecraftFontMetrics mono = monoFallback.metrics();
        this.metrics = MinecraftFontMetrics.of(this, mono.getFont(), mono.getAscent(), mono.getDescent(), mono.getHeight());
    }

    /**
     * Builds a colour font from raw font bytes, the parsed sidecar, and a mono fallback. The strike
     * cache is internal, so callers pass only the {@code .ttf} bytes.
     *
     * @param fontId the font id
     * @param ttf the raw colour {@code .ttf} bytes
     * @param sidecar the parsed sidecar (may span multiple font ids)
     * @param monoFallback the vanilla font used for codepoints the pack does not define
     * @return the colour font
     */
    public static @NotNull MinecraftColorFont of(
        @NotNull FontId fontId,
        byte @NotNull [] ttf,
        @NotNull ColorGlyphSidecar sidecar,
        @NotNull MinecraftFont monoFallback
    ) {
        return new MinecraftColorFont(fontId, new SbixReader(ttf), sidecar, monoFallback);
    }

    /**
     * Resolves a colour font for a font id from the classpath, then the user-home cache, using
     * {@link Vanilla#REGULAR} as the mono fallback.
     *
     * @param fontId the font id to load
     * @return the resolved colour font
     * @throws IllegalStateException when the sidecar, the font id, or its {@code .ttf} cannot be found
     */
    public static @NotNull MinecraftColorFont load(@NotNull FontId fontId) {
        return load(fontId, Vanilla.REGULAR);
    }

    /**
     * Resolves a colour font for a font id with an explicit mono fallback.
     *
     * @param fontId the font id to load
     * @param monoFallback the vanilla font used for undefined codepoints
     * @return the resolved colour font
     * @throws IllegalStateException when the sidecar, the font id, or its {@code .ttf} cannot be found
     */
    public static @NotNull MinecraftColorFont load(@NotNull FontId fontId, @NotNull MinecraftFont monoFallback) {
        ColorGlyphSidecar sidecar = loadSidecar();
        String file = sidecar.fileFor(fontId).orElseThrow(() -> new IllegalStateException(
            "Colour sidecar '" + SIDECAR_NAME + "' does not list font id '" + fontId + "'."));
        return of(fontId, loadTtfBytes(file), sidecar, monoFallback);
    }

    private static @NotNull ColorGlyphSidecar loadSidecar() {
        String classpath = RESOURCE_DIR + "/" + SIDECAR_NAME;
        try (InputStream in = MinecraftColorFont.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    return ColorGlyphSidecar.parse(reader);
                }
            }
        } catch (IOException ex) {
            // Fall through to the cache tier.
        }

        Path cached = MinecraftFont.defaultCacheRoot().resolve(RESOURCE_DIR).resolve(SIDECAR_NAME);
        if (Files.isRegularFile(cached)) {
            try (Reader reader = Files.newBufferedReader(cached, StandardCharsets.UTF_8)) {
                return ColorGlyphSidecar.parse(reader);
            } catch (IOException ex) {
                throw new UncheckedIOException("Unable to read colour sidecar '" + cached + "'", ex);
            }
        }

        throw new IllegalStateException(
            "Unable to load colour sidecar after all fallbacks.\n"
                + "  Tier 1 (classpath): /" + classpath + "\n"
                + "  Tier 2 (filesystem cache): " + cached);
    }

    private static byte[] loadTtfBytes(@NotNull String file) {
        String classpath = RESOURCE_DIR + "/" + file;
        try (InputStream in = MinecraftColorFont.class.getClassLoader().getResourceAsStream(classpath)) {
            if (in != null) return in.readAllBytes();
        } catch (IOException ex) {
            // Fall through to the cache tier.
        }

        Path cached = MinecraftFont.defaultCacheRoot().resolve(RESOURCE_DIR).resolve(file);
        if (Files.isRegularFile(cached)) {
            try {
                return Files.readAllBytes(cached);
            } catch (IOException ex) {
                throw new UncheckedIOException("Unable to read colour font '" + cached + "'", ex);
            }
        }

        throw new IllegalStateException(
            "Unable to load colour font '" + file + "' after all fallbacks.\n"
                + "  Tier 1 (classpath): /" + classpath + "\n"
                + "  Tier 2 (filesystem cache): " + cached);
    }

    @Override
    public @NotNull GlyphData glyph(int codepoint) {
        return this.glyphCache.computeIfAbsent(codepoint, this::resolveGlyph);
    }

    private @NotNull GlyphData resolveGlyph(int codepoint) {
        Optional<GlyphRow> rowOptional = this.sidecar.lookup(this.fontId, codepoint);
        if (rowOptional.isEmpty()) return this.monoFallback.glyph(codepoint);   // MONO fallback

        GlyphRow row = rowOptional.get();
        int unitsPerEm = this.sidecar.unitsPerEm();
        float advance = (float) FontUnits.toOutputPixels(row.advance(), unitsPerEm);
        if (row.isSpace()) return GlyphData.space(advance);

        int ppem = resolvePpem(row);
        int gid = row.gid();
        int originX = (int) Math.round(FontUnits.toOutputPixels(row.originX(), unitsPerEm));
        int originY = (int) Math.round(FontUnits.toOutputPixels(row.originY(), unitsPerEm));

        // A raster row whose strike fails to decode degrades to an advance-only sentinel: the pen
        // still moves, nothing is painted, and glyph(cp) never returns null.
        return strike(gid, ppem)
            .map(bitmap -> GlyphData.color(bitmap, advance, originX, originY, gid, ppem))
            .orElseGet(() -> GlyphData.space(advance));
    }

    private int resolvePpem(@NotNull GlyphRow row) {
        Integer declared = row.strikePpem();
        if (declared != null) return declared;
        int[] available = this.reader.strikePpems();
        return available.length > 0 ? available[0] : 0;
    }

    /**
     * Returns the decoded {@code sbix} strike for a glyph as a {@link PixelBuffer}, decoding at most
     * once per {@code (gid, ppem)} and caching the result. Empty when the glyph is absent in that
     * strike or its graphic type is not a PNG.
     *
     * @param gid the glyph id
     * @param ppem the strike ppem
     * @return the decoded strike, or empty
     */
    @NotNull Optional<PixelBuffer> strike(int gid, int ppem) {
        long key = ((long) gid << 16) | (ppem & 0xFFFFL);
        return this.strikeCache.computeIfAbsent(key, ignored -> decode(gid, ppem));
    }

    private @NotNull Optional<PixelBuffer> decode(int gid, int ppem) {
        byte[] png = this.reader.strikePng(gid, ppem);
        if (png == null) return Optional.empty();
        try (InputStream in = new ByteArrayInputStream(png)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) return Optional.empty();
            // Wrap into an ARGB int[] and drop the BufferedImage graph; the PixelBuffer is all we keep.
            return Optional.of(PixelBuffer.wrap(toArgb(image)));
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to decode sbix strike gid=" + gid + " ppem=" + ppem, ex);
        }
    }

    @Override
    public @NotNull MinecraftFontMetrics metrics() {
        return this.metrics;
    }

    @Override
    public @NotNull FontId fontId() {
        return this.fontId;
    }

    @Override
    public @NotNull Optional<Shape> monoOutline(int codepoint, double penX) {
        return this.monoFallback.monoOutline(codepoint, penX);
    }

    /**
     * @return the underlying {@code sbix} reader
     */
    @NotNull SbixReader reader() {
        return this.reader;
    }

    /**
     * @return the parsed sidecar
     */
    public @NotNull ColorGlyphSidecar sidecar() {
        return this.sidecar;
    }

    /**
     * @return the vanilla mono fallback font
     */
    public @NotNull MinecraftFont monoFallback() {
        return this.monoFallback;
    }

    private static @NotNull BufferedImage toArgb(@NotNull BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) return source;
        BufferedImage argb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        argb.getGraphics().drawImage(source, 0, 0, null);
        return argb;
    }

}

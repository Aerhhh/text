package lib.minecraft.text.font;

import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A thread-safe, decode-once cache of {@code sbix} strike images over a {@link SbixReader}.
 * <p>
 * Each {@code (gid, ppem)} pair is decoded from its PNG payload by exactly one
 * {@link ImageIO#read} call and cached; repeated lookups return the same {@link BufferedImage}
 * instance. Empty glyphs and non-{@code png } graphic types cache an empty result so the miss is
 * not re-attempted. Backed by {@link ConcurrentHashMap#computeIfAbsent}, so concurrent callers see
 * a single decode and an identical cached image.
 */
public final class SbixStrikeCache {

    private final @NotNull SbixReader reader;
    private final @NotNull ConcurrentMap<Long, Optional<BufferedImage>> cache = new ConcurrentHashMap<>();

    private SbixStrikeCache(@NotNull SbixReader reader) {
        this.reader = reader;
    }

    /**
     * Creates a cache over raw font bytes.
     *
     * @param ttf the raw TrueType font bytes
     * @return the cache
     */
    public static @NotNull SbixStrikeCache of(byte[] ttf) {
        return new SbixStrikeCache(new SbixReader(ttf));
    }

    /**
     * Creates a cache over a font file.
     *
     * @param ttf the font file path
     * @return the cache
     * @throws UncheckedIOException when the file cannot be read
     */
    public static @NotNull SbixStrikeCache of(@NotNull Path ttf) {
        try {
            return of(Files.readAllBytes(ttf));
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read colour font file '" + ttf + "'", ex);
        }
    }

    /**
     * Returns the decoded strike image for a glyph, decoding at most once per {@code (gid, ppem)}.
     *
     * @param gid the glyph id
     * @param ppem the strike ppem
     * @return the decoded image, or empty when the glyph is absent in that strike or not a PNG
     */
    public @NotNull Optional<BufferedImage> strike(int gid, int ppem) {
        long key = ((long) gid << 16) | (ppem & 0xFFFFL);
        return this.cache.computeIfAbsent(key, ignored -> decode(gid, ppem));
    }

    private @NotNull Optional<BufferedImage> decode(int gid, int ppem) {
        byte[] png = this.reader.strikePng(gid, ppem);
        if (png == null) return Optional.empty();
        try (InputStream in = new ByteArrayInputStream(png)) {
            return Optional.ofNullable(ImageIO.read(in));
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to decode sbix strike gid=" + gid + " ppem=" + ppem, ex);
        }
    }

    /**
     * @return the underlying reader
     */
    public @NotNull SbixReader reader() {
        return this.reader;
    }

}

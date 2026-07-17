package lib.minecraft.text.font;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A single pack colour font, keyed by {@link FontId}, that coexists with the fixed vanilla
 * {@link MinecraftFont} enum rather than extending it.
 * <p>
 * It binds the three things a colour font needs at draw time: the {@link SbixStrikeCache} over its
 * per-font {@code .ttf}, the parsed {@link ColorGlyphSidecar} view (advances, origins, strike
 * selection), and a vanilla {@link MinecraftFont} mono fallback for any codepoint the pack does not
 * define. Layout and paint go through {@link MinecraftGlyphVector}; measurement goes through
 * {@link ColorFontMetrics} - both share one advance source so measure equals draw.
 */
public final class MinecraftColorFont {

    /**
     * Classpath / cache subdirectory the colour {@code .ttf} files and shared sidecar live under.
     */
    public static final @NotNull String RESOURCE_DIR = "colorfont";

    /**
     * Shared sidecar filename.
     */
    public static final @NotNull String SIDECAR_NAME = "colour-glyphs.json";

    private final @NotNull FontId fontId;
    private final @NotNull SbixStrikeCache strikes;
    private final @NotNull ColorGlyphSidecar sidecar;
    private final @NotNull MinecraftFont monoFallback;
    private final @NotNull ColorFontMetrics metrics;

    private MinecraftColorFont(
        @NotNull FontId fontId,
        @NotNull SbixStrikeCache strikes,
        @NotNull ColorGlyphSidecar sidecar,
        @NotNull MinecraftFont monoFallback
    ) {
        this.fontId = fontId;
        this.strikes = strikes;
        this.sidecar = sidecar;
        this.monoFallback = monoFallback;
        this.metrics = new ColorFontMetrics(fontId, sidecar, monoFallback);
    }

    /**
     * Builds a colour font from explicit parts. The primary constructor for callers that already
     * hold the strike cache and parsed sidecar (e.g. a pack loader or a test fixture).
     *
     * @param fontId the font id
     * @param strikes the strike cache over this font's {@code .ttf}
     * @param sidecar the parsed sidecar (may span multiple font ids)
     * @param monoFallback the vanilla font used for codepoints the pack does not define
     * @return the colour font
     */
    public static @NotNull MinecraftColorFont of(
        @NotNull FontId fontId,
        @NotNull SbixStrikeCache strikes,
        @NotNull ColorGlyphSidecar sidecar,
        @NotNull MinecraftFont monoFallback
    ) {
        return new MinecraftColorFont(fontId, strikes, sidecar, monoFallback);
    }

    /**
     * Resolves a colour font for a font id from the classpath, then the user-home cache, using
     * {@link MinecraftFont#REGULAR} as the mono fallback.
     * <p>
     * The shared {@code colour-glyphs.json} sidecar is read first (it names the per-font-id
     * {@code .ttf} file), then that {@code .ttf} is loaded. On a miss the failure names every path
     * that was tried, mirroring {@link MinecraftFont}'s fail-loud bootstrap message.
     *
     * @param fontId the font id to load
     * @return the resolved colour font
     * @throws IllegalStateException when the sidecar, the font id, or its {@code .ttf} cannot be found
     */
    public static @NotNull MinecraftColorFont load(@NotNull FontId fontId) {
        return load(fontId, MinecraftFont.REGULAR);
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
        SbixStrikeCache strikes = SbixStrikeCache.of(loadTtfBytes(file));
        return of(fontId, strikes, sidecar, monoFallback);
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

    /**
     * Lays out a run of text into a positioned colour glyph vector.
     *
     * @param text the text to lay out
     * @return the glyph vector
     */
    public @NotNull MinecraftGlyphVector layout(@NotNull String text) {
        return MinecraftGlyphVector.layout(this, text);
    }

    /**
     * @return the font id
     */
    public @NotNull FontId fontId() {
        return this.fontId;
    }

    /**
     * @return the strike cache
     */
    public @NotNull SbixStrikeCache strikes() {
        return this.strikes;
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

    /**
     * @return the advance/line metrics
     */
    public @NotNull ColorFontMetrics metrics() {
        return this.metrics;
    }

}

package lib.minecraft.text.font;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import dev.simplified.util.SystemUtil;
import lib.minecraft.text.tooling.ToolingFonts;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The Minecraft vanilla font family plus its two alternate-script companions, each wrapping a
 * pre-loaded {@link Font} and a lazy glyph atlas for pure {@link PixelBuffer} text rendering.
 * <p>
 * At enum initialization, the underlying {@code .otf} font is loaded via AWT and font-level
 * metrics (ascent, descent, height) are captured. Printable ASCII glyphs (codepoints 32-126)
 * are eagerly rasterized into the glyph cache so the first render has zero AWT overhead. All
 * remaining glyphs are lazily rasterized on first use and cached for subsequent renders.
 * <p>
 * Glyph bitmaps are stored as white-on-transparent {@link PixelBuffer} instances. At draw
 * time, callers tint each pixel by multiplying the glyph's alpha with a target color - no AWT
 * {@link Graphics2D} is needed after initialization.
 *
 * @see GlyphData
 * @see MinecraftFontMetrics
 */
@Getter
@RequiredArgsConstructor
public enum MinecraftFont {

    REGULAR("Minecraft-Regular.otf", Style.REGULAR),
    BOLD("Minecraft-Bold.otf", Style.BOLD),
    ITALIC("Minecraft-Italic.otf", Style.ITALIC),
    BOLD_ITALIC("Minecraft-BoldItalic.otf", Style.BOLD_ITALIC),
    GALACTIC("Minecraft-Galactic.otf", Style.GALACTIC),
    ILLAGERALT("Minecraft-Illageralt.otf", Style.ILLAGERALT);

    /**
     * Load size (in AWT points) for every Minecraft font file.
     * <p>
     * {@code 16.0f} is twice the vanilla mcPixel resolution so the bitmap-derived OTFs render
     * at the {@link #MC_PIXEL_SCALE} integer factor. The cached OTFs use {@code unitsPerEm = 1024}
     * with {@code 128 units = 1 mcPixel}, so 1 em corresponds to 8 mcPixels and the load size
     * must be an integer multiple of 8 to keep every glyph on integer output pixel boundaries
     * at AWT's fixed 72 DPI.
     */
    public static final float FONT_POINT_SIZE = 16.0f;

    /**
     * First printable ASCII codepoint the font eagerly pre-caches at enum init.
     */
    private static final int EAGER_ASCII_START = 32;

    /**
     * Last printable ASCII codepoint eagerly pre-cached.
     */
    private static final int EAGER_ASCII_END = 126;

    /**
     * Output pixels per vanilla Minecraft pixel for the current native {@code 16.0f} load
     * size. Driven by the {@code unitsPerEm = 1024}, {@code 128 units = 1 mcPixel} layout
     * that the bitmap-to-OTF generator bakes into every font file. Callers positioning
     * text-adjacent geometry (line spacing, tooltip padding, decoration offsets) should
     * express their measurements in terms of this constant rather than hardcoding the
     * {@code 2x} factor.
     */
    public static final int MC_PIXEL_SCALE = 2;

    /**
     * Minecraft version used by the runtime font bootstrap when the classpath has no {@code fonts/}
     * resources. Mirrors {@link ToolingFonts#DEFAULT_VERSION} so the in-module Gradle task and the
     * runtime cache produce byte-identical output.
     */
    public static final @NotNull String DEFAULT_VERSION = ToolingFonts.DEFAULT_VERSION;

    /**
     * The underlying AWT font, retained for lazy glyph rasterization.
     */
    private final @NotNull java.awt.Font actual;

    /**
     * Filesystem path to the backing {@code .otf} file. Guaranteed to exist after construction -
     * when the font resolves from the classpath (tier 1), the bytes are also materialized into the
     * cache so this path is always usable downstream.
     */
    private final @NotNull Path path;

    /**
     * The style category this enum value belongs to.
     */
    private final @NotNull Style style;

    /**
     * Font-level metrics backed by the glyph atlas, captured at init time.
     */
    private final @NotNull MinecraftFontMetrics fontMetrics;

    /**
     * Lazily populated glyph cache - eagerly filled with ASCII at init, rest on demand.
     */
    private final @NotNull ConcurrentMap<Integer, GlyphData> glyphCache;

    MinecraftFont(@NotNull String fileName, @NotNull Style style) {
        Resolved resolved = resolveFont(fileName);
        this.actual = resolved.font();
        this.path = resolved.path();
        this.style = style;
        this.glyphCache = Concurrent.newMap();
        this.fontMetrics = MinecraftFontMetrics.capture(this);

        // Eagerly rasterize printable ASCII so the first render has zero AWT overhead.
        for (int cp = EAGER_ASCII_START; cp <= EAGER_ASCII_END; cp++)
            this.glyphCache.put(cp, rasterizeGlyph(cp));
    }

    /**
     * Returns the {@link MinecraftFont} whose {@link #getStyle() style} matches the given
     * {@link Style}, falling back to {@link #REGULAR} when no match exists.
     *
     * @param style the style to look up
     * @return the matching font, or {@link #REGULAR} when none is registered for the style
     */
    public static @NotNull MinecraftFont of(@NotNull Style style) {
        for (MinecraftFont font : values())
            if (font.getStyle() == style) return font;

        return REGULAR;
    }

    /**
     * Returns the cached glyph data for the given codepoint, lazily rasterizing it on first
     * access. Thread-safe via {@link ConcurrentMap#computeIfAbsent}.
     *
     * @param codepoint the Unicode codepoint
     * @return the cached glyph data
     */
    public @NotNull GlyphData glyph(int codepoint) {
        return this.glyphCache.computeIfAbsent(codepoint, this::rasterizeGlyph);
    }

    // --- init ---

    /**
     * Rasterizes a single glyph as white-on-transparent into a {@link PixelBuffer}. Queries the
     * AWT {@link FontMetrics} and {@link FontRenderContext} already captured on
     * {@link #fontMetrics} rather than spinning up a throwaway scratch {@link Graphics2D} for
     * every codepoint - only the per-glyph {@link BufferedImage} (sized to the visual bounds)
     * is newly allocated.
     */
    private @NotNull GlyphData rasterizeGlyph(int codepoint) {
        int advanceWidth = this.fontMetrics.getAwtMetrics().charWidth(codepoint);
        char[] chars = Character.toChars(codepoint);
        GlyphVector gv = this.actual.createGlyphVector(this.fontMetrics.getAwtFrc(), chars);
        Rectangle2D bounds = gv.getVisualBounds();

        int bw = Math.max(1, (int) Math.ceil(bounds.getWidth()));
        int bh = Math.max(1, (int) Math.ceil(bounds.getHeight()));
        int bearingX = (int) Math.floor(bounds.getX());
        int bearingY = (int) Math.floor(bounds.getY());

        BufferedImage glyphImage = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = glyphImage.createGraphics();

        try {
            gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gg.setFont(this.actual);
            gg.setColor(Color.WHITE);
            gg.drawString(new String(chars), -bearingX, -bearingY);
        } finally {
            gg.dispose();
        }

        return new GlyphData(PixelBuffer.wrap(glyphImage), advanceWidth, bearingX, bearingY);
    }

    /**
     * Resolves an {@code .otf} file using a 3-tier strategy and returns the loaded AWT font
     * alongside its filesystem location. Called exclusively from the enum constructor, so the
     * JVM's per-class {@code <clinit>} monitor (JLS 12.4.2) guarantees mutual exclusion across
     * enum values - no additional locks are required.
     *
     * <ol>
     *   <li><b>Classpath</b>: when {@code /fonts/<fileName>} resolves via the classloader (the
     *       in-module build stages OTFs onto the classpath via {@code processResources}), the
     *       bytes are copied into the filesystem cache so {@link #getPath} always points to a
     *       real file, then the font loads from that file.</li>
     *   <li><b>Filesystem cache</b>: check
     *       {@code DEFAULT_CACHE_ROOT/fonts/DEFAULT_VERSION/<fileName>} for a previously generated
     *       copy and load it directly.</li>
     *   <li><b>Auto-bootstrap</b>: invoke {@link ToolingFonts#generate} to produce the file, then
     *       retry tier 2.</li>
     * </ol>
     *
     * <p>On final miss, throws {@link IllegalStateException} naming every path that was tried
     * along with remediation instructions (run the Gradle task or install git + Python 3.10+).
     */
    private static @NotNull Resolved resolveFont(@NotNull String fileName) {
        String classpathPath = "fonts/" + fileName;
        Path cachedPath = defaultCacheRoot().resolve("fonts").resolve(DEFAULT_VERSION).resolve(fileName);

        // Tier 1: classpath. Materialize to filesystem cache so getPath() is always valid.
        try (InputStream cpStream = MinecraftFont.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (cpStream != null) {
                Files.createDirectories(cachedPath.getParent());
                Files.copy(cpStream, cachedPath, StandardCopyOption.REPLACE_EXISTING);
                return new Resolved(createFontFromPath(cachedPath), cachedPath);
            }
        } catch (IOException ex) {
            // Classpath present but I/O failed; fall through to filesystem/bootstrap rather than fail-fast.
        }

        // Tier 2: filesystem cache.
        if (Files.isRegularFile(cachedPath))
            return new Resolved(createFontFromPath(cachedPath), cachedPath);

        // Tier 3: auto-bootstrap via ToolingFonts.
        try {
            ToolingFonts.generate(DEFAULT_VERSION, defaultCacheRoot());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(bootstrapFailureMessage(fileName, classpathPath, cachedPath), ex);
        } catch (IOException ex) {
            throw new IllegalStateException(bootstrapFailureMessage(fileName, classpathPath, cachedPath), ex);
        }

        if (Files.isRegularFile(cachedPath))
            return new Resolved(createFontFromPath(cachedPath), cachedPath);

        throw new IllegalStateException(bootstrapFailureMessage(fileName, classpathPath, cachedPath));
    }

    /**
     * User-home cache root for runtime font bootstrap. {@code %LOCALAPPDATA%\minecraft-library} on
     * Windows (falling back to {@code user.home\AppData\Local\minecraft-library} when the env var
     * is unset) and {@code ~/.cache/minecraft-library} elsewhere. Durable across projects and
     * working directories so every caller of this library shares one OTF cache.
     *
     * <p>Backed by a holder class so initialization is deferred until first call. Enum-constant
     * construction happens before static fields declared after the constants are initialized
     * (JLS 12.4.1), so a direct {@code public static final Path} field would be {@code null}
     * when {@link #resolveFont} first runs.
     */
    public static @NotNull Path defaultCacheRoot() {
        return DefaultsHolder.CACHE_ROOT;
    }

    private static final class DefaultsHolder {
        static final @NotNull Path CACHE_ROOT = computeDefaultCacheRoot();
    }

    /**
     * Reads an {@code .otf} file from disk, registers it with AWT, and derives to the native load size.
     */
    private static @NotNull java.awt.Font createFontFromPath(@NotNull Path otfPath) {
        try (InputStream in = Files.newInputStream(otfPath)) {
            Font font = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(FONT_POINT_SIZE);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font;
        } catch (IOException | FontFormatException ex) {
            throw new IllegalStateException("Unable to load font from file '" + otfPath + "'", ex);
        }
    }

    private static @NotNull String bootstrapFailureMessage(@NotNull String fileName, @NotNull String classpathPath, @NotNull Path cachedPath) {
        return String.format(
            "Unable to load font '%s' after all fallbacks.%n" +
                "  Tier 1 (classpath): /%s%n" +
                "  Tier 2 (filesystem cache): %s%n" +
                "  Tier 3 (auto-bootstrap via ToolingFonts.generate): did not produce the expected file%n" +
                "Fix: run `./gradlew :minecraft-text:fonts` to pre-warm the cache, " +
                "or ensure `git` and Python 3.10+ are on PATH so auto-bootstrap can run.",
            fileName, classpathPath, cachedPath);
    }

    private static @NotNull Path computeDefaultCacheRoot() {

        String osName = System.getProperty("os.name", "").toLowerCase();
        String userHome = System.getProperty("user.home", ".");
        if (osName.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank())
                return Path.of(localAppData, "minecraft-library");
            return Path.of(userHome, "AppData", "Local", "minecraft-library");
        }
        return Path.of(userHome, ".cache", "minecraft-library");
    }

    // --- inner types ---

    /**
     * Internal result of {@link #resolveFont}: the loaded AWT font plus the on-disk path of its {@code .otf}.
     */
    private record Resolved(@NotNull java.awt.Font font, @NotNull Path path) {}

    /**
     * Rasterized glyph data: the bitmap pixels and positioning metrics needed to blit the
     * glyph at the correct location relative to the text cursor.
     *
     * @param bitmap the glyph pixels (white-on-transparent)
     * @param advanceWidth the horizontal cursor advance after this glyph
     * @param bearingX the left bearing - horizontal offset from cursor to left edge of bitmap
     * @param bearingY the top bearing - vertical offset from baseline to top edge of bitmap
     */
    public record GlyphData(
        @NotNull PixelBuffer bitmap,
        int advanceWidth,
        int bearingX,
        int bearingY
    ) {}

    /**
     * The style category a {@link MinecraftFont} entry belongs to.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Style {

        REGULAR(0),
        BOLD(1),
        ITALIC(2),
        BOLD_ITALIC(3),
        GALACTIC(4),
        ILLAGERALT(5);

        private final int id;

        /**
         * Returns the {@link Style} whose {@link #getId() id} matches the given value, or
         * {@link #REGULAR} when no match exists.
         *
         * @param id the style id to look up
         * @return the matching style, or {@link #REGULAR} when none matches
         */
        public static @NotNull Style of(int id) {
            for (Style style : values())
                if (style.getId() == id) return style;

            return REGULAR;
        }

    }

}

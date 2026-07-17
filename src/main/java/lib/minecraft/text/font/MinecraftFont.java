package lib.minecraft.text.font;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.text.tooling.ToolingFonts;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The unified home for every Minecraft-style font, whether a vanilla monospace atlas or a pack
 * colour ({@code sbix}) font. It is a sealed interface so a single downstream surface -
 * {@link #glyph(int)}, {@link #metrics()}, {@link #fontId()}, {@link #layout(String)} - serves both
 * kinds with no caller-side type gating.
 * <p>
 * Two implementations exist:
 * <ul>
 *   <li>{@link Vanilla} - the fixed six-value enum backing the vanilla {@code .otf} family
 *   (regular/bold/italic/bold-italic and the two alternate scripts). It owns everything AWT: font
 *   resolution, glyph rasterization, the render context, and the eager ASCII atlas.</li>
 *   <li>{@link MinecraftColorFont} - an open, pack-supplied colour font keyed by {@link FontId},
 *   backed by an {@code sbix} strike cache and a vanilla mono fallback.</li>
 * </ul>
 * <p>
 * A process-wide {@code FontId -> MinecraftFont} registry (see {@link #register}, {@link #getOrLoad})
 * holds both kinds under one key space: the vanilla constants auto-register their synthetic ids
 * ({@code minecraft:default}, {@code minecraft:default/bold}, ...) so consumers iterate
 * {@link #fontIds()} rather than switching on the enum.
 *
 * @see GlyphData
 * @see MinecraftFontMetrics
 * @see MinecraftGlyphVector
 */
public sealed interface MinecraftFont permits MinecraftFont.Vanilla, MinecraftColorFont {

    /**
     * Load size (in AWT points) for every Minecraft font file.
     * <p>
     * {@code 16.0f} is twice the vanilla mcPixel resolution so the bitmap-derived OTFs render at the
     * {@link #MC_PIXEL_SCALE} integer factor. The cached OTFs use {@code unitsPerEm = 1024} with
     * {@code 128 units = 1 mcPixel}, so 1 em corresponds to 8 mcPixels and the load size must be an
     * integer multiple of 8 to keep every glyph on integer output pixel boundaries at AWT's fixed
     * 72 DPI.
     */
    float FONT_POINT_SIZE = 16.0f;

    /**
     * Output pixels per vanilla Minecraft pixel for the current native {@code 16.0f} load size.
     * Driven by the {@code unitsPerEm = 1024}, {@code 128 units = 1 mcPixel} layout that the
     * bitmap-to-OTF generator bakes into every font file. Callers positioning text-adjacent geometry
     * (line spacing, tooltip padding, decoration offsets) should express their measurements in terms
     * of this constant rather than hardcoding the {@code 2x} factor.
     */
    int MC_PIXEL_SCALE = 2;

    /**
     * Horizontal shear of the italic OTFs, as {@code dx} per {@code +1} unit above the baseline.
     * <p>
     * The italic slant is baked into {@code Minecraft-Italic.otf} / {@code Minecraft-BoldItalic.otf}
     * at generation time - there is no runtime glyph matrix to intercept. This constant mirrors the
     * font-generator's {@code ITALIC_SHEAR_FACTOR = 1 / ITALIC_SHEAR_VERTICAL} (with
     * {@code ITALIC_SHEAR_VERTICAL = 5}), which each glyph contour is sheared by as
     * {@code (sx + sy * factor, sy)}. Because the shear is scale-uniform it is the same slope in
     * output px, so a renderer that wants a gradient's colour bands to run parallel to italic
     * letterforms slants them by this factor. It does not affect glyph drawing.
     */
    float ITALIC_SHEAR = 1.0f / 5.0f;

    /**
     * Minecraft version used by the runtime font bootstrap when the classpath has no {@code fonts/}
     * resources. Mirrors {@link ToolingFonts#DEFAULT_VERSION} so the in-module Gradle task and the
     * runtime cache produce byte-identical output.
     */
    @NotNull String DEFAULT_VERSION = ToolingFonts.DEFAULT_VERSION;

    // --- shared surface ---

    /**
     * Returns the glyph data for a codepoint, rasterizing or resolving it on first access and
     * caching the result. Never returns {@code null}: an advance-only glyph (a pack space provider,
     * or a colour row whose strike failed to decode) is modelled as a {@link Kind#SPACE} sentinel.
     *
     * @param codepoint the Unicode codepoint
     * @return the glyph data
     */
    @NotNull GlyphData glyph(int codepoint);

    /**
     * Returns the font's metrics - advances and line geometry, all derived from
     * {@link GlyphData#signedAdvance()} so measurement and layout agree exactly.
     *
     * @return the font metrics
     */
    @NotNull MinecraftFontMetrics metrics();

    /**
     * Returns the font id this font registers under.
     *
     * @return the font id
     */
    @NotNull FontId fontId();

    /**
     * Lays out a run of text into a positioned glyph vector. The one layout entry point for both
     * font kinds; the vector positions its pens from {@link GlyphData#signedAdvance()} with no
     * vanilla-vs-colour branch.
     *
     * @param text the text to lay out
     * @return the positioned glyph vector
     */
    default @NotNull MinecraftGlyphVector layout(@NotNull String text) {
        return MinecraftGlyphVector.of(this, text);
    }

    /**
     * Returns the fillable vector outline of a mono codepoint, translated by {@code penX}, for the
     * glyph-knockout hook. Fonts and glyphs with no outline ({@code sbix} strikes, space providers)
     * return empty.
     *
     * @param codepoint the Unicode codepoint
     * @param penX the pen position to translate the outline by, in output pixels
     * @return the translated outline, or empty when the glyph has no fillable outline
     */
    default @NotNull Optional<Shape> monoOutline(int codepoint, double penX) {
        return Optional.empty();
    }

    // --- cache root ---

    /**
     * User-home cache root for runtime font bootstrap. {@code %LOCALAPPDATA%\minecraft-library} on
     * Windows (falling back to {@code user.home\AppData\Local\minecraft-library} when the env var is
     * unset) and {@code ~/.cache/minecraft-library} elsewhere. Durable across projects and working
     * directories so every caller of this library shares one OTF cache.
     *
     * <p>Backed by a holder class so initialization is deferred until first call, respecting the
     * interface/enum initialization order (JLS 12.4.1).
     *
     * @return the cache root path
     */
    static @NotNull Path defaultCacheRoot() {
        return DefaultsHolder.CACHE_ROOT;
    }

    // --- font registry (FontId -> MinecraftFont) ---

    /**
     * Registers a font, replacing any previous registration for its {@link #fontId()}.
     *
     * @param font the font to register
     * @return the registered font
     */
    static @NotNull MinecraftFont register(@NotNull MinecraftFont font) {
        Registry.MAP.put(font.fontId(), font);
        return font;
    }

    /**
     * Returns the registered font for a font id, if any. Vanilla ids are auto-registered on first
     * registry use.
     *
     * @param fontId the font id
     * @return the registered font, or empty
     */
    static @NotNull Optional<MinecraftFont> get(@NotNull FontId fontId) {
        Registry.ensureVanilla();
        return Optional.ofNullable(Registry.MAP.get(fontId));
    }

    /**
     * Returns the registered font for a font id, resolving and registering a pack
     * {@link MinecraftColorFont} via {@link MinecraftColorFont#load(FontId)} on first use.
     *
     * @param fontId the font id
     * @return the font
     */
    static @NotNull MinecraftFont getOrLoad(@NotNull FontId fontId) {
        Registry.ensureVanilla();
        return Registry.MAP.computeIfAbsent(fontId, MinecraftColorFont::load);
    }

    /**
     * Removes a font id's registration. Vanilla ids re-register on the next {@link #clear()} or
     * registry read, so this is meaningful only for pack fonts.
     *
     * @param fontId the font id to unregister
     */
    static void unregister(@NotNull FontId fontId) {
        Registry.MAP.remove(fontId);
    }

    /**
     * Clears every pack registration and re-registers the vanilla constants. Primarily for test
     * isolation; the vanilla ids always remain present so downstream iteration stays stable.
     */
    static void clear() {
        Registry.MAP.clear();
        Registry.registerVanilla();
    }

    /**
     * Returns a snapshot of every registered font id. Vanilla ids are auto-registered on first use.
     *
     * @return the registered font ids
     */
    static @NotNull Set<FontId> fontIds() {
        Registry.ensureVanilla();
        return Set.copyOf(Registry.MAP.keySet());
    }

    // --- inner types ---

    /**
     * The fixed vanilla font family plus its two alternate-script companions, each wrapping a
     * pre-loaded {@link Font} and a lazy glyph atlas for pure {@link PixelBuffer} text rendering.
     * <p>
     * At enum initialization the underlying {@code .otf} font is loaded via AWT and font-level
     * metrics (ascent, descent, height) are captured. Printable ASCII glyphs (codepoints 32-126) are
     * eagerly rasterized so the first render has zero AWT overhead; all other glyphs are lazily
     * rasterized on first use and cached. Glyph bitmaps are white-on-transparent and tinted at draw
     * time - no {@link Graphics2D} is needed after initialization.
     */
    @Getter
    enum Vanilla implements MinecraftFont {

        REGULAR("Minecraft-Regular.otf", Style.REGULAR, "minecraft:default"),
        BOLD("Minecraft-Bold.otf", Style.BOLD, "minecraft:default/bold"),
        ITALIC("Minecraft-Italic.otf", Style.ITALIC, "minecraft:default/italic"),
        BOLD_ITALIC("Minecraft-BoldItalic.otf", Style.BOLD_ITALIC, "minecraft:default/bold_italic"),
        GALACTIC("Minecraft-Galactic.otf", Style.GALACTIC, "minecraft:alt"),
        ILLAGERALT("Minecraft-Illageralt.otf", Style.ILLAGERALT, "minecraft:illageralt");

        /**
         * First printable ASCII codepoint the font eagerly pre-caches at enum init.
         */
        private static final int EAGER_ASCII_START = 32;

        /**
         * Last printable ASCII codepoint eagerly pre-cached.
         */
        private static final int EAGER_ASCII_END = 126;

        /**
         * The underlying AWT font, retained for lazy glyph rasterization.
         */
        private final @NotNull java.awt.Font actual;

        /**
         * Filesystem path to the backing {@code .otf} file. Guaranteed to exist after construction.
         */
        private final @NotNull Path path;

        /**
         * The style category this enum value belongs to.
         */
        private final @NotNull Style style;

        @Getter(lombok.AccessLevel.NONE)
        private final @NotNull FontId fontId;

        @Getter(lombok.AccessLevel.NONE)
        private final @NotNull MinecraftFontMetrics metrics;

        /**
         * The AWT {@link FontMetrics} captured at init - reused by glyph rasterization.
         */
        private final @NotNull FontMetrics awtMetrics;

        /**
         * The AWT {@link FontRenderContext} captured at init - reused by glyph rasterization and the
         * knockout outline hook.
         */
        private final @NotNull FontRenderContext awtFrc;

        @Getter(lombok.AccessLevel.NONE)
        private final @NotNull ConcurrentMap<Integer, GlyphData> glyphCache;

        Vanilla(@NotNull String fileName, @NotNull Style style, @NotNull String fontId) {
            Resolved resolved = resolveFont(fileName);
            this.actual = resolved.font();
            this.path = resolved.path();
            this.style = style;
            this.fontId = FontId.parse(fontId);
            this.glyphCache = Concurrent.newMap();

            BufferedImage temp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = temp.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(this.actual);
                this.awtMetrics = g.getFontMetrics();
                this.awtFrc = g.getFontRenderContext();
            } finally {
                g.dispose();
            }

            this.metrics = MinecraftFontMetrics.of(this, this.actual,
                this.awtMetrics.getAscent(), this.awtMetrics.getDescent(), this.awtMetrics.getHeight());

            // Eagerly rasterize printable ASCII so the first render has zero AWT overhead.
            for (int cp = EAGER_ASCII_START; cp <= EAGER_ASCII_END; cp++)
                this.glyphCache.put(cp, rasterizeGlyph(cp));
        }

        /**
         * Returns the vanilla font whose {@link #getStyle() style} matches the given {@link Style},
         * falling back to {@link #REGULAR} when no match exists.
         *
         * @param style the style to look up
         * @return the matching font, or {@link #REGULAR}
         */
        public static @NotNull Vanilla of(@NotNull Style style) {
            for (Vanilla font : values())
                if (font.style == style) return font;

            return REGULAR;
        }

        @Override
        public @NotNull GlyphData glyph(int codepoint) {
            return this.glyphCache.computeIfAbsent(codepoint, this::rasterizeGlyph);
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
            GlyphVector vector = this.actual.createGlyphVector(this.awtFrc, new String(Character.toChars(codepoint)));
            Shape outline = vector.getGlyphOutline(0);
            return Optional.of(AffineTransform.getTranslateInstance(penX, 0).createTransformedShape(outline));
        }

        /**
         * Rasterizes a single glyph as white-on-transparent into a {@link PixelBuffer}. Queries the
         * AWT {@link FontMetrics} and {@link FontRenderContext} captured at init rather than spinning
         * up a throwaway scratch {@link Graphics2D} for every codepoint - only the per-glyph
         * {@link BufferedImage} (sized to the visual bounds) is newly allocated.
         */
        private @NotNull GlyphData rasterizeGlyph(int codepoint) {
            int advanceWidth = this.awtMetrics.charWidth(codepoint);
            char[] chars = Character.toChars(codepoint);
            GlyphVector gv = this.actual.createGlyphVector(this.awtFrc, chars);
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
         *   <li><b>Classpath</b>: when {@code /fonts/<fileName>} resolves via the classloader, the
         *       bytes are copied into the filesystem cache so {@link #getPath} always points to a
         *       real file, then the font loads from that file.</li>
         *   <li><b>Filesystem cache</b>: check
         *       {@code DEFAULT_CACHE_ROOT/fonts/DEFAULT_VERSION/<fileName>} and load it directly.</li>
         *   <li><b>Auto-bootstrap</b>: invoke {@link ToolingFonts#generate} to produce the file, then
         *       retry tier 2.</li>
         * </ol>
         *
         * <p>On final miss, throws {@link IllegalStateException} naming every path that was tried.
         */
        private static @NotNull Resolved resolveFont(@NotNull String fileName) {
            String classpathPath = "fonts/" + fileName;
            Path cachedPath = defaultCacheRoot().resolve("fonts").resolve(DEFAULT_VERSION).resolve(fileName);

            // Tier 1: classpath. Materialize to filesystem cache so getPath() is always valid.
            try (InputStream cpStream = Vanilla.class.getClassLoader().getResourceAsStream(classpathPath)) {
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

        /**
         * Internal result of {@link #resolveFont}: the loaded AWT font plus the on-disk path of its {@code .otf}.
         */
        private record Resolved(@NotNull java.awt.Font font, @NotNull Path path) {}

    }

    /**
     * Rasterized glyph data: the bitmap pixels and positioning metrics needed to blit the glyph at
     * the correct location relative to the text cursor, plus the {@link Kind} that tells the draw
     * path how to composite it.
     * <p>
     * Three kinds exist, all reached through the same {@link #glyph(int)} surface:
     * <ul>
     *   <li>{@link Kind#MONO} - a vanilla white-on-transparent atlas bitmap, tinted at draw time;
     *   {@link #signedAdvance} equals the integer {@link #advanceWidth}. The four-argument
     *   constructor produces this form and is the only shape the vanilla rasterizer uses, so the
     *   mono path is unchanged byte-for-byte.</li>
     *   <li>{@link Kind#RASTER} - a native RGBA colour ({@code sbix}) strike, blitted untinted; it
     *   may carry a fractional/negative {@link #signedAdvance}, a non-zero origin, and the
     *   {@link #gid}/{@link #strikePpem} the artwork came from.</li>
     *   <li>{@link Kind#SPACE} - an advance-only sentinel over a 1x1 transparent bitmap; it moves the
     *   pen (possibly backward) and paints nothing.</li>
     * </ul>
     *
     * @param bitmap the glyph pixels (white-on-transparent for mono, native RGBA for colour, 1x1
     * transparent for space)
     * @param advanceWidth the integer horizontal cursor advance after this glyph, in output pixels
     * @param bearingX the left bearing - horizontal offset from cursor to left edge of bitmap
     * @param bearingY the top bearing - vertical offset from baseline to top edge of bitmap
     * @param color whether the bitmap is native colour artwork (never tinted) rather than mono
     * @param signedAdvance the signed, possibly fractional advance in output pixels; equals
     * {@link #advanceWidth} for mono glyphs
     * @param originX the glyph origin X offset in output pixels (0 for mono/space glyphs)
     * @param originY the glyph origin Y offset in output pixels (0 for mono/space glyphs)
     * @param gid the {@code sbix} glyph id the strike came from, or {@code -1} for mono/space glyphs
     * @param strikePpem the {@code sbix} strike ppem, or {@code -1} for mono/space glyphs
     * @param kind the glyph kind driving the draw path
     */
    record GlyphData(
        @NotNull PixelBuffer bitmap,
        int advanceWidth,
        int bearingX,
        int bearingY,
        boolean color,
        float signedAdvance,
        int originX,
        int originY,
        int gid,
        int strikePpem,
        MinecraftGlyphVector.@NotNull Kind kind
    ) {

        /**
         * A shared 1x1 fully-transparent bitmap backing every {@link Kind#SPACE} sentinel. Immutable
         * in practice - the blit path only reads glyph bitmaps - so one instance is safe to share.
         */
        private static final @NotNull PixelBuffer SPACE_BITMAP = PixelBuffer.create(1, 1);

        /**
         * Constructs a monochrome glyph: {@code color = false}, {@code signedAdvance = advanceWidth},
         * a zero origin, and {@link Kind#MONO}. This is the vanilla rasterization form.
         *
         * @param bitmap the white-on-transparent glyph pixels
         * @param advanceWidth the integer horizontal cursor advance
         * @param bearingX the left bearing
         * @param bearingY the top bearing
         */
        public GlyphData(@NotNull PixelBuffer bitmap, int advanceWidth, int bearingX, int bearingY) {
            this(bitmap, advanceWidth, bearingX, bearingY, false, advanceWidth, 0, 0, -1, -1, MinecraftGlyphVector.Kind.MONO);
        }

        /**
         * Constructs a colour glyph from a native RGBA bitmap and sidecar-sourced positioning,
         * without strike provenance.
         *
         * @param bitmap the native RGBA artwork
         * @param signedAdvance the signed, possibly fractional advance in output pixels
         * @param originX the origin X offset in output pixels
         * @param originY the origin Y offset in output pixels
         * @return the colour glyph data
         */
        public static @NotNull GlyphData color(@NotNull PixelBuffer bitmap, float signedAdvance, int originX, int originY) {
            return color(bitmap, signedAdvance, originX, originY, -1, -1);
        }

        /**
         * Constructs a colour glyph from a native RGBA bitmap, sidecar-sourced positioning, and the
         * {@code sbix} strike it was decoded from. The origin doubles as the blit bearing.
         *
         * @param bitmap the native RGBA artwork
         * @param signedAdvance the signed, possibly fractional advance in output pixels
         * @param originX the origin X offset in output pixels
         * @param originY the origin Y offset in output pixels
         * @param gid the strike glyph id
         * @param strikePpem the strike ppem
         * @return the colour glyph data
         */
        public static @NotNull GlyphData color(@NotNull PixelBuffer bitmap, float signedAdvance, int originX, int originY, int gid, int strikePpem) {
            return new GlyphData(bitmap, Math.round(signedAdvance), originX, originY, true, signedAdvance, originX, originY, gid, strikePpem, MinecraftGlyphVector.Kind.RASTER);
        }

        /**
         * Constructs an advance-only {@link Kind#SPACE} sentinel: a 1x1 transparent bitmap that moves
         * the pen by {@code signedAdvance} (possibly backward) and paints nothing.
         *
         * @param signedAdvance the signed, possibly fractional advance in output pixels
         * @return the space glyph data
         */
        public static @NotNull GlyphData space(float signedAdvance) {
            return new GlyphData(SPACE_BITMAP, Math.round(signedAdvance), 0, 0, false, signedAdvance, 0, 0, -1, -1, MinecraftGlyphVector.Kind.SPACE);
        }

    }

    /**
     * The style category a {@link Vanilla} font entry belongs to.
     */
    @Getter
    @lombok.RequiredArgsConstructor
    enum Style {

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

    /**
     * Holder for the deferred user-home cache root. Enum-constant construction happens before static
     * fields declared after the constants are initialized (JLS 12.4.1), so deferring the computation
     * behind a holder keeps {@link #defaultCacheRoot()} usable from the {@link Vanilla} constructor.
     */
    final class DefaultsHolder {

        static final @NotNull Path CACHE_ROOT = computeDefaultCacheRoot();

        private DefaultsHolder() {}

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

    }

    /**
     * Backing store for the {@code FontId -> MinecraftFont} registry. The vanilla constants
     * auto-register their synthetic ids on first registry use; forcing {@link Vanilla} class init
     * through a guarded {@link #registerVanilla()} avoids an NPE from interface/enum init ordering.
     */
    final class Registry {

        static final @NotNull java.util.concurrent.ConcurrentMap<FontId, MinecraftFont> MAP = new ConcurrentHashMap<>();

        private static volatile boolean vanillaRegistered = false;

        private Registry() {}

        static void ensureVanilla() {
            if (!vanillaRegistered) registerVanilla();
        }

        static synchronized void registerVanilla() {
            for (Vanilla font : Vanilla.values())
                MAP.put(font.fontId(), font);
            vanillaRegistered = true;
        }

    }

}

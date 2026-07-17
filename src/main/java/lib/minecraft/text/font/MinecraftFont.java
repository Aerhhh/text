package lib.minecraft.text.font;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.text.tooling.ToolingFonts;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The unified home for every Minecraft-style font, whether a vanilla monospace atlas or a pack
 * colour ({@code sbix}) font. It is a sealed interface so a single downstream surface -
 * {@link #glyph(int)}, {@link #metrics()}, {@link #fontId()}, {@link #layout(String)} - serves both
 * kinds with no caller-side type gating. Every codepoint resolves through {@link #glyph(int)} into a
 * single {@link MinecraftGlyph}, so cache, layout, and blit all speak one glyph type.
 * <p>
 * Two implementations exist:
 * <ul>
 *   <li>{@link Vanilla} - the fixed six-value enum backing the vanilla {@code .otf} family
 *   (regular/bold/italic/bold-italic and the two alternate scripts). It owns everything AWT: font
 *   resolution, glyph rasterization, the render context, and the eager ASCII atlas.</li>
 *   <li>{@link Color} - an open, pack-supplied colour font keyed by {@link FontId}, backed by an
 *   {@code sbix} strike cache and a vanilla mono fallback.</li>
 * </ul>
 * <p>
 * A process-wide {@code FontId -> MinecraftFont} registry (see {@link #register}, {@link #getOrLoad})
 * holds both kinds under one key space: the vanilla constants auto-register their synthetic ids
 * ({@code minecraft:default}, {@code minecraft:default/bold}, ...) so consumers iterate
 * {@link #fontIds()} rather than switching on the enum.
 *
 * @see MinecraftGlyph
 * @see MinecraftFontMetrics
 * @see MinecraftGlyphVector
 */
public sealed interface MinecraftFont permits MinecraftFont.Vanilla, MinecraftFont.Color {

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
     * Returns the glyph for a codepoint, rasterizing or resolving it on first access and caching the
     * result. Never returns {@code null}: an advance-only glyph (a pack space provider, or a colour
     * row whose strike failed to decode) is modelled as a {@link MinecraftGlyphVector.Kind#SPACE}
     * sentinel. The returned glyph is the canonical cached instance ({@link MinecraftGlyph#penX() penX}
     * {@code == 0}); layout stamps each occurrence with its pen.
     *
     * @param codepoint the Unicode codepoint
     * @return the glyph
     */
    @NotNull MinecraftGlyph glyph(int codepoint);

    /**
     * Returns the font's metrics - advances and line geometry, all derived from
     * {@link MinecraftGlyph#signedAdvance()} so measurement and layout agree exactly.
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
     * font kinds; the vector positions its pens from {@link MinecraftGlyph#signedAdvance()} with no
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

    /**
     * Walks a run of text, emitting each resolved glyph at its cumulative pen and returning the total
     * signed advance. This is the single accumulation loop behind all three text surfaces:
     * {@link MinecraftGraphics#drawString drawString} drives it directly (no vector allocation on the
     * hot path), {@link MinecraftFontMetrics#stringAdvanceX metrics} sums it with a
     * {@link GlyphSink#NOOP no-op} sink, and {@link MinecraftGlyphVector#of layout} materializes a
     * vector from it. Because measure, draw, and layout share this one walk, they agree on width and
     * position by construction rather than by keeping parallel loops in step.
     * <p>
     * Pens accumulate from {@link MinecraftGlyph#signedAdvance()} as a {@code double}, so fractional
     * and negative advances (space providers) are exact; rounding happens only at blit time. The sink
     * receives the canonical cached glyph plus its pen separately - it is not handed a
     * {@link MinecraftGlyph#at stamped} copy - so the draw hot path allocates nothing per glyph.
     *
     * @param text the text to walk
     * @param sink receives each glyph and its cumulative pen X in output pixels
     * @return the total signed advance in output pixels
     */
    default double walk(@NotNull String text, @NotNull GlyphSink sink) {
        double pen = 0.0;
        int i = 0;
        while (i < text.length()) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);

            MinecraftGlyph glyph = glyph(codepoint);
            sink.accept(glyph, pen);
            pen += glyph.signedAdvance();
        }
        return pen;
    }

    // --- awt interop surface (backs MinecraftGlyphVector's GlyphVector contract) ---

    /**
     * The backing AWT {@link java.awt.Font} this font's glyph codes resolve against, and the font a
     * {@link MinecraftGlyphVector#getFont()} returns. {@link Vanilla} hands back the {@code .otf} it
     * loaded; {@link Color} lazily {@link java.awt.Font#createFont createFont}s its merged {@code .ttf}
     * bytes once and shares that instance across every font id built from the same bytes.
     *
     * @return the backing AWT font
     */
    @NotNull java.awt.Font awtFont();

    /**
     * The {@link FontRenderContext} the backing font and its glyph codes resolve under. {@link Vanilla}
     * reuses its rasterization context; {@link Color} uses an identity-transform, antialiased default
     * (it never rasterizes vector outlines - its art is {@code sbix} strikes).
     *
     * @return the render context
     */
    @NotNull FontRenderContext fontRenderContext();

    /**
     * Resolves a codepoint to a real glyph id in {@link #awtFont() the backing font}'s {@code cmap},
     * caching per font. This backs the {@link MinecraftGlyphVector#getGlyphCode glyph-code} surface for
     * glyphs that carry no sidecar gid - vanilla atlas glyphs and space providers; colour raster glyphs
     * report their sidecar gid directly. A codepoint the backing font lacks resolves to {@code 0}
     * ({@code .notdef}), which is the honest answer.
     *
     * @param codepoint the Unicode codepoint
     * @return the glyph id in the backing font
     */
    int glyphCode(int codepoint);

    /**
     * Resolves a codepoint to its glyph id through a font's {@code cmap} by laying out the single
     * character and reading the resulting glyph code. Shared by both kinds' {@link #glyphCode} caches.
     *
     * @param font the backing AWT font
     * @param frc the render context to resolve under
     * @param codepoint the Unicode codepoint
     * @return the glyph id, or {@code 0} when the font has no glyph for the codepoint
     */
    static int resolveGlyphCode(@NotNull java.awt.Font font, @NotNull FontRenderContext frc, int codepoint) {
        return font.createGlyphVector(frc, new String(Character.toChars(codepoint))).getGlyphCode(0);
    }

    /**
     * Receives each glyph a {@link #walk} emits, together with its cumulative pen position. The glyph
     * is the canonical cached instance (pen {@code 0}); the pen is passed separately so the walk need
     * not allocate a {@link MinecraftGlyph#at stamped} copy on the draw hot path.
     */
    @FunctionalInterface
    interface GlyphSink {

        /**
         * A sink that discards every glyph - used by {@link MinecraftFontMetrics} to drive the walk
         * purely for its returned total advance.
         */
        @NotNull GlyphSink NOOP = (glyph, penX) -> {};

        /**
         * Accepts one walked glyph.
         *
         * @param glyph the canonical cached glyph (pen {@code 0})
         * @param penX the cumulative pen position of this occurrence, in output pixels (may be negative)
         */
        void accept(@NotNull MinecraftGlyph glyph, double penX);

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
     * Returns the registered font for a font id, resolving and registering a pack {@link Color} font
     * via {@link Color#load(FontId)} on first use.
     *
     * @param fontId the font id
     * @return the font
     */
    static @NotNull MinecraftFont getOrLoad(@NotNull FontId fontId) {
        Registry.ensureVanilla();
        return Registry.MAP.computeIfAbsent(fontId, Color::load);
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

        @Getter(AccessLevel.NONE)
        private final @NotNull FontId fontId;

        @Getter(AccessLevel.NONE)
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

        @Getter(AccessLevel.NONE)
        private final @NotNull ConcurrentMap<Integer, MinecraftGlyph> glyphCache;

        /**
         * Per-font {@code codepoint -> cmap gid} cache backing the {@link MinecraftGlyphVector}
         * glyph-code surface. Distinct from {@link #glyphCache}: that holds rasterized bitmaps, this
         * holds only integer glyph ids resolved through the AWT font's {@code cmap}.
         */
        @Getter(AccessLevel.NONE)
        private final @NotNull ConcurrentMap<Integer, Integer> gidCache;

        Vanilla(@NotNull String fileName, @NotNull Style style, @NotNull String fontId) {
            Resolved resolved = resolveFont(fileName);
            this.actual = resolved.font();
            this.path = resolved.path();
            this.style = style;
            this.fontId = FontId.parse(fontId);
            this.glyphCache = Concurrent.newMap();
            this.gidCache = Concurrent.newMap();

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

            this.metrics = new MinecraftFontMetrics(this, this.actual,
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
        public @NotNull MinecraftGlyph glyph(int codepoint) {
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

        @Override
        public @NotNull java.awt.Font awtFont() {
            return this.actual;
        }

        @Override
        public @NotNull FontRenderContext fontRenderContext() {
            return this.awtFrc;
        }

        @Override
        public int glyphCode(int codepoint) {
            return this.gidCache.computeIfAbsent(codepoint, cp -> resolveGlyphCode(this.actual, this.awtFrc, cp));
        }

        /**
         * Rasterizes a single glyph as white-on-transparent into a {@link PixelBuffer}. Queries the
         * AWT {@link FontMetrics} and {@link FontRenderContext} captured at init rather than spinning
         * up a throwaway scratch {@link Graphics2D} for every codepoint - only the per-glyph
         * {@link BufferedImage} (sized to the visual bounds) is newly allocated.
         */
        private @NotNull MinecraftGlyph rasterizeGlyph(int codepoint) {
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
                gg.setColor(java.awt.Color.WHITE);
                gg.drawString(new String(chars), -bearingX, -bearingY);
            } finally {
                gg.dispose();
            }

            return new MinecraftGlyph(codepoint, PixelBuffer.wrap(glyphImage), advanceWidth, bearingX, bearingY);
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
     * A single pack colour font, keyed by {@link FontId}, that sits beside the fixed {@link Vanilla}
     * enum as the second {@link MinecraftFont} implementation.
     * <p>
     * It binds the three things a colour font needs at draw time: a {@code sbix} strike store over
     * its {@code .ttf} (a {@link SbixReader} plus a decode-once {@code (gid, ppem) -> PixelBuffer}
     * map), the parsed {@link ColorGlyphSidecar} view (advances, origins, strike selection), and a
     * vanilla {@link MinecraftFont} mono fallback for any codepoint the pack does not define. Every
     * codepoint resolves through {@link #glyph(int)} into a single {@link MinecraftGlyph}, so
     * downstream layout ({@link #layout(String)}), measurement ({@link #metrics()}), and painting
     * share one advance source and one glyph surface with the vanilla path.
     * <p>
     * The strike store is shared per FILE, not per font id (see {@link SharedStrikes}): with the
     * single merged per-pack colour font every font id of a pack points at the same {@code .ttf}
     * bytes, so one {@link SbixReader} and one strike cache back all of them rather than one copy per
     * id. The per-codepoint {@link #glyphCache} stays private to each instance because its rows differ
     * per font id.
     * <p>
     * Strikes are cached as {@link PixelBuffer} rather than {@link BufferedImage}: each strike is
     * decoded once via {@link ImageIO#read}, wrapped into an ARGB {@code int[]}, and the whole
     * {@code BufferedImage}/{@code Raster}/{@code ColorModel}/{@code SampleModel} object graph is then
     * discarded, shedding its fixed per-strike overhead for the process lifetime of the font.
     */
    final class Color implements MinecraftFont {

        /**
         * Classpath / cache subdirectory the colour {@code .ttf} files and shared sidecar live under.
         */
        public static final @NotNull String RESOURCE_DIR = "colorfont";

        /**
         * Shared sidecar filename.
         */
        public static final @NotNull String SIDECAR_NAME = "colour-glyphs.json";

        /**
         * The render context colour-font glyph codes resolve under: an identity transform with
         * antialiasing and fractional metrics on. A colour font never rasterizes vector outlines (its
         * art is {@code sbix} strikes), so the context only needs to be stable and shared - the merged
         * {@code cmap} lookups it feeds are transform-independent.
         */
        private static final @NotNull FontRenderContext COLOR_FRC = new FontRenderContext(new AffineTransform(), true, true);

        private final @NotNull FontId fontId;
        private final @NotNull SharedStrikes strikes;
        private final @NotNull ColorGlyphSidecar sidecar;
        private final @NotNull MinecraftFont monoFallback;
        private final @NotNull MinecraftFontMetrics metrics;

        /**
         * Per-font {@code codepoint -> cmap gid} cache backing the {@link MinecraftGlyphVector}
         * glyph-code surface for this font's non-raster glyphs (mono fallback and space providers);
         * raster glyphs report their sidecar gid directly and never reach this cache.
         */
        private final @NotNull ConcurrentMap<Integer, Integer> gidCache;

        /**
         * Per-codepoint glyph cache. Uniform {@link #glyph(int)} surface with the vanilla atlas over a
         * lower {@code sbix} strike tier. Stays private per instance - unlike the {@link #strikes}
         * store, the resolved rows differ per font id.
         */
        private final @NotNull ConcurrentMap<Integer, MinecraftGlyph> glyphCache;

        private Color(
            @NotNull FontId fontId,
            @NotNull SharedStrikes strikes,
            @NotNull ColorGlyphSidecar sidecar,
            @NotNull MinecraftFont monoFallback
        ) {
            this.fontId = fontId;
            this.strikes = strikes;
            this.sidecar = sidecar;
            this.monoFallback = monoFallback;
            this.glyphCache = Concurrent.newMap();
            this.gidCache = Concurrent.newMap();

            MinecraftFontMetrics mono = monoFallback.metrics();
            this.metrics = new MinecraftFontMetrics(this, mono.getFont(), mono.getAscent(), mono.getDescent(), mono.getHeight());
        }

        /**
         * Builds a colour font from raw font bytes, the parsed sidecar, and a mono fallback. The
         * strike store is internal, so callers pass only the {@code .ttf} bytes; the
         * {@link SbixReader} and its strike cache are shared with any other font built from identical
         * bytes (see {@link SharedStrikes}).
         *
         * @param fontId the font id
         * @param ttf the raw colour {@code .ttf} bytes
         * @param sidecar the parsed sidecar (may span multiple font ids)
         * @param monoFallback the vanilla font used for codepoints the pack does not define
         * @return the colour font
         */
        public static @NotNull Color of(
            @NotNull FontId fontId,
            byte @NotNull [] ttf,
            @NotNull ColorGlyphSidecar sidecar,
            @NotNull MinecraftFont monoFallback
        ) {
            return new Color(fontId, SharedStrikes.forBytes(ttf), sidecar, monoFallback);
        }

        /**
         * Resolves a colour font for a font id from the classpath, then the user-home cache, using
         * {@link Vanilla#REGULAR} as the mono fallback.
         *
         * @param fontId the font id to load
         * @return the resolved colour font
         * @throws IllegalStateException when the sidecar, the font id, or its {@code .ttf} cannot be found
         */
        public static @NotNull Color load(@NotNull FontId fontId) {
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
        public static @NotNull Color load(@NotNull FontId fontId, @NotNull MinecraftFont monoFallback) {
            ColorGlyphSidecar sidecar = loadSidecar();
            String file = sidecar.fileFor(fontId).orElseThrow(() -> new IllegalStateException(
                "Colour sidecar '" + SIDECAR_NAME + "' does not list font id '" + fontId + "'."));
            return of(fontId, loadTtfBytes(file), sidecar, monoFallback);
        }

        private static @NotNull ColorGlyphSidecar loadSidecar() {
            String classpath = RESOURCE_DIR + "/" + SIDECAR_NAME;
            try (InputStream in = Color.class.getClassLoader().getResourceAsStream(classpath)) {
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
            try (InputStream in = Color.class.getClassLoader().getResourceAsStream(classpath)) {
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
        public @NotNull MinecraftGlyph glyph(int codepoint) {
            return this.glyphCache.computeIfAbsent(codepoint, this::resolveGlyph);
        }

        private @NotNull MinecraftGlyph resolveGlyph(int codepoint) {
            Optional<GlyphRow> rowOptional = this.sidecar.lookup(this.fontId, codepoint);
            if (rowOptional.isEmpty()) return this.monoFallback.glyph(codepoint);   // MONO fallback

            GlyphRow row = rowOptional.get();
            int unitsPerEm = this.sidecar.unitsPerEm();
            float advance = (float) FontUnits.toOutputPixels(row.advance(), unitsPerEm);
            if (row.isSpace()) return MinecraftGlyph.space(codepoint, advance);

            int ppem = resolvePpem(row);
            int gid = row.gid();
            int originX = (int) Math.round(FontUnits.toOutputPixels(row.originX(), unitsPerEm));
            int originY = (int) Math.round(FontUnits.toOutputPixels(row.originY(), unitsPerEm));

            // A raster row whose strike fails to decode degrades to an advance-only sentinel: the pen
            // still moves, nothing is painted, and glyph(cp) never returns null.
            return strike(gid, ppem)
                .map(bitmap -> MinecraftGlyph.color(codepoint, bitmap, advance, originX, originY, gid, ppem))
                .orElseGet(() -> MinecraftGlyph.space(codepoint, advance));
        }

        private int resolvePpem(@NotNull GlyphRow row) {
            Integer declared = row.strikePpem();
            if (declared != null) return declared;
            int[] available = this.strikes.reader().strikePpems();
            return available.length > 0 ? available[0] : 0;
        }

        /**
         * Returns the decoded {@code sbix} strike for a glyph as a {@link PixelBuffer}, decoding at
         * most once per {@code (gid, ppem)} and caching the result on the shared per-file store. Empty
         * when the glyph is absent in that strike or its graphic type is not a PNG.
         *
         * @param gid the glyph id
         * @param ppem the strike ppem
         * @return the decoded strike, or empty
         */
        @NotNull Optional<PixelBuffer> strike(int gid, int ppem) {
            return this.strikes.strike(gid, ppem);
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

        @Override
        public @NotNull java.awt.Font awtFont() {
            return this.strikes.awtFont();
        }

        @Override
        public @NotNull FontRenderContext fontRenderContext() {
            return COLOR_FRC;
        }

        @Override
        public int glyphCode(int codepoint) {
            return this.gidCache.computeIfAbsent(codepoint, cp -> resolveGlyphCode(awtFont(), COLOR_FRC, cp));
        }

        /**
         * @return the underlying {@code sbix} reader (shared per file)
         */
        @NotNull SbixReader reader() {
            return this.strikes.reader();
        }

        /**
         * @return the shared per-file strike store backing this font
         */
        @NotNull SharedStrikes strikes() {
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
         * The {@code sbix} strike store shared across every {@link Color} font whose {@code .ttf}
         * bytes are byte-for-byte identical - which, with the single merged per-pack colour font, is
         * every font id of a pack. It owns the one {@link SbixReader} (holding the single ~2.8MB copy
         * of the font bytes) and the decode-once {@code (gid << 16 | ppem) -> PixelBuffer} cache, so a
         * reference pack's ~191 font ids share one reader and one set of decoded strikes instead of
         * ~191 copies.
         * <p>
         * <strong>Identity key.</strong> Entries are keyed by a SHA-256 content hash of the
         * {@code .ttf} bytes rather than a file path. {@link Color#of} receives raw bytes with no path
         * in hand (only {@link Color#load} resolves a filename), so a content hash is the only
         * identity available to both entry points, and it correctly folds together identical bytes
         * whether they arrive from the classpath, the filesystem cache, or an in-memory caller.
         * <p>
         * <strong>Lifetime.</strong> Process-lifetime: {@link #BY_CONTENT} is never evicted. This
         * mirrors the font {@link Registry}, which likewise holds pack fonts for the process lifetime,
         * and it is safe without a reference count because content-hash keying makes reloading
         * identical bytes an idempotent cache hit and a pack's glyph/strike set is finite. The strike
         * cache itself is unbounded for the same reason: the decoded strike set per file is bounded by
         * the font's glyphs.
         */
        static final class SharedStrikes {

            private static final @NotNull ConcurrentMap<String, SharedStrikes> BY_CONTENT = Concurrent.newMap();

            private final @NotNull SbixReader reader;
            private final byte @NotNull [] fontBytes;
            private final @NotNull ConcurrentMap<Long, Optional<PixelBuffer>> strikeCache;
            private volatile java.awt.Font awtFont;

            private SharedStrikes(@NotNull SbixReader reader, byte @NotNull [] fontBytes) {
                this.reader = reader;
                this.fontBytes = fontBytes;
                this.strikeCache = Concurrent.newMap();
            }

            /**
             * Returns the shared store for the given font bytes, constructing (and parsing) a
             * {@link SbixReader} exactly once per distinct byte content and reusing it thereafter. The
             * bytes are retained (the same array the reader already holds - no copy) so the backing AWT
             * font can be built lazily from them.
             *
             * @param ttf the raw colour {@code .ttf} bytes
             * @return the shared store keyed by the bytes' content hash
             */
            static @NotNull SharedStrikes forBytes(byte @NotNull [] ttf) {
                return BY_CONTENT.computeIfAbsent(contentKey(ttf), ignored -> new SharedStrikes(new SbixReader(ttf), ttf));
            }

            @NotNull SbixReader reader() {
                return this.reader;
            }

            /**
             * Lazily builds - then shares across every font id backed by these same bytes - the AWT
             * {@link java.awt.Font} the merged colour {@code .ttf} maps to. It answers the
             * {@link MinecraftGlyphVector} glyph-code and {@link MinecraftGlyphVector#getFont() font}
             * surface honestly: its {@code cmap} yields the real gids and it is the font the vector
             * reports. It is never rasterized (colour art lives in {@code sbix}, which AWT paints
             * blank), so it is deliberately not registered with the {@link GraphicsEnvironment}.
             *
             * @return the backing AWT font, created once per distinct byte content
             */
            @NotNull java.awt.Font awtFont() {
                java.awt.Font font = this.awtFont;
                if (font == null) {
                    synchronized (this) {
                        font = this.awtFont;
                        if (font == null) {
                            font = createAwtFont(this.fontBytes);
                            this.awtFont = font;
                        }
                    }
                }
                return font;
            }

            private static @NotNull java.awt.Font createAwtFont(byte @NotNull [] ttf) {
                try (InputStream in = new ByteArrayInputStream(ttf)) {
                    return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(FONT_POINT_SIZE);
                } catch (IOException | FontFormatException ex) {
                    throw new IllegalStateException("Unable to create the backing AWT font from the colour '.ttf' bytes", ex);
                }
            }

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

            private static @NotNull String contentKey(byte @NotNull [] ttf) {
                try {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(ttf);
                    StringBuilder hex = new StringBuilder(digest.length * 2);
                    for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
                    return hex.toString();
                } catch (NoSuchAlgorithmException ex) {
                    throw new IllegalStateException("SHA-256 is required to key the shared colour strike store", ex);
                }
            }

            private static @NotNull BufferedImage toArgb(@NotNull BufferedImage source) {
                if (source.getType() == BufferedImage.TYPE_INT_ARGB) return source;
                BufferedImage argb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
                argb.getGraphics().drawImage(source, 0, 0, null);
                return argb;
            }

        }

    }

    /**
     * The style category a {@link Vanilla} font entry belongs to.
     */
    @Getter
    @RequiredArgsConstructor
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

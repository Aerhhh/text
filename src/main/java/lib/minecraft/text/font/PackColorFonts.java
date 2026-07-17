package lib.minecraft.text.font;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A process-wide registry of pack {@link MinecraftColorFont}s keyed by {@link FontId}.
 * <p>
 * This is the open, non-enum counterpart to the fixed {@link MinecraftFont} enum: pack font ids are
 * user-supplied and unbounded, so they are registered here rather than baked into a value list. The
 * registry only stores and hands back fonts; it holds no rendering state and is safe for concurrent
 * use.
 */
public final class PackColorFonts {

    private static final @NotNull ConcurrentMap<FontId, MinecraftColorFont> REGISTRY = new ConcurrentHashMap<>();

    private PackColorFonts() {}

    /**
     * Registers a colour font, replacing any previous registration for its font id.
     *
     * @param font the colour font to register
     * @return the registered font
     */
    public static @NotNull MinecraftColorFont register(@NotNull MinecraftColorFont font) {
        REGISTRY.put(font.fontId(), font);
        return font;
    }

    /**
     * Returns the registered colour font for a font id, if any.
     *
     * @param fontId the font id
     * @return the registered font, or empty
     */
    public static @NotNull Optional<MinecraftColorFont> get(@NotNull FontId fontId) {
        return Optional.ofNullable(REGISTRY.get(fontId));
    }

    /**
     * Returns the registered colour font for a font id, resolving and registering it via
     * {@link MinecraftColorFont#load(FontId)} on first use.
     *
     * @param fontId the font id
     * @return the colour font
     */
    public static @NotNull MinecraftColorFont getOrLoad(@NotNull FontId fontId) {
        return REGISTRY.computeIfAbsent(fontId, MinecraftColorFont::load);
    }

    /**
     * Removes a font id's registration.
     *
     * @param fontId the font id to unregister
     */
    public static void unregister(@NotNull FontId fontId) {
        REGISTRY.remove(fontId);
    }

    /**
     * Clears every registration. Primarily for test isolation.
     */
    public static void clear() {
        REGISTRY.clear();
    }

}

package lib.minecraft.text.font;

import org.jetbrains.annotations.NotNull;

/**
 * A namespaced pack font identifier of the form {@code namespace:path} (e.g.
 * {@code minecraft:default}).
 * <p>
 * Pack colour fonts reuse the same Private-Use-Area codepoints across different font files with
 * different artwork, so a bare codepoint is ambiguous. The {@link FontId} is the disambiguation
 * key: the colour sidecar rows and the per-font {@code .ttf} files are both keyed by it. Unlike
 * the vanilla {@link MinecraftFont} enum - a fixed six-value style family - font ids are open and
 * pack-supplied, so they live in a registry rather than an enum.
 *
 * @param namespace the resource namespace (the part before the first {@code :})
 * @param path the resource path (the part after the first {@code :})
 */
public record FontId(@NotNull String namespace, @NotNull String path) {

    /**
     * Parses a {@code namespace:path} string into a {@link FontId}. When no {@code :} is present
     * the whole string is treated as the path under the {@code minecraft} namespace, mirroring
     * vanilla resource-location resolution.
     *
     * @param id the identifier string
     * @return the parsed font id
     */
    public static @NotNull FontId parse(@NotNull String id) {
        int colon = id.indexOf(':');
        if (colon < 0) return new FontId("minecraft", id);
        return new FontId(id.substring(0, colon), id.substring(colon + 1));
    }

    /**
     * Returns the canonical {@code namespace:path} string form.
     *
     * @return the {@code namespace:path} representation
     */
    @Override
    public @NotNull String toString() {
        return this.namespace + ":" + this.path;
    }

}

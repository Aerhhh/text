package lib.minecraft.text.font;

import dev.simplified.image.pixel.PixelBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Pins the per-FILE sharing of the {@code sbix} strike store: with the single merged colour font,
 * every font id of a pack loads from the same {@code .ttf} bytes, so all of them must share one
 * {@link SbixReader} and one decoded-strike cache rather than holding a private ~2.8MB copy each.
 * <p>
 * The assertions are on object identity, not value equality - a per-instance regression would still
 * decode value-equal strikes, so only {@code sameInstance} distinguishes a shared cache from a
 * duplicated one. {@code demoFont()} and {@code altFont()} both load {@link ColorFontFixtures#MERGED_TTF}.
 */
@DisplayName("MinecraftColorFont shares one sbix strike store across every font id of a file")
class MinecraftColorFontSharedStoreTest {

    @Test
    @DisplayName("two fonts loaded from the same bytes share the same strike store and reader instance")
    void sameFileSharesStoreAndReader() {
        MinecraftColorFont demo = ColorFontFixtures.demoFont();
        MinecraftColorFont alt = ColorFontFixtures.altFont();

        assertThat(demo.strikes(), sameInstance(alt.strikes()));
        assertThat(demo.reader(), sameInstance(alt.reader()));
    }

    @Test
    @DisplayName("a strike decoded through one font is a cache hit through the other")
    void strikeDecodedByOneFontIsCacheHitForTheOther() {
        MinecraftColorFont demo = ColorFontFixtures.demoFont();
        MinecraftColorFont alt = ColorFontFixtures.altFont();

        // Decode once via demo; the shared store must hand alt back the very same PixelBuffer.
        PixelBuffer viaDemo = demo.strike(ColorFontFixtures.GID_ALT_FLAT, 8).orElseThrow();
        PixelBuffer viaAlt = alt.strike(ColorFontFixtures.GID_ALT_FLAT, 8).orElseThrow();
        assertThat(viaAlt, sameInstance(viaDemo));
    }

    @Test
    @DisplayName("distinct file bytes get distinct strike stores")
    void differentFilesDoNotShareAStore() {
        MinecraftColorFont merged = ColorFontFixtures.demoFont();
        MinecraftColorFont edge = MinecraftColorFont.of(ColorFontFixtures.DEMO,
            ColorFontFixtures.bytes("SynthColour-edge.ttf"), ColorFontFixtures.sidecar(), MinecraftFont.Vanilla.REGULAR);

        assertThat(merged.strikes(), not(sameInstance(edge.strikes())));
    }

}

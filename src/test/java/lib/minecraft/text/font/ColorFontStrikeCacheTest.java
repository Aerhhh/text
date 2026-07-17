package lib.minecraft.text.font;

import dev.simplified.image.pixel.PixelBuffer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

@DisplayName("MinecraftFont.Color decodes each sbix strike once, as a PixelBuffer, and is thread-safe")
class ColorFontStrikeCacheTest {

    private static @NotNull MinecraftFont.Color edgeFont() {
        return MinecraftFont.Color.of(ColorFontFixtures.DEMO,
            ColorFontFixtures.bytes("SynthColour-edge.ttf"), ColorFontFixtures.sidecar(), MinecraftFont.Vanilla.REGULAR);
    }

    @Test
    @DisplayName("decodes at most once per (gid, ppem) - repeated lookups return the same PixelBuffer")
    void singleDecodePerKey() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        PixelBuffer first = font.strike(ColorFontFixtures.GID_FLAT, 8).orElseThrow();
        PixelBuffer second = font.strike(ColorFontFixtures.GID_FLAT, 8).orElseThrow();
        assertThat(second, sameInstance(first));
    }

    @Test
    @DisplayName("the decoded PixelBuffer pixels match the source cell colours")
    void decodedPixelsMatchSource() {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        PixelBuffer flat = font.strike(ColorFontFixtures.GID_FLAT, 8).orElseThrow();
        assertThat(flat.width(), is(8));
        assertThat(flat.height(), is(8));
        assertThat(flat.getPixel(0, 0), is(0xFFDC2828));   // top-left red (220,40,40)
        assertThat(flat.getPixel(4, 4), is(0xFF283CDC));   // centre blue (40,60,220)
    }

    @Test
    @DisplayName("an absent strike slot and a non-png record decode to empty")
    void absentAndNonPngDecodeEmpty() {
        MinecraftFont.Color demo = ColorFontFixtures.demoFont();
        assertThat(demo.strike(ColorFontFixtures.GID_FLAT, 16), is(Optional.empty()));   // flat glyph not present in strike 16

        MinecraftFont.Color edge = edgeFont();
        assertThat(edge.strike(3, 8), is(Optional.empty()));    // gid 3 is a 'jpg ' record
    }

    @Test
    @DisplayName("concurrent lookups return one identical cached PixelBuffer")
    void concurrentLookupsShareOneBuffer() throws Exception {
        MinecraftFont.Color font = ColorFontFixtures.demoFont();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<PixelBuffer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++)
                futures.add(pool.submit(() -> font.strike(ColorFontFixtures.GID_TALL, 8).orElseThrow()));

            PixelBuffer reference = futures.get(0).get();
            for (Future<PixelBuffer> future : futures)
                assertThat(future.get(), sameInstance(reference));
        } finally {
            pool.shutdownNow();
        }
    }

}

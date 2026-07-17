package lib.minecraft.text.font;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

@DisplayName("SbixStrikeCache decodes each strike once and is thread-safe")
class SbixStrikeCacheTest {

    @Test
    @DisplayName("decodes at most once per (gid, ppem) - repeated lookups return the same instance")
    void singleDecodePerKey() {
        SbixStrikeCache cache = SbixStrikeCache.of(ColorFontFixtures.bytes("SynthColour-demo.ttf"));
        BufferedImage first = cache.strike(1, 8).orElseThrow();
        BufferedImage second = cache.strike(1, 8).orElseThrow();
        assertThat(second, sameInstance(first));
    }

    @Test
    @DisplayName("decoded strike pixels match the source cell colours")
    void decodedPixelsMatchSource() {
        SbixStrikeCache cache = SbixStrikeCache.of(ColorFontFixtures.bytes("SynthColour-demo.ttf"));
        BufferedImage flat = cache.strike(1, 8).orElseThrow();
        assertThat(flat.getWidth(), is(8));
        assertThat(flat.getHeight(), is(8));
        assertThat(flat.getRGB(0, 0), is(0xFFDC2828));   // top-left red (220,40,40)
        assertThat(flat.getRGB(4, 4), is(0xFF283CDC));   // centre blue (40,60,220)
    }

    @Test
    @DisplayName("an absent strike slot and a non-png record decode to empty")
    void absentAndNonPngDecodeEmpty() {
        SbixStrikeCache demo = SbixStrikeCache.of(ColorFontFixtures.bytes("SynthColour-demo.ttf"));
        assertThat(demo.strike(1, 16), is(Optional.empty()));   // gid 1 not present in strike 16

        SbixStrikeCache edge = SbixStrikeCache.of(ColorFontFixtures.bytes("SynthColour-edge.ttf"));
        assertThat(edge.strike(3, 8), is(Optional.empty()));    // gid 3 is a 'jpg ' record
    }

    @Test
    @DisplayName("concurrent lookups return one identical cached image")
    void concurrentLookupsShareOneImage() throws Exception {
        SbixStrikeCache cache = SbixStrikeCache.of(ColorFontFixtures.bytes("SynthColour-demo.ttf"));
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<BufferedImage>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++)
                futures.add(pool.submit(() -> cache.strike(5, 8).orElseThrow()));

            BufferedImage reference = futures.get(0).get();
            for (Future<BufferedImage> future : futures)
                assertThat(future.get(), sameInstance(reference));
        } finally {
            pool.shutdownNow();
        }
    }

}

package lib.minecraft.renderer.text;

import com.google.gson.JsonObject;
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.GradientSpec;
import lib.minecraft.text.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link GradientSpec} builder validation (per-mode stop rules, band/hue/cycle bounds) and
 * the custom {@code "gradient"} JSON codec round-trips every mode plus the {@code shear} / {@code
 * scroll} extensions, while malformed input fails loudly.
 */
class GradientSpecTest {

    @Nested
    @DisplayName("builder validation")
    class Validation {

        @Test
        @DisplayName("START_END forces positions to 0 and 1, ignoring author positions")
        void startEndForcesPositions() {
            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.START_END)
                .addStop(0xFF0000)
                .addStop(0x0000FF)
                .build();

            assertThat(spec.stops().get(0).position(), is(0f));
            assertThat(spec.stops().get(1).position(), is(1f));
        }

        @Test
        @DisplayName("START_END rejects a stop count other than 2")
        void startEndRejectsWrongCount() {
            assertThrows(IllegalArgumentException.class, () -> GradientSpec.builder(GradientSpec.Mode.START_END)
                .addStop(0xFF0000)
                .build());
        }

        @Test
        @DisplayName("RANGE auto-spreads N stops at i/(N-1)")
        void rangeAutoSpreads() {
            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.RANGE)
                .addStop(0xFF0000)
                .addStop(0x00FF00)
                .addStop(0x0000FF)
                .build();

            assertThat(spec.stops().get(0).position(), is(0f));
            assertThat(spec.stops().get(1).position(), is(0.5f));
            assertThat(spec.stops().get(2).position(), is(1f));
        }

        @Test
        @DisplayName("RANGE rejects explicit positions and fewer than 2 stops")
        void rangeRejectsExplicitAndTooFew() {
            assertThrows(IllegalArgumentException.class, () -> GradientSpec.builder(GradientSpec.Mode.RANGE)
                .addStop(0xFF0000, 0.3f)
                .addStop(0x0000FF, 0.7f)
                .build());
            assertThrows(IllegalArgumentException.class, () -> GradientSpec.builder(GradientSpec.Mode.RANGE)
                .addStop(0xFF0000)
                .build());
        }

        @Test
        @DisplayName("SPECIFIC sorts ascending and keeps the last of duplicate positions")
        void specificSortsAndDedups() {
            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.SPECIFIC)
                .addStop(0x111111, 0.75f)
                .addStop(0x222222, 0.25f)
                .addStop(0x333333, 0.25f) // duplicate position -> last wins
                .build();

            assertThat(spec.stops().size(), is(2));
            assertThat(spec.stops().get(0).position(), is(0.25f));
            assertThat(spec.stops().get(0).rgb(), is(0x333333));
            assertThat(spec.stops().get(1).position(), is(0.75f));
        }

        @Test
        @DisplayName("SPECIFIC rejects out-of-range positions and requires >= 1 stop")
        void specificRejectsOutOfRange() {
            assertThrows(IllegalArgumentException.class, () -> GradientSpec.builder(GradientSpec.Mode.SPECIFIC)
                .addStop(0x111111, 1.5f)
                .build());
            assertThrows(IllegalArgumentException.class, () -> GradientSpec.builder(GradientSpec.Mode.SPECIFIC)
                .build());
        }

        @Test
        @DisplayName("RAINBOW rejects stops and non-positive hueCycles")
        void rainbowRules() {
            GradientSpec ok = GradientSpec.builder(GradientSpec.Mode.RAINBOW).hueCycles(2f).build();
            assertThat(ok.stops().isEmpty(), is(true));

            assertThrows(IllegalArgumentException.class, () -> GradientSpec.builder(GradientSpec.Mode.RAINBOW)
                .addStop(0xFF0000)
                .build());
            assertThrows(IllegalArgumentException.class, () -> GradientSpec.builder(GradientSpec.Mode.RAINBOW)
                .hueCycles(0f)
                .build());
        }

        @Test
        @DisplayName("negative bandPx and cycleTicks < 1 fail loudly")
        void boundsFailLoudly() {
            assertThrows(IllegalArgumentException.class, () -> GradientSpec.builder(GradientSpec.Mode.START_END)
                .addStop(0x000000)
                .addStop(0xFFFFFF)
                .bandPx(-1)
                .build());
            assertThrows(IllegalArgumentException.class, () -> new GradientSpec.Scroll(0, GradientSpec.Scroll.Direction.LEFT));
        }
    }

    @Nested
    @DisplayName("JSON codec round-trip")
    class Codec {

        @Test
        @DisplayName("START_END round-trips through toJson/fromJson")
        void startEndRoundTrip() {
            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.START_END)
                .addStop(0xFF00FF)
                .addStop(0x00FFFF)
                .bandPx(1)
                .build();
            assertThat(GradientSpec.fromJson(spec.toJson()), is(spec));
        }

        @Test
        @DisplayName("RANGE round-trips with a scroll block")
        void rangeRoundTrip() {
            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.RANGE)
                .addStop(0xFF0000)
                .addStop(0xFFAA00)
                .addStop(0xFFFF00)
                .bandPx(1)
                .scroll(new GradientSpec.Scroll(40, GradientSpec.Scroll.Direction.LEFT))
                .build();
            assertThat(GradientSpec.fromJson(spec.toJson()), is(spec));
        }

        @Test
        @DisplayName("SPECIFIC round-trips exact positions and a numeric shear")
        void specificRoundTrip() {
            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.SPECIFIC)
                .addStop(0x101010, 0.0f)
                .addStop(0x808080, 0.5f)
                .addStop(0xF0F0F0, 1.0f)
                .bandPx(8)
                .shear(0.5f)
                .build();
            assertThat(GradientSpec.fromJson(spec.toJson()), is(spec));
        }

        @Test
        @DisplayName("RAINBOW round-trips hue_cycles and the auto-shear sentinel")
        void rainbowRoundTrip() {
            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.RAINBOW)
                .hueCycles(1.5f)
                .bandPx(1)
                .shear(GradientSpec.AUTO_SHEAR) // serializes as "auto", NaN survives the trip
                .build();
            GradientSpec back = GradientSpec.fromJson(spec.toJson());
            assertThat(back, is(spec));
            assertThat(Float.isNaN(back.shear()), is(true));
        }

        @Test
        @DisplayName("auto shear serializes to the string \"auto\"")
        void autoShearSerializesAsString() {
            JsonObject json = GradientSpec.builder(GradientSpec.Mode.RAINBOW).hueCycles(1f).build().toJson();
            assertThat(json.get("shear").getAsString(), is("auto"));
        }

        @Test
        @DisplayName("non-RAINBOW hueCycles is canonicalized to 1 so the round-trip preserves identity")
        void nonRainbowHueCyclesCanonicalized() {
            GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.START_END)
                .addStop(0xFF0000).addStop(0x0000FF).hueCycles(3f).build();
            assertThat(spec.hueCycles(), is(1f)); // canonicalized (ignored for non-RAINBOW)
            assertThat(GradientSpec.fromJson(spec.toJson()), is(spec));
        }

        @Test
        @DisplayName("unknown mode and malformed color fail parse loudly")
        void malformedFailsLoudly() {
            JsonObject unknownMode = new JsonObject();
            unknownMode.addProperty("mode", "spiral");
            assertThrows(IllegalArgumentException.class, () -> GradientSpec.fromJson(unknownMode));

            GradientSpec valid = GradientSpec.builder(GradientSpec.Mode.START_END)
                .addStop(0x000000).addStop(0xFFFFFF).build();
            JsonObject badColor = valid.toJson();
            badColor.getAsJsonArray("colors").set(0, new com.google.gson.JsonPrimitive("not-a-color"));
            assertThrows(IllegalArgumentException.class, () -> GradientSpec.fromJson(badColor));
        }
    }

    @Test
    @DisplayName("ColorSegment carries the gradient through toJson -> TextSegment.fromJson")
    void colorSegmentRoundTrip() {
        GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.RANGE)
            .addStop(0xFF0000).addStop(0x00FF00).addStop(0x0000FF)
            .bandPx(1)
            .build();
        ColorSegment segment = ColorSegment.builder().withText("Legendary").withGradient(spec).build();

        TextSegment restored = TextSegment.fromJson(segment.toJson());
        assertThat(restored, notNullValue());
        assertThat(restored.getGradient(), is(Optional.of(spec)));
    }

    @Test
    @DisplayName("from() copy chain preserves the gradient")
    void copyChainPreservesGradient() {
        GradientSpec spec = GradientSpec.builder(GradientSpec.Mode.START_END)
            .addStop(0x123456).addStop(0x654321).build();
        ColorSegment original = ColorSegment.builder().withText("x").withGradient(spec).build();
        ColorSegment copy = ColorSegment.from(original).build();
        assertThat(copy.getGradient(), is(Optional.of(spec)));
    }
}

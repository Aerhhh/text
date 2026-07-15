package lib.minecraft.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Gradient color spec for a {@link ColorSegment} - modes, smoothness, scroll, and shear.
 * <p>
 * An immutable data model only: it carries no rendering behavior. A renderer reduces every mode
 * to one sampling function {@code sample(t)} over the segment's normalized pixel width, quantized
 * into bands whose width is the {@link #bandPx()} lever ({@link #PER_LETTER} = one color per
 * glyph), optionally slid along the text by {@link #scroll()} and slanted by {@link #shear()}.
 * <p>
 * Instances are validated at construction - an out-of-shape spec (wrong stop count for the mode,
 * negative {@code bandPx}, non-positive {@link #hueCycles()} for {@link Mode#RAINBOW}) fails the
 * canonical constructor loudly rather than deferring a surprise to render time. Build one through
 * {@link #builder(Mode)}; the builder collects raw stops and the constructor normalizes them per
 * mode (positions forced for {@link Mode#START_END}, auto-spread for {@link Mode#RANGE}, sorted
 * and deduplicated for {@link Mode#SPECIFIC}).
 *
 * @param mode the interpolation mode
 * @param stops the color stops - per-mode meaning, empty for {@link Mode#RAINBOW}
 * @param hueCycles {@link Mode#RAINBOW} hue revolutions across the segment; normalized to {@code 1}
 *     for every other mode (ignored there)
 * @param bandPx quantization band width in output px, {@link #PER_LETTER} = per-letter
 * @param shear band slant as dx per +1 py above baseline, {@link #AUTO_SHEAR} = match segment italic
 * @param scroll the scroll animation, or {@code null} for a static gradient
 */
public record GradientSpec(
    @NotNull Mode mode,
    @NotNull List<Stop> stops,
    float hueCycles,
    int bandPx,
    float shear,
    @Nullable Scroll scroll
) {

    /**
     * {@link #bandPx()} sentinel selecting the per-letter fidelity: each glyph samples one color at
     * its advance-span center, rather than banding uniformly across the segment.
     */
    public static final int PER_LETTER = 0;

    /**
     * {@link #shear()} sentinel selecting the automatic slant: {@link MinecraftFont#ITALIC_SHEAR}
     * when the owning segment is italic, otherwise {@code 0} (vertical bands).
     */
    public static final float AUTO_SHEAR = Float.NaN;

    public GradientSpec {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(stops, "stops");
        if (bandPx < 0)
            throw new IllegalArgumentException("bandPx must be >= 0, got " + bandPx);

        stops = normalizeStops(mode, stops, hueCycles);
        // hueCycles is meaningful only for RAINBOW; canonicalize it to the default for every other
        // mode so equal-in-effect specs compare equal and survive the JSON round-trip (toJson emits
        // hue_cycles for RAINBOW only).
        if (mode != Mode.RAINBOW) hueCycles = 1f;
    }

    /**
     * Creates a builder for the given mode.
     *
     * @param mode the interpolation mode
     * @return a new builder
     */
    public static @NotNull Builder builder(@NotNull Mode mode) {
        return new Builder(mode);
    }

    // --- normalization (shared by the builder and any direct construction) ---

    /**
     * Validates the raw stop list against {@code mode}'s rules and returns the normalized,
     * immutable stop list the record stores. Fails loudly on any shape violation.
     */
    private static @NotNull List<Stop> normalizeStops(@NotNull Mode mode, @NotNull List<Stop> raw, float hueCycles) {
        return switch (mode) {
            case START_END -> {
                require(raw.size() == 2, "START_END requires exactly 2 stops, got " + raw.size());
                yield List.of(new Stop(raw.get(0).rgb(), 0f), new Stop(raw.get(1).rgb(), 1f));
            }
            case RANGE -> {
                require(raw.size() >= 2, "RANGE requires >= 2 stops, got " + raw.size());
                for (Stop stop : raw)
                    require(Float.isNaN(stop.position()),
                        "RANGE positions are auto-spread; supply explicit positions via SPECIFIC");
                List<Stop> spread = new ArrayList<>(raw.size());
                for (int i = 0; i < raw.size(); i++)
                    spread.add(new Stop(raw.get(i).rgb(), i / (float) (raw.size() - 1)));
                yield List.copyOf(spread);
            }
            case SPECIFIC -> {
                require(!raw.isEmpty(), "SPECIFIC requires >= 1 stop");
                Map<Float, Integer> byPosition = new LinkedHashMap<>();
                for (Stop stop : raw) {
                    float position = stop.position();
                    require(Float.isFinite(position) && position >= 0f && position <= 1f,
                        "SPECIFIC positions must be finite in [0,1], got " + position);
                    byPosition.put(position, stop.rgb()); // duplicate position: last wins
                }
                yield byPosition.entrySet().stream()
                    .map(entry -> new Stop(entry.getValue(), entry.getKey()))
                    .sorted(Comparator.comparingDouble(Stop::position))
                    .toList();
            }
            case RAINBOW -> {
                require(raw.isEmpty(), "RAINBOW takes no stops, got " + raw.size());
                require(hueCycles > 0f, "RAINBOW hueCycles must be > 0, got " + hueCycles);
                yield List.of();
            }
        };
    }

    private static void require(boolean condition, @NotNull String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    // --- serialization (custom "gradient" text-component extension key) ---

    /**
     * Serializes this spec to its {@code "gradient"} text-component JSON object. Vanilla text
     * parsers ignore the key; {@link #fromJson(JsonObject)} round-trips it.
     *
     * @return the JSON object
     */
    public @NotNull JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("mode", this.mode.name().toLowerCase(Locale.ROOT));

        switch (this.mode) {
            case START_END, RANGE -> {
                JsonArray colors = new JsonArray();
                for (Stop stop : this.stops)
                    colors.add(toHex(stop.rgb()));
                object.add("colors", colors);
            }
            case SPECIFIC -> {
                JsonArray colors = new JsonArray();
                for (Stop stop : this.stops) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("color", toHex(stop.rgb()));
                    entry.addProperty("position", stop.position());
                    colors.add(entry);
                }
                object.add("colors", colors);
            }
            case RAINBOW -> object.addProperty("hue_cycles", this.hueCycles);
        }

        object.addProperty("band_px", this.bandPx);
        if (Float.isNaN(this.shear))
            object.addProperty("shear", "auto");
        else
            object.addProperty("shear", this.shear);

        if (this.scroll != null) {
            JsonObject scrollJson = new JsonObject();
            scrollJson.addProperty("cycle_ticks", this.scroll.cycleTicks());
            scrollJson.addProperty("direction", this.scroll.direction().name().toLowerCase(Locale.ROOT));
            object.add("scroll", scrollJson);
        }

        return object;
    }

    /**
     * Parses a {@code "gradient"} JSON object back into a spec. An unknown mode, missing required
     * field, or malformed color fails loudly.
     *
     * @param object the JSON object
     * @return the parsed spec
     */
    public static @NotNull GradientSpec fromJson(@NotNull JsonObject object) {
        Mode mode = parseMode(field(object, "mode").getAsString());
        Builder builder = builder(mode);

        switch (mode) {
            case START_END, RANGE -> {
                for (JsonElement element : field(object, "colors").getAsJsonArray())
                    builder.addStop(parseHex(element.getAsString()));
            }
            case SPECIFIC -> {
                for (JsonElement element : field(object, "colors").getAsJsonArray()) {
                    JsonObject entry = element.getAsJsonObject();
                    builder.addStop(parseHex(field(entry, "color").getAsString()), field(entry, "position").getAsFloat());
                }
            }
            case RAINBOW -> builder.hueCycles(field(object, "hue_cycles").getAsFloat());
        }

        if (object.has("band_px")) builder.bandPx(object.get("band_px").getAsInt());
        if (object.has("shear")) {
            JsonElement shear = object.get("shear");
            builder.shear(shear.isJsonPrimitive() && shear.getAsJsonPrimitive().isString() && "auto".equals(shear.getAsString())
                ? AUTO_SHEAR
                : shear.getAsFloat());
        }
        if (object.has("scroll")) {
            JsonObject scroll = object.getAsJsonObject("scroll");
            builder.scroll(new Scroll(
                field(scroll, "cycle_ticks").getAsInt(),
                Scroll.Direction.valueOf(field(scroll, "direction").getAsString().toUpperCase(Locale.ROOT))
            ));
        }

        return builder.build();
    }

    private static @NotNull JsonElement field(@NotNull JsonObject object, @NotNull String key) {
        JsonElement element = object.get(key);
        if (element == null)
            throw new IllegalArgumentException("Gradient JSON missing required field '" + key + "'");
        return element;
    }

    private static @NotNull Mode parseMode(@NotNull String raw) {
        try {
            return Mode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown gradient mode '" + raw + "'", ex);
        }
    }

    private static @NotNull String toHex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    private static int parseHex(@NotNull String value) {
        if (!value.startsWith("#") || value.length() != 7)
            throw new IllegalArgumentException("Gradient color must be '#RRGGBB', got '" + value + "'");
        try {
            return Integer.parseInt(value.substring(1), 16);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Gradient color must be '#RRGGBB', got '" + value + "'", ex);
        }
    }

    /**
     * The interpolation modes.
     */
    public enum Mode {

        /** Two stops interpolated end to end (author positions forced to 0 and 1). */
        START_END,
        /** A palette of >= 2 stops swept edge to edge, auto-pinned at {@code i / (N - 1)}. */
        RANGE,
        /** Explicitly-positioned stops in {@code [0,1]}, flat-clamped outside the pinned span. */
        SPECIFIC,
        /** A continuous HSV hue sweep with no stops. */
        RAINBOW

    }

    /**
     * One color stop.
     *
     * @param rgb the 24-bit color
     * @param position the position in {@code [0,1]} along the segment, {@code NaN} = auto-spread (RANGE)
     */
    public record Stop(int rgb, float position) {}

    /**
     * A scroll animation - one full slide of the gradient per {@link #cycleTicks} game ticks.
     *
     * @param cycleTicks ticks per full slide (>= 1)
     * @param direction the slide direction
     */
    public record Scroll(int cycleTicks, @NotNull Direction direction) {

        public Scroll {
            Objects.requireNonNull(direction, "direction");
            if (cycleTicks < 1)
                throw new IllegalArgumentException("cycleTicks must be >= 1, got " + cycleTicks);
        }

        /**
         * The scroll slide direction.
         */
        public enum Direction {

            /** Colors slide toward the start of the text. */
            LEFT,
            /** Colors slide toward the end of the text. */
            RIGHT

        }

    }

    /**
     * Collects a {@link GradientSpec}'s inputs and defers validation to {@link #build()}.
     */
    public static final class Builder {

        private final @NotNull Mode mode;
        private final @NotNull List<Stop> stops = new ArrayList<>();
        private float hueCycles = 1f;
        private int bandPx = PER_LETTER;
        private float shear = AUTO_SHEAR;
        private @Nullable Scroll scroll;

        private Builder(@NotNull Mode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
        }

        /**
         * Adds a positionless stop, for {@link Mode#START_END} / {@link Mode#RANGE}.
         *
         * @param rgb the 24-bit color
         * @return this builder
         */
        public @NotNull Builder addStop(int rgb) {
            this.stops.add(new Stop(rgb, Float.NaN));
            return this;
        }

        /**
         * Adds a positioned stop, for {@link Mode#SPECIFIC}.
         *
         * @param rgb the 24-bit color
         * @param position the position in {@code [0,1]}
         * @return this builder
         */
        public @NotNull Builder addStop(int rgb, float position) {
            this.stops.add(new Stop(rgb, position));
            return this;
        }

        /**
         * Sets the {@link Mode#RAINBOW} hue revolutions across the segment.
         *
         * @param hueCycles the hue revolutions (> 0)
         * @return this builder
         */
        public @NotNull Builder hueCycles(float hueCycles) {
            this.hueCycles = hueCycles;
            return this;
        }

        /**
         * Sets the quantization band width in output px, {@link #PER_LETTER} for per-letter.
         *
         * @param bandPx the band width (>= 0)
         * @return this builder
         */
        public @NotNull Builder bandPx(int bandPx) {
            this.bandPx = bandPx;
            return this;
        }

        /**
         * Sets the band slant, {@link #AUTO_SHEAR} to match the segment italic.
         *
         * @param shear the slant as dx per +1 py above baseline
         * @return this builder
         */
        public @NotNull Builder shear(float shear) {
            this.shear = shear;
            return this;
        }

        /**
         * Sets the scroll animation, {@code null} for a static gradient.
         *
         * @param scroll the scroll animation
         * @return this builder
         */
        public @NotNull Builder scroll(@Nullable Scroll scroll) {
            this.scroll = scroll;
            return this;
        }

        /**
         * Builds and validates the spec.
         *
         * @return the validated spec
         * @throws IllegalArgumentException if the collected inputs violate the mode's rules
         */
        public @NotNull GradientSpec build() {
            return new GradientSpec(this.mode, this.stops, this.hueCycles, this.bandPx, this.shear, this.scroll);
        }

    }

}

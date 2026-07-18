package lib.minecraft.text.font;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the per-pack colour-sidecar discovery rule: the resource basename is derived from the font
 * id's namespace with the same first-character capitalization the generator uses, and a miss fails
 * loud naming the derived basename and both lookup tiers.
 */
@DisplayName("Colour sidecar per-pack discovery")
class ColorFontSidecarDiscoveryTest {

    private static ColorGlyphSidecar sidecar(String marker) {
        return ColorGlyphSidecar.parse(new StringReader(
            "{\"schema_version\":2,\"file\":\"" + marker + ".ttf\","
                + "\"fonts\":[{\"font_id\":\"synth:demo\",\"file\":\"" + marker + ".ttf\"}]}"));
    }

    @Test
    @DisplayName("the per-pack name capitalizes the namespace's first character, mirroring the generator")
    void derivesPerPackName() {
        assertThat(MinecraftFont.Color.sidecarNameFor(FontId.parse("synth:demo")),
            is("Minecraft-Synth.colour-glyphs.json"));
        assertThat(MinecraftFont.Color.sidecarNameFor(FontId.parse("hypixel:default")),
            is("Minecraft-Hypixel.colour-glyphs.json"));
        // no namespace -> minecraft namespace (FontId.parse default), still capitalized
        assertThat(MinecraftFont.Color.sidecarNameFor(FontId.parse("default")),
            is("Minecraft-Minecraft.colour-glyphs.json"));
    }

    @Test
    @DisplayName("resolution asks the loader for exactly the derived per-pack name")
    void resolvesThroughDerivedName() {
        List<String> asked = new ArrayList<>();
        ColorGlyphSidecar perPack = sidecar("PerPack");

        ColorGlyphSidecar resolved = MinecraftFont.Color.resolveSidecar(FontId.parse("synth:demo"), name -> {
            asked.add(name);
            return name.equals("Minecraft-Synth.colour-glyphs.json") ? Optional.of(perPack) : Optional.empty();
        });

        assertThat(resolved, sameInstance(perPack));
        assertThat(asked, contains("Minecraft-Synth.colour-glyphs.json"));
    }

    @Test
    @DisplayName("a miss fails loud naming the derived per-pack name and both lookup tiers")
    void missNamesDerivedNameAndTiers() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> MinecraftFont.Color.resolveSidecar(FontId.parse("synth:demo"), name -> Optional.empty()));

        assertTrue(ex.getMessage().contains("'Minecraft-Synth.colour-glyphs.json'"),
            "message should name the derived per-pack basename: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("classpath") && ex.getMessage().contains("cache"),
            "message should name both lookup tiers: " + ex.getMessage());
    }

}

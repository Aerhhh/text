package lib.minecraft.text.font;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;

@DisplayName("FontUnits converts font units to output pixels consistently")
class FontUnitsTest {

    @Test
    @DisplayName("one em (1024 units) maps to 8 mcPixels = 16 output pixels")
    void oneEmMapsToEightMcPixels() {
        assertThat(FontUnits.toOutputPixels(1024, 1024), is(16.0));   // 8 mcPixels * MC_PIXEL_SCALE
    }

    @Test
    @DisplayName("a 1024-unit advance yields the same mcPixel width regardless of unitsPerEm")
    void scalesWithUnitsPerEm() {
        // 512 units at unitsPerEm 512 is one em, same as 1024 units at 1024.
        assertThat(FontUnits.toOutputPixels(512, 512), is(16.0));
    }

    @Test
    @DisplayName("negative and fractional units are preserved as a double")
    void preservesSignAndFraction() {
        assertThat(FontUnits.toOutputPixels(-1024, 1024), is(-16.0));
        assertThat(FontUnits.toOutputPixels(96, 1024), closeTo(1.5, 1e-9));
    }

}

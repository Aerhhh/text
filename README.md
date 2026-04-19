# Minecraft Text

A Java 21 library that models Minecraft's chat text system - colours, formatting codes, segment trees, click/hover event payloads - and renders it to raw pixel buffers via the vanilla OTF font family.

> [!IMPORTANT]
> This project depends on font files derived from **copyrighted bitmap assets owned by [Mojang AB](https://www.minecraft.net/)** (a Microsoft subsidiary). The OTF files are **never committed** to this repository. They are generated on demand by the bundled [`fonts`](#fonts) Gradle task, which invokes [`minecraft-library/font-generator`](https://github.com/minecraft-library/font-generator) against the official Minecraft client JAR. You are responsible for ensuring your use of the generated font files complies with the [Minecraft EULA](https://www.minecraft.net/en-us/eula) and [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines).

## Table of Contents

- [Features](#features)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Installation](#installation)
  - [Usage](#usage)
- [Fonts](#fonts)
  - [Runtime Bootstrap](#runtime-bootstrap)
  - [Build-Time Generation](#build-time-generation)
- [Packages](#packages)
- [How It Works](#how-it-works)
  - [Segment Tree](#segment-tree)
  - [Glyph Rendering](#glyph-rendering)
- [Project Structure](#project-structure)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Chat color model** - `ChatColor.Legacy` for vanilla 1.8.9-style codes (`0-9`, `a-f`) and `ChatColor.Custom` for arbitrary modern RGB (`"color": "#FF00FF"`) with automatic shadow derivation via `(rgb & 0xFCFCFC) >> 2`
- **Formatting codes** - `ChatFormat` enum covering `OBFUSCATED`, `BOLD`, `STRIKETHROUGH`, `UNDERLINE`, `ITALIC`, `RESET`, plus the `SECTION_SYMBOL` constant and legacy/alternate-code translation helpers
- **Segment primitives** - `ColorSegment` (styled text run), `TextSegment` (styled run plus click/hover events), and `LineSegment` (list of styled runs) with builders and legacy-string parsing
- **Event payloads** - `ClickEvent` and `HoverEvent` with Gson `JsonObject` (de)serialization
- **Native OTF rendering** - `MinecraftFont` loads the official Minecraft fonts (Regular, Bold, Italic, BoldItalic, Galactic, Illageralt) and rasterizes glyphs into `PixelBuffer`s with zero AWT overhead after initialization
- **Runtime font bootstrap** - downstream consumers don't need to ship OTFs: `MinecraftFont` invokes the generator on first use when the classpath has no `fonts/` resources
- **Gson-backed JSON** - segment, color, and event types round-trip through `JsonObject`

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| [JDK](https://adoptium.net/) | **21+** | Required - the build toolchain targets Java 21 |
| [Git](https://git-scm.com/) | 2.x+ | Required for cloning and for the `fonts` task |
| [Python](https://www.python.org/downloads/) | 3.10+ | Required **only** when running the `fonts` task or the runtime bootstrap |

> [!NOTE]
> Python is not required to consume this library as a dependency if the upstream artifact already bundles the generated OTFs on its classpath. It is required only when *this* repository has to produce fresh font files.

### Installation

Add the Gradle coordinates via [JitPack](https://jitpack.io/):

```kotlin
repositories {
    mavenCentral()
    maven(url = "https://jitpack.io")
}

dependencies {
    implementation("com.github.minecraft-library:minecraft-text:<commit-or-tag>")
}
```

Or clone the repository and build locally:

```bash
git clone https://github.com/minecraft-library/minecraft-text.git
cd minecraft-text
./gradlew build
```

### Usage

Build a single styled run and serialize it to JSON:

```java
import lib.minecraft.text.ChatColor;
import lib.minecraft.text.TextSegment;

TextSegment hello = new TextSegment.Builder()
    .withText("Hello, world!")
    .withColor(ChatColor.Legacy.GOLD)
    .isBold()
    .build();

String json = hello.toJson().toString();
```

Compose multiple styled runs into a single line:

```java
import lib.minecraft.text.ColorSegment;
import lib.minecraft.text.LineSegment;

LineSegment line = LineSegment.builder()
    .withSegments(
        ColorSegment.builder()
            .withText("Hello, ")
            .withColor(ChatColor.Legacy.GOLD)
            .build(),
        ColorSegment.builder()
            .withText("world!")
            .withColor(ChatColor.of(0xFF00FF))
            .isBold()
            .build()
    )
    .build();
```

Or parse a legacy section-symbol string directly:

```java
// Accepts § natively and '&' as a substitute (second overload lets you pick another substitute)
LineSegment parsed = ColorSegment.fromLegacy("&6Hello, &lworld!");
```

Render a line to a `PixelBuffer`:

```java
import dev.simplified.image.pixel.PixelBuffer;
import lib.minecraft.text.font.MinecraftFont;
import lib.minecraft.text.font.MinecraftGraphics;

PixelBuffer buffer = PixelBuffer.of(256, 32);      // output size in render pixels
MinecraftGraphics gfx = new MinecraftGraphics(buffer);

int cursorX = 0;                                    // mcPixel coordinates
int baselineY = MinecraftFont.REGULAR.getFontMetrics().getAscent();

for (ColorSegment segment : line.getSegments()) {
    segment.getColor().ifPresent(c -> gfx.setColor(c.color()));
    gfx.setFont(MinecraftFont.of(segment.fontStyle()));
    gfx.drawString(segment.getText(), cursorX, baselineY);
    cursorX += gfx.getFontMetrics().stringWidth(segment.getText());
}
```

> [!TIP]
> Call `MinecraftFont.REGULAR.glyph(codepoint)` directly if you need per-glyph bitmaps (e.g. for a custom layout engine). The glyph cache is thread-safe and lazily populated via `ConcurrentMap#computeIfAbsent`.

## Fonts

### Runtime Bootstrap

On first use, `MinecraftFont` resolves each OTF in priority order:

1. **Classpath** - `fonts/Minecraft-*.otf` via `ClassLoader.getResourceAsStream`
2. **Module cache** - `cache/fonts/Minecraft-*.otf`
3. **On-demand generation** - invokes `ToolingFonts` to clone the generator and produce the OTFs into `cache/fonts/` (version driven by `MinecraftFont.DEFAULT_VERSION`)

This means downstream consumers can depend on `minecraft-text` without shipping any font assets - the first `MinecraftFont.REGULAR.glyph(...)` call transparently produces them.

> [!IMPORTANT]
> The runtime bootstrap requires `git` and Python 3.10+ on the host's `PATH`. In sealed environments (e.g. production Docker images) you should run the [build-time generation](#build-time-generation) during image build and ship the OTFs on the classpath instead.

### Build-Time Generation

Run the `fonts` Gradle task to populate `cache/fonts/`:

```bash
./gradlew fonts                      # defaults to Minecraft 26.1
./gradlew fonts -PfontVersion=26.2   # pin a different version
```

The task clones [`minecraft-library/font-generator`](https://github.com/minecraft-library/font-generator) into `cache/font-generator/`, creates a Python virtual environment, installs the generator, and emits `.otf` files to `cache/fonts/`. Subsequent runs reuse the clone and venv so only the generator itself re-executes.

The standard `processResources` task then copies `cache/fonts/` onto the runtime classpath under `fonts/`:

```bash
./gradlew build
```

> [!NOTE]
> `cache/` and `src/main/resources/fonts/` are both gitignored. The `fonts` task writes exclusively into `cache/fonts/`; the source tree is never modified.

<details>
<summary>Build order for a fresh checkout</summary>

```bash
./gradlew fonts     # emit cache/fonts/Minecraft-*.otf (run once per version bump)
./gradlew build     # compile, test, and package the jar
```

You can skip the first step if you are only working on code paths that do not exercise `MinecraftFont`. Unit tests under `MinecraftFontTest` will fail without OTFs present.

</details>

## Packages

| Package | Purpose |
|---------|---------|
| `lib.minecraft.text` | Top-level model - `ChatColor`, `ChatFormat`, `TextSegment`, `LineSegment`, `ColorSegment` |
| `lib.minecraft.text.event` | `ClickEvent`, `HoverEvent` payloads with Gson (de)serialization |
| `lib.minecraft.text.font` | `MinecraftFont`, `MinecraftFontMetrics`, `MinecraftGraphics` - OTF loading and glyph rendering |
| `lib.minecraft.text.tooling` | `ToolingFonts` - Gradle/runtime-invoked wrapper around the Python font generator |

## How It Works

### Segment Tree

Minecraft's wire format represents chat text as a recursive tree of segments, each carrying text content plus optional colour, formatting flags, click/hover events, and a child list. The `TextSegment` family mirrors that structure:

```
TextSegment
  ├── content       (plain string or a translation key)
  ├── color         (ChatColor.Legacy | ChatColor.Custom)
  ├── formats       (EnumSet<ChatFormat>)
  ├── clickEvent    (optional)
  ├── hoverEvent    (optional)
  └── children      (List<TextSegment>)
```

`LineSegment` flattens a tree into a linear sequence of same-styled runs suitable for rendering, and `ColorSegment` carries the concrete runs that `MinecraftGraphics` draws.

### Glyph Rendering

Each `MinecraftFont` enum value wraps a pre-loaded AWT `Font` and a lazy glyph atlas:

1. **Enum init** - the OTF resolves via the [runtime bootstrap](#runtime-bootstrap), AWT loads it at `FONT_POINT_SIZE = 16f`, font-level metrics (ascent, descent, height) are captured, and printable ASCII (U+0020 - U+007E) is eagerly rasterized so the first render has zero AWT overhead
2. **Lazy rasterization** - non-ASCII codepoints are rasterized on first access via `ConcurrentMap#computeIfAbsent` and cached for subsequent renders
3. **Draw-time tint** - glyphs are stored as white-on-transparent `PixelBuffer`s; callers multiply each pixel's alpha against a target colour at draw time, so no `Graphics2D` is needed after initialization

The OTFs use `unitsPerEm = 1024` with `128 units = 1 mcPixel`, and `MC_PIXEL_SCALE = 2` maps vanilla Minecraft pixels to output pixels at the standard load size.

## Project Structure

```
minecraft-text/
├── src/
│   ├── main/java/lib/minecraft/text/
│   │   ├── ChatColor.java           # Legacy enum + Custom record
│   │   ├── ChatFormat.java          # Bold/italic/etc. enum
│   │   ├── TextSegment.java         # Recursive segment model
│   │   ├── LineSegment.java         # Flattened same-style runs
│   │   ├── ColorSegment.java        # Draw-ready segment
│   │   ├── event/
│   │   │   ├── ClickEvent.java
│   │   │   └── HoverEvent.java
│   │   ├── font/
│   │   │   ├── MinecraftFont.java          # OTF-backed glyph atlas enum
│   │   │   ├── MinecraftFontMetrics.java   # Ascent/descent/advance capture
│   │   │   └── MinecraftGraphics.java      # Line layout + PixelBuffer output
│   │   └── tooling/
│   │       └── ToolingFonts.java           # Gradle + runtime generator wrapper
│   └── test/java/lib/minecraft/renderer/text/
│       ├── ChatColorTest.java
│       └── font/MinecraftFontTest.java
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── LICENSE.md
├── COPYRIGHT.md
├── CONTRIBUTING.md
└── README.md
```

### Runtime Directories

Created during execution and excluded from version control:

| Directory | Contents |
|-----------|----------|
| `cache/font-generator/` | Clone of the Python font-generator repo and its virtual environment |
| `cache/fonts/` | Generated `Minecraft-*.otf` files - copied onto the classpath by `processResources` |
| `build/` | Gradle outputs (compiled classes, resources, jar) |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style conventions, and how to submit a pull request.

## License

This project is licensed under the **Apache License 2.0** - see [LICENSE.md](LICENSE.md) for the full text.

See [COPYRIGHT.md](COPYRIGHT.md) for third-party attribution notices, including information about Mojang AB's copyrighted assets and the upstream font-generator tool.

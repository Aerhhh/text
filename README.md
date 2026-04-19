# minecraft-text

Minecraft chat text model extracted from `minecraft-library/asset-renderer`.

Provides the Minecraft chat colour / format types (`ChatColor`, `ChatFormat`), segment
primitives (`TextSegment`, `LineSegment`, `ColorSegment`), click/hover event payloads,
and the `MinecraftFont` enum that loads Mojang's OTF fonts from the classpath.

## Packages

- `dev.sbs.renderer.text` - colour codes, formatting, segment tree
- `dev.sbs.renderer.text.event` - `ClickEvent`, `HoverEvent`
- `dev.sbs.renderer.text.font` - `MinecraftFont`, `MinecraftFontMetrics`, `MinecraftGraphics`
- `dev.sbs.renderer.tooling.ToolingFonts` - Gradle-invoked generator that produces the OTF files
- `dev.sbs.renderer.exception` - `RendererException`, `FontException`

## Building

```bash
./gradlew build
```

Font generation (run once per Minecraft version bump, or on a fresh checkout before the
first build that exercises `MinecraftFont`):

```bash
./gradlew fonts           # defaults to Minecraft 26.1
./gradlew fonts -PfontVersion=26.2
```

The generator clones `minecraft-library/font-generator` into `cache/font-generator/`,
sets up a Python venv, and writes the resulting `.otf` files to `cache/fonts/`. The
`processResources` task then copies them to the runtime classpath under `fonts/`.

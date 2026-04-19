# minecraft-text

Java 21 library. Minecraft chat text model (colours, formats, segments, click/hover events, font rendering) extracted from `minecraft-library/asset-renderer`.

## Layout
- Source: `src/main/java/lib/minecraft/text/` (README lists `dev.sbs.renderer.text` - stale)
- Tests: `src/test/java/lib/minecraft/renderer/text/`
- Subpackages: `.event` (Click/Hover), `.font` (MinecraftFont + metrics + graphics), `.tooling` (ToolingFonts generator)

## Build
- `./gradlew build` - standard
- `./gradlew fonts [-PfontVersion=26.1]` - clones `minecraft-library/font-generator` into `cache/font-generator/`, runs Python venv, emits OTFs to `cache/fonts/`. Run once per MC version bump or on fresh checkout before any code exercising `MinecraftFont`.
- `processResources` copies `cache/fonts/` -> classpath `fonts/`. `DuplicatesStrategy.INCLUDE` so generated wins over stray `src/main/resources/fonts/`.

## Conventions
- `cache/` and `texturepacks/` gitignored. **Never commit OTFs or generated font artifacts.**
- No `src/main/resources/` in repo - fonts arrive via generator.
- Deps: version catalog (`libs.*`) for Lombok/JUnit/Hamcrest/Gson/simplified-annotations; pinned jitpack commits for `simplified-dev` libs (bump commit hash in `build.gradle.kts`, not version range).
- Runtime font bootstrap: `MinecraftFont` extracts classpath OTF to temp file on first init - downstream consumers don't ship OTFs.

## Style
- Global rules (Javadoc, exception ctor order, control flow, git) live in `~/.claude/CLAUDE.md`. Don't restate here.
- Lombok: `@Getter`/`@RequiredArgsConstructor` idiomatic; doc the field, not the generated method.
- `@NotNull` / `@Nullable` from `org.jetbrains.annotations`.

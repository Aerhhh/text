# Contributing to Minecraft Text

Thank you for your interest in contributing! This document explains how to get started, what to expect during the review process, and the conventions this project follows.

## Table of Contents

- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Development Setup](#development-setup)
  - [Generating Fonts](#generating-fonts)
- [Making Changes](#making-changes)
  - [Branching Strategy](#branching-strategy)
  - [Code Style](#code-style)
  - [Javadoc](#javadoc)
  - [Exceptions](#exceptions)
  - [Commit Messages](#commit-messages)
  - [Validating Output](#validating-output)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Reporting Issues](#reporting-issues)
- [Project Architecture](#project-architecture)
- [Legal](#legal)

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| [JDK](https://adoptium.net/) | 21+ | Required - matches the Gradle toolchain |
| [Git](https://git-scm.com/) | 2.x+ | For cloning and for the `fonts` task |
| [Python](https://www.python.org/downloads/) | 3.10+ | Required to run the `fonts` task locally |
| IntelliJ IDEA (optional) | Latest | Recommended IDE - ships with Lombok and Gradle support |

### Development Setup

1. **Fork and clone the repository**

   [Fork the repository](https://github.com/minecraft-library/minecraft-text/fork), then clone your fork:

   ```bash
   git clone https://github.com/<your-username>/minecraft-text.git
   cd minecraft-text
   ```

2. **Verify the toolchain**

   The Gradle build downloads and pins a Java 21 toolchain automatically, but you should confirm Java is on your `PATH`:

   ```bash
   java -version
   ```

3. **Generate the fonts** (first checkout only)

   ```bash
   ./gradlew fonts
   ```

   See [Generating Fonts](#generating-fonts) for version pinning and troubleshooting.

4. **Build and test**

   ```bash
   ./gradlew build
   ```

> [!TIP]
> Running `./gradlew build` before `./gradlew fonts` on a fresh checkout will fail the `MinecraftFontTest` suite - the test exercises `MinecraftFont` which requires the OTFs on the classpath. Always run `fonts` first on a new clone.

### Generating Fonts

The `fonts` task clones [`minecraft-library/font-generator`](https://github.com/minecraft-library/font-generator) into `cache/font-generator/`, sets up a Python virtual environment, installs the generator, and writes `.otf` files to `cache/fonts/`.

```bash
# Default Minecraft version (matches MinecraftFont.DEFAULT_VERSION)
./gradlew fonts

# Pin a specific version
./gradlew fonts -PfontVersion=26.2
```

<details>
<summary>Installing Python</summary>

**Linux (Debian / Ubuntu)**
```bash
sudo apt install python3 python3-venv
```

**Linux (Fedora)**
```bash
sudo dnf install python3
```

**macOS**
```bash
brew install python@3.12
```

**Windows**

Download the installer from [python.org](https://www.python.org/downloads/) and ensure "Add python.exe to PATH" is checked during setup.

</details>

Confirm Python is on your `PATH` before running the task:

```bash
python --version  # or python3 --version
```

> [!NOTE]
> The task tries `python3`, `python`, then `py` (in that order) when locating the interpreter. Any of the three works; you do not need to symlink.

> [!IMPORTANT]
> Never commit the contents of `cache/fonts/` or `cache/font-generator/` to the repository. Both paths are gitignored, but double-check `git status` before staging if you have been experimenting locally.

## Making Changes

### Branching Strategy

- Create a feature branch from `master` for your work.
- Use a descriptive branch name: `fix/segment-tree-null-children`, `feat/hover-event-item-payload`, `docs/render-pipeline`.

```bash
git checkout -b feat/my-feature master
```

### Code Style

This project does not enforce an auto-formatter, but please follow these conventions:

- **Java version** - target Java 21 features where they improve clarity (records, sealed types, pattern matching). `ChatColor` already uses a `sealed interface` hierarchy; follow the same pattern when modelling closed type sets.
- **Naming** - `camelCase` for methods and fields, `PascalCase` for types, `UPPER_SNAKE_CASE` for constants.
- **Packages** - all production code lives under `lib.minecraft.text.*`. Place new types in the subpackage that matches their role (`event`, `font`, `tooling`).
- **Null safety** - annotate non-null and nullable references with `@NotNull` / `@Nullable` from `org.jetbrains.annotations`. Every `Throwable cause` and `String message` on an exception constructor is `@NotNull`; varargs `Object...` is `@Nullable`.
- **Lombok** - `@Getter`, `@RequiredArgsConstructor`, `@UtilityClass`, and `@Accessors(fluent = true)` are idiomatic. Document the field, not the generated accessor.
- **Control flow** - omit braces on single-line bodies; add them when the body wraps.
- **Line length** - aim for 120 characters or fewer.

### Javadoc

- **Punctuation** - use single hyphens (` - `) only. Never em dashes, `&mdash;`, or `--`.
- **Voice** - class/interface docs are noun phrases; method docs start with a third-person verb ("Returns", "Rasterizes"); field docs are fragments.
- **Tags** - include `@param`, `@return`, and `@throws` where applicable. Lowercase fragments, no trailing period, single space after the param name.
- **References** - use `{@link}` / `{@linkplain}` / `@see`. Import the target so the short name resolves; fully-qualify only on conflict.
- **Overrides** - use `/** {@inheritDoc} */` - do not rewrite the parent doc.
- **Structure** - `<p>` on its own line between paragraphs; `<ul>` / `<li>` for lists; `<b>` for list-item emphasis.
- Never emit `@author` or `@since`.

### Exceptions

Constructor overloads must be declared in this order:

1. `(Throwable cause)`
2. `(String message)`
3. `(Throwable cause, String message)`
4. `(@PrintFormat String message, Object... args)`
5. `(Throwable cause, @PrintFormat String message, Object... args)`

Root exception types (direct subclasses of `RuntimeException`) reverse the `super()` parameter order:

```java
super(message, cause);
super(String.format(message, args), cause);
```

Child exception types pass through to the parent, which performs the reversal:

```java
super(cause, message);
super(cause, message, args);
```

Messages start uppercase, carry no trailing punctuation, and quote interpolated values with `'%s'`.

### Commit Messages

Write clear, concise commit messages that describe *what* changed and *why*.

```
Add runtime font bootstrap so downstream consumers work without shipping OTFs

Resolves the OTF in three tiers (classpath, cache/fonts, on-demand
generation). Drops the hard requirement for consumers to package
the font files themselves.
```

- Use the imperative mood ("Add", "Fix", "Update", not "Added", "Fixes").
- Keep the subject line under 72 characters.
- Add a body when the *why* isn't obvious from the subject.

### Validating Output

If your change touches segment rendering, glyph rasterization, or font loading, exercise the test suite and inspect the generated output before opening a PR.

```bash
# Run all unit tests
./gradlew test

# Run a specific test class
./gradlew test --tests 'lib.minecraft.renderer.text.font.MinecraftFontTest'
```

> [!TIP]
> For visual rendering changes, dump the generated `PixelBuffer` to a PNG in a throwaway test method and attach it to your PR. Screenshots make glyph-level regressions obvious in review.

Changes to font generation itself (e.g. bumping `DEFAULT_VERSION`, modifying `ToolingFonts`) should be verified by deleting `cache/fonts/` and re-running `./gradlew fonts build`.

## Submitting a Pull Request

1. **Push your branch** to your fork.

   ```bash
   git push origin feat/my-feature
   ```

2. **Open a Pull Request** against the `master` branch of [minecraft-library/minecraft-text](https://github.com/minecraft-library/minecraft-text).

3. **In the PR description**, include:
   - A summary of the changes and the motivation behind them.
   - Steps to test or verify the changes (specific segment trees, font versions, test classes, etc.).
   - Rendered output comparisons (before/after PNGs) for any change that affects glyph or segment rendering.

4. **Respond to review feedback.** PRs may go through one or more rounds of review before being merged.

### What gets reviewed

- Correctness of the segment model and its JSON (de)serialization round-trips.
- Null-safety annotations and exception constructor signatures.
- Impact on glyph rendering output - any change to `MinecraftFont`, `MinecraftFontMetrics`, or `MinecraftGraphics` should be exercised against the existing test suite and demonstrated visually.
- Whether new dependencies are pinned (jitpack commits must strictly pin a commit hash, not a version range).

## Reporting Issues

Use [GitHub Issues](https://github.com/minecraft-library/minecraft-text/issues) to report bugs or request features.

When reporting a bug, include:

- **JDK version** (`java -version`)
- **Operating system**
- **Minecraft version** the generator was invoked against (if applicable)
- **Full stack trace** (if applicable)
- **Steps to reproduce** - a minimal `TextSegment` tree or code snippet is ideal
- **Expected vs. actual behavior**

## Project Architecture

A brief overview to help you find your way around the codebase:

```
src/main/java/lib/minecraft/text/
├── ChatColor.java              # Sealed: Legacy enum + Custom record
├── ChatFormat.java             # Formatting code enum
├── TextSegment.java            # Recursive segment tree + Gson wiring
├── LineSegment.java            # Flattened same-style runs
├── ColorSegment.java           # Draw-ready segment consumed by MinecraftGraphics
├── event/
│   ├── ClickEvent.java
│   └── HoverEvent.java
├── font/
│   ├── MinecraftFont.java          # OTF-backed glyph atlas enum + runtime bootstrap
│   ├── MinecraftFontMetrics.java   # Ascent/descent/advance capture
│   └── MinecraftGraphics.java      # Line layout + PixelBuffer output
└── tooling/
    └── ToolingFonts.java           # Gradle + runtime wrapper for the Python generator
```

### Render Pipeline

```
legacy string (§6...§l...)  or  JSON text component
      ↓                                ↓
ColorSegment.fromLegacy            TextSegment.fromJson
      ↓                                ↓
              LineSegment (ConcurrentList<ColorSegment>)
                            ↓
              MinecraftGraphics#drawString (per ColorSegment)
                  ├── setColor   ← ColorSegment#getColor
                  ├── setFont    ← MinecraftFont.of(ColorSegment#fontStyle)
                  └── drawString ← ColorSegment#getText
                            ↓
                       PixelBuffer
```

If your change touches glyph rasterization (`MinecraftFont`, `MinecraftFontMetrics`), delete `cache/fonts/` and re-run the full pipeline to confirm the runtime bootstrap still produces byte-identical output to the Gradle `fonts` task.

## Legal

By submitting a pull request, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE.md), the same license that covers this project.

This project depends on a generator that processes copyrighted assets owned by Mojang AB at runtime. Do not commit any Minecraft assets (textures, JARs, JSON files extracted from the client, generated OTFs) to the repository.

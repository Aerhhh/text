# Copyright Notice

## Project License

Copyright 2025 CraftedFury

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

> <http://www.apache.org/licenses/LICENSE-2.0>

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## Third-Party Notices

### Minecraft Font Glyphs - Mojang AB

The bitmap font textures and glyph data processed by the upstream font generator
are the copyrighted property of **Mojang AB** (a Microsoft subsidiary). These
assets are extracted at runtime from the official Minecraft client JAR and are
**not distributed** with this repository. The generated `.otf` files derived
from those assets are likewise excluded from version control (see the `cache/`
entry in `.gitignore`).

> "Minecraft" is a trademark of Mojang AB. This project is not affiliated with
> or endorsed by Mojang AB or Microsoft Corporation.

Users are responsible for ensuring their use of the generated font files
complies with the [Minecraft End User License Agreement (EULA)](https://www.minecraft.net/en-us/eula)
and [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines).

### Minecraft Font Generator - Apache License 2.0

The font pipeline is driven by
[`minecraft-library/font-generator`](https://github.com/minecraft-library/font-generator),
cloned on demand by `ToolingFonts` and invoked during the `fonts` Gradle task
or the runtime bootstrap. The generator is licensed under the **Apache License
2.0**. It in turn relies on [fontTools](https://github.com/fonttools/fonttools)
(Apache-2.0) for OpenType and TrueType table construction.

### GNU Unifont - GNU General Public License

When the upstream generator is configured with unifont fallback enabled, it
downloads [GNU Unifont](https://unifoundry.com/unifont/) hex files from
Minecraft's asset index at runtime. GNU Unifont is licensed under the
**GNU General Public License v2+** with a font embedding exception that
permits the generated font files to be used and distributed without the GPL
applying to documents or applications that embed the font.

### Compile-Time Dependencies

| Dependency | License | Notes |
|------------|---------|-------|
| [Lombok](https://projectlombok.org/) | MIT | Compile-time annotation processor; not retained at runtime |
| [Simplified Annotations](https://github.com/simplified-dev/annotations) | Apache-2.0 | Compile-time annotation processor |

### Runtime Dependencies

| Dependency | License | Notes |
|------------|---------|-------|
| [Gson](https://github.com/google/gson) | Apache-2.0 | `JsonObject` / `JsonElement` used by `TextSegment` and event payloads |
| [Simplified Collections](https://github.com/simplified-dev/collections) | Apache-2.0 | Concurrent map primitives backing `MinecraftFont`'s glyph cache |
| [Simplified Utils](https://github.com/simplified-dev/utils) | Apache-2.0 | Shared utility helpers |
| [Simplified Image](https://github.com/simplified-dev/image) | Apache-2.0 | `PixelBuffer` primitive used as the glyph-render target |

### Test Dependencies

| Dependency | License | Notes |
|------------|---------|-------|
| [JUnit Jupiter](https://junit.org/junit5/) | EPL-2.0 | Test framework |
| [Hamcrest](https://hamcrest.org/JavaHamcrest/) | BSD-3-Clause | Matcher library for tests |

## Attribution

- **Project author** - [CraftedFury](https://sbs.dev/)
- **Organization** - [Minecraft Library](https://github.com/minecraft-library)
- **Upstream font generator** - [minecraft-library/font-generator](https://github.com/minecraft-library/font-generator) (Apache-2.0)
- **Font building library** - [fontTools](https://github.com/fonttools/fonttools) (Apache-2.0)

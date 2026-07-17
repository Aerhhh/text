# Colour-font test fixtures

`gen_fixtures.py` generates the synthetic colour-font fixtures under
`src/test/resources/colorfont/` that back the colour-glyph test suite. Every cell is
painted from scratch in the script - no real resource-pack assets are used or committed.

It emits:

- `SynthColour.ttf` - ONE merged `sbix` TrueType carrying both font ids (`synth:demo` and
  `synth:alt`). It has two strikes (ppem 8 and 16), a de-duplicated glyph, a 256px tall cell,
  and it reuses codepoint `U+E001` across the two font ids with different artwork - resolved by
  giving each `(font_id, U+E001)` pair its own stored codepoint from plane 15. `synth:alt` sorts
  first, so its glyph is gid 1 and `synth:demo`'s glyphs follow at gids 2..6.
- `SynthColour-edge.ttf` - a four-glyph font with a `dupe` record and a non-`png ` record
  to exercise the `sbix` reader edge paths.
- `colour-glyphs.json` - the versioned single-file sidecar (schema v2): one top-level `file`
  reference, and per-glyph raster rows (original codepoint, stored codepoint, gid, signed advance,
  origin, strike ppem) plus space-provider rows (negative and fractional advances).

## Regenerating

Requires Python 3 with `fontTools`, `Pillow`, and `numpy`. On Windows, run from a short path
(e.g. `subst`ed drive) - fontTools/Pillow C extensions fail to import from a very deep path.

```
python gen_fixtures.py ../../src/test/resources/colorfont
```

Output is deterministic (`recalcTimestamp`/`recalcBBoxes` pinned, sidecar keys sorted), so a
regeneration produces byte-identical fixtures.

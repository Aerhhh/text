# Colour-font test fixtures

`gen_fixtures.py` generates the synthetic colour-font fixtures under
`src/test/resources/colorfont/` that back the colour-glyph test suite. Every cell is
painted from scratch in the script - no real resource-pack assets are used or committed.

It emits:

- `SynthColour-demo.ttf` - an `sbix` TrueType for font id `synth:demo` with two strikes
  (ppem 8 and 16), a de-duplicated glyph, and a 256px tall cell.
- `SynthColour-alt.ttf` - font id `synth:alt`, reusing codepoint `U+E001` with different
  artwork to exercise per-`font_id` PUA disambiguation.
- `SynthColour-edge.ttf` - a four-glyph font with a `dupe` record and a non-`png ` record
  to exercise the `sbix` reader edge paths.
- `colour-glyphs.json` - the versioned sidecar (schema v1) covering both font ids, with
  raster rows (signed advance, origin, strike ppem) and space-provider rows (negative and
  fractional advances).

## Regenerating

Requires Python 3 with `fontTools`, `Pillow`, and `numpy`. On Windows, run from a short path
(e.g. `subst`ed drive) - fontTools/Pillow C extensions fail to import from a very deep path.

```
python gen_fixtures.py ../../src/test/resources/colorfont
```

Output is deterministic (`recalcTimestamp`/`recalcBBoxes` pinned, sidecar keys sorted), so a
regeneration produces byte-identical fixtures.

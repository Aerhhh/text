"""Synthetic colour-glyph test-fixture generator for the text library.

Derived from the e2e-proto pipeline (impl-research/e2e-proto/e2e.py). Emits the
committed fixtures consumed by the colour-font test suite:

  colorfont/colour-glyphs.json   versioned sidecar covering two font ids
  colorfont/SynthColour-demo.ttf sbix TrueType for font id synth:demo
  colorfont/SynthColour-alt.ttf  sbix TrueType for font id synth:alt (PUA reuse)
  colorfont/SynthColour-edge.ttf sbix TrueType exercising dupe + non-png records

Everything is synthetic - no real pack assets. Deterministic under a pinned epoch
so the committed bytes never drift between machines.
"""
import io, os, json, hashlib, sys
import numpy as np
from PIL import Image
from fontTools.ttLib import TTFont, newTable
from fontTools.ttLib.tables.sbixStrike import Strike
from fontTools.ttLib.tables.sbixGlyph import Glyph as SbixGlyph
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib.tables._c_m_a_p import CmapSubtable
from fontTools.ttLib.tables.O_S_2f_2 import Panose

UNITS_PER_EM = 1024
DEFAULT_GLYPH_SIZE = 8
ASCENT = (DEFAULT_GLYPH_SIZE - 1) * (UNITS_PER_EM // DEFAULT_GLYPH_SIZE)   # 896
DESCENT = -(UNITS_PER_EM // DEFAULT_GLYPH_SIZE)                            # -128
EPOCH = 0
GRAPHIC_TYPE = "png "
SCHEMA_VERSION = 1
GENERATOR_VERSION = "1.0.0"

# classifier thresholds (mirror the fontgen colour classifier)
OPQ_A, AA_LO, SIG_COVER, SIG_MIN, AA_FLAT_MAX, HUGE = 240, 8, 0.02, 15, 0.02, 128


# ----------------------------------------------------------------------------
# cell painting helpers (all synthetic)
# ----------------------------------------------------------------------------
def sheet_demo():
    # 8x8 cells, 4 in a row (32x8): mono H, flat 2-colour, aa multi, dup-of-1
    a = np.zeros((8, 32, 4), np.uint8)
    a[1:7, 1, :] = (255, 255, 255, 255)
    a[1:7, 6, :] = (255, 255, 255, 255)
    a[3:5, 1:7, :] = (255, 255, 255, 255)
    a[:, 8:12, :] = (220, 40, 40, 255)
    a[:, 12:16, :] = (40, 60, 220, 255)
    a[:, 16:24, :] = (30, 180, 60, 255)
    a[2:6, 18:22, :] = (240, 150, 20, 255)
    a[0, 16:24, 3] = 120
    a[7, 16:24, 3] = 120
    a[:, 16, 3] = np.minimum(a[:, 16, 3], 120)
    a[:, 23, 3] = np.minimum(a[:, 23, 3], 120)
    a[:, 24:28, :] = (220, 40, 40, 255)
    a[:, 28:32, :] = (40, 60, 220, 255)
    return a


def cell_b():
    b = np.zeros((16, 16, 4), np.uint8)
    b[:, :8, :] = (200, 120, 30, 255)
    b[:, 8:, :] = (30, 120, 200, 255)
    return b


def cell_c():
    c = np.zeros((16, 16, 4), np.uint8)
    c[:8, :8, :] = (200, 30, 30, 255)
    c[:8, 8:, :] = (30, 200, 30, 255)
    c[8:, :8, :] = (30, 30, 200, 255)
    c[8:, 8:, :] = (200, 200, 30, 255)
    return c


def cell_d():
    d = np.zeros((256, 256, 4), np.uint8)
    yy, xx = np.mgrid[0:256, 0:256]
    d[..., 0] = (xx & 0xFF).astype(np.uint8)
    d[..., 1] = (yy & 0xFF).astype(np.uint8)
    d[..., 2] = ((xx ^ yy) & 0xFF).astype(np.uint8)
    d[..., 3] = 255
    return d


def cell_alt():
    # 8x8 flat 2-colour distinct from demo E001 (green/yellow, not red/blue)
    a = np.zeros((8, 8, 4), np.uint8)
    a[:, :4, :] = (40, 200, 60, 255)
    a[:, 4:, :] = (230, 210, 30, 255)
    return a


# ----------------------------------------------------------------------------
# slice / classify / encode
# ----------------------------------------------------------------------------
def classify(cell):
    a = cell[:, :, 3].astype(np.int32)
    nontrans = int((a > 0).sum())
    if nontrans == 0:
        return "mono"
    aa = int(((a > AA_LO) & (a < OPQ_A)).sum())
    if max(cell.shape[1], cell.shape[0]) > HUGE:
        return "raster"
    if aa / nontrans > AA_FLAT_MAX:
        return "raster"
    opq_mask = a >= OPQ_A
    opq = int(opq_mask.sum())
    if opq == 0:
        return "mono"
    rgb = (cell[:, :, :3][opq_mask] >> 4).astype(np.int32)
    keys = (rgb[:, 0] << 8) | (rgb[:, 1] << 4) | rgb[:, 2]
    _, counts = np.unique(keys, return_counts=True)
    thr = max(SIG_MIN, opq * SIG_COVER)
    return "raster" if int((counts >= thr).sum()) >= 2 else "mono"


def encode_png(cell):
    buf = io.BytesIO()
    Image.fromarray(cell, "RGBA").save(buf, format="PNG", optimize=False, compress_level=6)
    return buf.getvalue()


# each tile: dict(codepoint, cell, cw, ch, height) ; space rows: (codepoint, advance)
# The colour layer is raster + space only. Mono-classified cells belong to the mono
# font (the runtime falls back to the vanilla atlas for them), so they are dropped here.
def build_font(font_name, tiles, space_rows, font_id):
    embed = {}          # png sha -> glyph name
    raster_png = {}      # glyph name -> png bytes
    records = []         # ordered per-codepoint
    for t in sorted(tiles, key=lambda x: x["codepoint"]):
        cp = t["codepoint"]
        gname = "u%04X" % cp
        mode = classify(t["cell"])
        if mode == "mono":
            continue     # not a colour glyph; handled by the mono font / vanilla fallback
        display_scale = t["height"] / t["ch"]
        ppem = round(DEFAULT_GLYPH_SIZE / display_scale)
        png = encode_png(t["cell"])
        h = hashlib.sha256(png).hexdigest()
        if h in embed:
            gname = embed[h]
        else:
            embed[h] = gname
            raster_png[gname] = png
        adv = round(t["cw"] * (UNITS_PER_EM / t["ch"]) * display_scale)
        records.append(dict(cp=cp, gname=gname, advance=adv, ppem=ppem, png_sha=h))

    order = [".notdef"]
    seen = {".notdef"}
    for gn in sorted(raster_png):
        if gn not in seen:
            order.append(gn); seen.add(gn)

    f = _new_ttfont(font_name)
    glyf = newTable("glyf"); glyf.glyphs = {}
    empty = TTGlyphPen(None).glyph()
    for gn in order:
        glyf.glyphs[gn] = empty      # empty glyf; artwork lives in the sbix strike
    f["glyf"] = glyf
    f["loca"] = newTable("loca")
    f.setGlyphOrder(order); glyf.glyphOrder = order
    gid_of = {gn: i for i, gn in enumerate(order)}

    adv_by = {}
    for r in records:
        adv_by[r["gname"]] = r["advance"]
    hmtx = newTable("hmtx"); hmtx.metrics = {".notdef": (UNITS_PER_EM // 2, 0)}
    for gn in order[1:]:
        hmtx.metrics[gn] = (max(0, min(0xFFFF, adv_by.get(gn, 0))), 0)
    f["hmtx"] = hmtx

    cmap_dict = {r["cp"]: r["gname"] for r in records}
    f["cmap"] = _cmap(cmap_dict)

    strikes = {}
    max_h = 0
    for r in records:
        png = raster_png[r["gname"]]
        strikes.setdefault(r["ppem"], {})[r["gname"]] = png
        max_h = max(max_h, Image.open(io.BytesIO(png)).size[1])
    if strikes:
        _attach_sbix(f, strikes)

    _finish_tables(f, order, max_h)

    rows = []
    for r in sorted(records, key=lambda x: x["cp"]):
        rows.append(dict(font_id=font_id, codepoint=r["cp"], glyphName=r["gname"],
                         gid=gid_of[r["gname"]], advance=r["advance"],
                         origin=[0, 0], strike_ppem=r["ppem"]))
    covered = {r["cp"] for r in records}
    for cp, adv in sorted(space_rows):
        if cp in covered:
            continue
        rows.append(dict(font_id=font_id, codepoint=cp, glyphName=None, gid=None,
                         advance=adv, origin=[0, 0], strike_ppem=None))
    return f, rows


def _new_ttfont(_name):
    f = TTFont()
    f.sfntVersion = "\x00\x01\x00\x00"
    f.recalcBBoxes = False
    f.recalcTimestamp = False   # pin head.modified so committed bytes are reproducible
    return f


def _cmap(cmap_dict):
    cmap = newTable("cmap"); cmap.tableVersion = 0
    sub4 = CmapSubtable.newSubtable(4)
    sub4.platformID = 3; sub4.platEncID = 1; sub4.language = 0
    sub4.cmap = {cp: gn for cp, gn in cmap_dict.items() if cp <= 0xFFFF}
    sub12 = CmapSubtable.newSubtable(12)
    sub12.platformID = 3; sub12.platEncID = 10; sub12.language = 0
    sub12.format = 12; sub12.reserved = 0; sub12.length = 0; sub12.nGroups = 0
    sub12.cmap = dict(cmap_dict)
    cmap.tables = [sub4, sub12]
    return cmap


def _attach_sbix(f, strikes):
    sbix = newTable("sbix"); sbix.version = 1; sbix.flags = 1
    sbix.numStrikes = 0; sbix.strikes = {}
    for ppem in sorted(strikes):
        st = Strike(ppem=ppem, resolution=72)
        for gn, png in strikes[ppem].items():
            st.glyphs[gn] = SbixGlyph(glyphName=gn, graphicType=GRAPHIC_TYPE,
                                      imageData=png, originOffsetX=0, originOffsetY=0)
        sbix.strikes[ppem] = st
    f["sbix"] = sbix


def _finish_tables(f, order, max_img_h):
    ymax_art = round(max_img_h * (UNITS_PER_EM / DEFAULT_GLYPH_SIZE) / DEFAULT_GLYPH_SIZE) if max_img_h else UNITS_PER_EM
    ymax = max(UNITS_PER_EM, ymax_art)
    head = newTable("head")
    for a, v in dict(checkSumAdjustment=0, created=EPOCH, modified=EPOCH, flags=11,
                     fontRevision=1.0, fontDirectionHint=2, glyphDataFormat=0, indexToLocFormat=1,
                     lowestRecPPEM=8, macStyle=0, magicNumber=0x5F0F3CF5, tableVersion=1.0,
                     unitsPerEm=UNITS_PER_EM, xMin=0, yMin=DESCENT, xMax=ymax, yMax=ymax).items():
        setattr(head, a, v)
    f["head"] = head

    numg = len(order)
    hhea = newTable("hhea")
    for a, v in dict(tableVersion=0x00010000, ascent=ASCENT, descent=DESCENT, lineGap=0,
                     advanceWidthMax=0xFFFF, minLeftSideBearing=0, minRightSideBearing=0,
                     xMaxExtent=ymax, caretSlopeRise=1, caretSlopeRun=0, caretOffset=0,
                     reserved0=0, reserved1=0, reserved2=0, reserved3=0, metricDataFormat=0,
                     numberOfHMetrics=numg).items():
        setattr(hhea, a, v)
    f["hhea"] = hhea

    maxp = newTable("maxp"); maxp.tableVersion = 0x00010000
    for a in ("maxPoints", "maxContours", "maxCompositePoints", "maxCompositeContours",
              "maxTwilightPoints", "maxStorage", "maxFunctionDefs", "maxInstructionDefs",
              "maxStackElements", "maxSizeOfInstructions", "maxComponentElements", "maxComponentDepth"):
        setattr(maxp, a, 0)
    maxp.maxZones = 2; maxp.numGlyphs = numg
    f["maxp"] = maxp

    os2 = newTable("OS/2"); os2.version = 4
    win_asc = max(ASCENT, ymax)
    for a, v in dict(xAvgCharWidth=UNITS_PER_EM // 2, usWeightClass=400, usWidthClass=5, fsType=0,
                     ySubscriptXSize=0, ySubscriptYSize=0, ySubscriptXOffset=0, ySubscriptYOffset=0,
                     ySuperscriptXSize=0, ySuperscriptYSize=0, ySuperscriptXOffset=0, ySuperscriptYOffset=0,
                     yStrikeoutSize=50, yStrikeoutPosition=250, sFamilyClass=0, panose=Panose(),
                     ulUnicodeRange1=0, ulUnicodeRange2=0, ulUnicodeRange3=0, ulUnicodeRange4=0,
                     achVendID="SNTH", fsSelection=0x40, usFirstCharIndex=0xE000, usLastCharIndex=0xE0FF,
                     sTypoAscender=ASCENT, sTypoDescender=DESCENT, sTypoLineGap=0, usWinAscent=win_asc,
                     usWinDescent=128, ulCodePageRange1=0, ulCodePageRange2=0, sxHeight=640,
                     sCapHeight=ASCENT, usDefaultChar=0, usBreakChar=32, usMaxContext=0).items():
        setattr(os2, a, v)
    f["OS/2"] = os2

    name = newTable("name"); name.names = []
    name.setName("SynthColour", 1, 3, 1, 0x409); name.setName("Regular", 2, 3, 1, 0x409)
    name.setName("SynthColour-Regular", 4, 3, 1, 0x409); name.setName("SynthColour-Regular", 6, 3, 1, 0x409)
    f["name"] = name

    post = newTable("post"); post.formatType = 3.0; post.italicAngle = 0
    post.underlinePosition = -100; post.underlineThickness = 50; post.isFixedPitch = 0
    post.minMemType42 = post.maxMemType42 = post.minMemType1 = post.maxMemType1 = 0
    f["post"] = post


def build_edge_font():
    """4-glyph sbix font: g1 real png, g2 dupe->g1, g3 non-png ('jpg ') record."""
    order = [".notdef", "g1", "g2", "g3"]
    f = _new_ttfont("SynthEdge")
    glyf = newTable("glyf"); glyf.glyphs = {}
    empty = TTGlyphPen(None).glyph()
    for gn in order:
        glyf.glyphs[gn] = empty
    f["glyf"] = glyf; f["loca"] = newTable("loca")
    f.setGlyphOrder(order); glyf.glyphOrder = order

    hmtx = newTable("hmtx")
    hmtx.metrics = {".notdef": (UNITS_PER_EM // 2, 0), "g1": (512, 0), "g2": (512, 0), "g3": (512, 0)}
    f["hmtx"] = hmtx
    f["cmap"] = _cmap({0xE000: "g1", 0xE001: "g2", 0xE002: "g3"})

    red = np.zeros((4, 4, 4), np.uint8); red[:, :, :] = (255, 0, 0, 255)
    png = encode_png(red)
    sbix = newTable("sbix"); sbix.version = 1; sbix.flags = 1; sbix.numStrikes = 0; sbix.strikes = {}
    st = Strike(ppem=8, resolution=72)
    st.glyphs["g1"] = SbixGlyph(glyphName="g1", graphicType="png ", imageData=png,
                                originOffsetX=0, originOffsetY=0)
    # dupe record: references g1 (gid 1); fontTools encodes the 2-byte referenced glyph id
    st.glyphs["g2"] = SbixGlyph(glyphName="g2", graphicType="dupe", referenceGlyphName="g1")
    st.glyphs["g3"] = SbixGlyph(glyphName="g3", graphicType="jpg ", imageData=b"\xff\xd8\xff\xe0notjpeg",
                                originOffsetX=0, originOffsetY=0)
    sbix.strikes[8] = st
    f["sbix"] = sbix
    _finish_tables(f, order, 4)
    return f


def save(f, path):
    buf = io.BytesIO(); f.save(buf)
    with open(path, "wb") as fh:
        fh.write(buf.getvalue())


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)

    a = sheet_demo()
    demo_tiles = [
        dict(codepoint=0xE000, cell=a[:, 0:8].copy(), cw=8, ch=8, height=8),      # mono H
        dict(codepoint=0xE001, cell=a[:, 8:16].copy(), cw=8, ch=8, height=8),     # flat 2-colour
        dict(codepoint=0xE002, cell=a[:, 16:24].copy(), cw=8, ch=8, height=8),    # aa multi
        dict(codepoint=0xE003, cell=a[:, 24:32].copy(), cw=8, ch=8, height=8),    # dup of E001
        dict(codepoint=0xE004, cell=cell_b(), cw=16, ch=16, height=16),           # flat, ppem 8
        dict(codepoint=0xE005, cell=cell_c(), cw=16, ch=16, height=8),            # multi, ppem 4
        dict(codepoint=0xE006, cell=cell_d(), cw=256, ch=256, height=256),        # tall art, ppem 8
    ]
    demo_space = [(0xE010, -1024), (0xE011, 96)]   # negative (-16px) ; fractional (96/64 = 1.5px)
    demo_font, demo_rows = build_font("SynthColour-demo", demo_tiles, demo_space, "synth:demo")

    alt_tiles = [dict(codepoint=0xE001, cell=cell_alt(), cw=8, ch=8, height=8)]
    alt_font, alt_rows = build_font("SynthColour-alt", alt_tiles, [], "synth:alt")

    edge_font = build_edge_font()

    save(demo_font, os.path.join(out_dir, "SynthColour-demo.ttf"))
    save(alt_font, os.path.join(out_dir, "SynthColour-alt.ttf"))
    save(edge_font, os.path.join(out_dir, "SynthColour-edge.ttf"))

    sidecar = {
        "schema_version": SCHEMA_VERSION,
        "generator_version": GENERATOR_VERSION,
        "source_date_epoch": EPOCH,
        "units_per_em": UNITS_PER_EM,
        "graphic_type": GRAPHIC_TYPE,
        "fonts": [
            {"font_id": "synth:demo", "file": "SynthColour-demo.ttf"},
            {"font_id": "synth:alt", "file": "SynthColour-alt.ttf"},
        ],
        "glyphs": demo_rows + alt_rows,
    }
    with open(os.path.join(out_dir, "colour-glyphs.json"), "w", encoding="utf-8") as fh:
        json.dump(sidecar, fh, ensure_ascii=False, indent=2, sort_keys=True)
    print("wrote fixtures to", out_dir)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "out")

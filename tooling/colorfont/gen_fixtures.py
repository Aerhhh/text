"""Synthetic colour-glyph test-fixture generator for the text library.

Derived from the e2e-proto pipeline (impl-research/e2e-proto/e2e.py). Emits the
committed fixtures consumed by the colour-font test suite:

  colorfont/colour-glyphs.json   versioned (schema v2) single-file sidecar
  colorfont/SynthColour.ttf      ONE merged sbix TrueType for both font ids
  colorfont/SynthColour-edge.ttf sbix TrueType exercising dupe + non-png records

Mirrors the fontgen single-file colour output: every (font_id, original_codepoint)
raster pair is assigned a synthetic STORED codepoint from plane 15/16, so the two
font ids (synth:demo and synth:alt reuse U+E001) coexist in one merged font whose
cmap keys on stored codepoints. The sidecar bridges pack + original codepoint back
to the stored codepoint and gid.

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
SCHEMA_VERSION = 2
GENERATOR_VERSION = "1.0.0"
STORED_CP_START = 0xF0000   # plane 15; stored codepoints are assigned linearly from here

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


# each spec: dict(font_id, tiles=[dict(codepoint, cell, cw, ch, height)], space=[(cp, adv)])
# The colour layer is raster + space only. Mono-classified cells belong to the mono
# font (the runtime falls back to the vanilla atlas for them), so they are dropped here.
def build_merged_font(font_name, specs):
    """Builds ONE merged sbix font over every spec's raster tiles, plus the schema-v2
    sidecar rows. Stored codepoints are allocated from U+F0000 over the raster pairs
    sorted by (font_id, original_codepoint); identical art dedups to one gid."""
    # 1. Collect raster tiles across all font ids, tagged with their font id.
    raster = []   # dict(font_id, cp, cell, advance, ppem, png, sha)
    for spec in specs:
        for t in spec["tiles"]:
            if classify(t["cell"]) == "mono":
                continue     # handled by the mono font / vanilla fallback
            display_scale = t["height"] / t["ch"]
            ppem = round(DEFAULT_GLYPH_SIZE / display_scale)
            png = encode_png(t["cell"])
            raster.append(dict(font_id=spec["font_id"], cp=t["codepoint"],
                               advance=round(t["cw"] * (UNITS_PER_EM / t["ch"]) * display_scale),
                               ppem=ppem, png=png, sha=hashlib.sha256(png).hexdigest()))

    # 2. Deterministic stored-codepoint allocation over sorted (font_id, cp) pairs.
    raster.sort(key=lambda r: (r["font_id"], r["cp"]))
    stored_of = {}
    cursor = STORED_CP_START
    for r in raster:
        stored_of[(r["font_id"], r["cp"])] = cursor
        cursor += 1

    # 3. Pack-wide content dedup: sha -> glyph name (named from the first pair's stored cp).
    embed = {}          # sha -> glyph name
    raster_png = {}      # glyph name -> png bytes
    order = [".notdef"]
    for r in raster:
        stored = stored_of[(r["font_id"], r["cp"])]
        if r["sha"] in embed:
            r["gname"] = embed[r["sha"]]
        else:
            gname = "u%06X" % stored
            embed[r["sha"]] = gname
            raster_png[gname] = r["png"]
            order.append(gname)
            r["gname"] = gname
    gid_of = {gn: i for i, gn in enumerate(order)}

    f = _new_ttfont(font_name)
    glyf = newTable("glyf"); glyf.glyphs = {}
    empty = TTGlyphPen(None).glyph()
    for gn in order:
        glyf.glyphs[gn] = empty      # empty glyf; artwork lives in the sbix strike
    f["glyf"] = glyf
    f["loca"] = newTable("loca")
    f.setGlyphOrder(order); glyf.glyphOrder = order

    adv_by = {r["gname"]: r["advance"] for r in raster}
    hmtx = newTable("hmtx"); hmtx.metrics = {".notdef": (UNITS_PER_EM // 2, 0)}
    for gn in order[1:]:
        hmtx.metrics[gn] = (max(0, min(0xFFFF, adv_by.get(gn, 0))), 0)
    f["hmtx"] = hmtx

    # cmap keys on the STORED codepoint (all > U+FFFF, so format-12 only).
    cmap_dict = {stored_of[(r["font_id"], r["cp"])]: r["gname"] for r in raster}
    f["cmap"] = _cmap(cmap_dict)

    strikes = {}
    max_h = 0
    for gn, png in raster_png.items():
        # each glyph lives in the ppem of the first raster row that minted it
        ppem = next(r["ppem"] for r in raster if r["gname"] == gn)
        strikes.setdefault(ppem, {})[gn] = png
        max_h = max(max_h, Image.open(io.BytesIO(png)).size[1])
    if strikes:
        _attach_sbix(f, strikes)

    _finish_tables(f, order, max_h)

    rows = []
    for r in raster:
        stored = stored_of[(r["font_id"], r["cp"])]
        rows.append(dict(font_id=r["font_id"], codepoint=r["cp"], stored_codepoint=stored,
                         glyphName=r["gname"], gid=gid_of[r["gname"]], advance=r["advance"],
                         origin=[0, 0], strike_ppem=r["ppem"]))
    covered = {(r["font_id"], r["cp"]) for r in raster}
    for spec in specs:
        for cp, adv in spec["space"]:
            if (spec["font_id"], cp) in covered:
                continue
            rows.append(dict(font_id=spec["font_id"], codepoint=cp, stored_codepoint=None,
                             glyphName=None, gid=None, advance=adv, origin=[0, 0], strike_ppem=None))
    rows.sort(key=lambda x: (x["font_id"], x["codepoint"], x["glyphName"] or ""))
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
        dict(codepoint=0xE005, cell=cell_c(), cw=16, ch=16, height=8),            # multi, ppem 16
        dict(codepoint=0xE006, cell=cell_d(), cw=256, ch=256, height=256),        # tall art, ppem 8
    ]
    demo_space = [(0xE010, -1024), (0xE011, 96)]   # negative (-16px) ; fractional (96/64 = 1.5px)
    alt_tiles = [dict(codepoint=0xE001, cell=cell_alt(), cw=8, ch=8, height=8)]

    # ONE merged font for both font ids. "synth:alt" sorts before "synth:demo", so alt's
    # single glyph takes gid 1 and demo's glyphs follow at gids 2..6.
    merged_font, rows = build_merged_font("SynthColour", [
        dict(font_id="synth:alt", tiles=alt_tiles, space=[]),
        dict(font_id="synth:demo", tiles=demo_tiles, space=demo_space),
    ])

    edge_font = build_edge_font()

    save(merged_font, os.path.join(out_dir, "SynthColour.ttf"))
    save(edge_font, os.path.join(out_dir, "SynthColour-edge.ttf"))

    sidecar = {
        "schema_version": SCHEMA_VERSION,
        "generator_version": GENERATOR_VERSION,
        "source_date_epoch": EPOCH,
        "units_per_em": UNITS_PER_EM,
        "graphic_type": GRAPHIC_TYPE,
        "file": "SynthColour.ttf",
        "glyphs": rows,
    }
    with open(os.path.join(out_dir, "colour-glyphs.json"), "w", encoding="utf-8") as fh:
        json.dump(sidecar, fh, ensure_ascii=False, indent=2, sort_keys=True)
    print("wrote fixtures to", out_dir)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "out")

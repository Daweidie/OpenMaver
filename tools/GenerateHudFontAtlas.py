#!/usr/bin/env python3
"""Generate OpenMaver's packed font atlases using Pillow.

Latin glyphs come from tools/fonts/LucidaTypewriter.ttf (Lucida Sans
Typewriter). It has no CJK coverage, so every non-ASCII character used by the
UI (currently Chinese) is rendered with a CJK fallback font instead. The
generated PNG/metrics are the only runtime dependencies; the Latin/CJK font
files themselves are not shipped in the mod jar.

One atlas is generated per Minecraft GUI scale (1x..8x). The runtime picks the
atlas matching the current GUI scale, so a glyph texel maps to exactly one
framebuffer pixel instead of being magnified (which is what made the old 1x
atlas look blurry at GUI scale 2/3). The manual GUI scale slider stops at 4,
but "Auto" can go higher on 1440p/4K displays, hence the extra headroom.

Run this after changing any text drawn inside ``mc/*.java``:

    python3 tools/GenerateHudFontAtlas.py
"""

import math
import os
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
FONT_DIR = ROOT / "mc/resources/assets/openmaver/font"
# Kept outside mc/resources so the proprietary Lucida font file is not packaged
# into the mod jar; only the generated atlas bitmaps ship.
LATIN_FONT = Path(os.environ.get("OPENMAVER_LATIN_FONT",
                                 ROOT / "tools/fonts/LucidaTypewriter.ttf"))
SOURCE_DIR = ROOT / "mc"

FIRST_CHAR = 32
LAST_CHAR = 126
# Logical (GUI unit) cell metrics. Every atlas is CELL * scale pixels wide.
CELL = 32
COLUMNS = 16
SIZE = 10
# Horizontal padding around the glyph inside its cell. The renderer samples the
# cell with the same padding so overhanging ink is not clipped.
PAD = 2
SCALES = (1, 2, 3, 4, 5, 6, 7, 8)

CJK_CANDIDATES = (
    os.environ.get("OPENMAVER_CJK_FONT"),
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
)

STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')


def ui_characters():
    """Collect non-ASCII characters that appear in mc/ string literals."""
    characters = set()
    for path in sorted(SOURCE_DIR.glob("*.java")):
        source = path.read_text(encoding="utf-8")
        for match in STRING_LITERAL.finditer(source):
            for character in match.group(1):
                if ord(character) > LAST_CHAR:
                    characters.add(character)
    return characters


def load_latin_font(size):
    if not LATIN_FONT.is_file():
        raise SystemExit(
            "找不到拉丁字体 {}；请把 Lucida Console 的 lucon.ttf 放到该路径，"
            "或用 OPENMAVER_LATIN_FONT 指定一个 TTF 文件".format(LATIN_FONT))
    return ImageFont.truetype(str(LATIN_FONT), size)


def load_cjk_font(size):
    for candidate in CJK_CANDIDATES:
        if not candidate:
            continue
        path = Path(candidate)
        if not path.is_file():
            continue
        indexes = range(8) if path.suffix.lower() == ".ttc" else (0,)
        fallback = None
        for index in indexes:
            try:
                font = ImageFont.truetype(str(path), size, index=index)
            except OSError:
                break
            name = " ".join(font.getname())
            if fallback is None:
                fallback = font
            if "SC" in name or "Simplified" in name:
                return font
        if fallback is not None:
            return fallback
    raise SystemExit(
        "找不到中文字体；请用 OPENMAVER_CJK_FONT 指定一个 CJK TTF/TTC 文件")


def atlas_paths(scale):
    """Runtime resource names; the 1x atlas has no scale suffix."""
    if scale == 1:
        return FONT_DIR / "HudFontAtlas.png", FONT_DIR / "HudFontMetrics.txt"
    return (FONT_DIR / "HudFontAtlas@{}x.png".format(scale),
            FONT_DIR / "HudFontMetrics@{}x.txt".format(scale))


def render_atlas(scale, characters, widths1x, needs_cjk):
    """Render one atlas. ``widths1x`` stays in GUI units."""
    size = SIZE * scale
    cell = CELL * scale
    pad = PAD * scale
    latin = load_latin_font(size)
    cjk = load_cjk_font(size) if needs_cjk else None

    latin_ascent, latin_descent = latin.getmetrics()
    cjk_ascent, cjk_descent = cjk.getmetrics() if cjk is not None else (0, 0)
    max_ascent = max(latin_ascent, cjk_ascent)
    line_height = max(latin_ascent + latin_descent, cjk_ascent + cjk_descent)
    baseline = (cell - line_height) // 2 + max_ascent

    rows = (len(characters) + COLUMNS - 1) // COLUMNS
    width = COLUMNS * cell
    height = rows * cell
    atlas = Image.new("L", (width, height), 0)
    draw = ImageDraw.Draw(atlas)

    ink_top = cell
    ink_bottom = 0
    for index, character in enumerate(characters):
        font = latin if ord(character) <= LAST_CHAR else cjk
        if font is None:
            raise SystemExit("缺少中文字体，无法绘制: " + character)
        cell_x = index % COLUMNS * cell
        cell_y = index // COLUMNS * cell
        if character != " ":
            bounds = draw.textbbox((cell_x + pad, cell_y + baseline), character,
                                   font=font, anchor="ls")
            ink_top = min(ink_top, math.floor(bounds[1] - cell_y))
            ink_bottom = max(ink_bottom, math.ceil(bounds[3] - cell_y))
        draw.text((cell_x + pad, cell_y + baseline), character,
                  font=font, fill=255, anchor="ls")

    if ink_top < 0 or ink_bottom > cell:
        raise SystemExit("字形超出单元格: top={}, bottom={}".format(ink_top, ink_bottom))
    font_height = ink_bottom - ink_top

    # The vertical ink band is sampled as-is by the renderer. Its top is aligned
    # via the baseline so text keeps the same baseline at every GUI scale.
    return atlas, width, height, ink_top, font_height, baseline


def main():
    latin1x = load_latin_font(SIZE)
    characters = sorted(
        {chr(code) for code in range(FIRST_CHAR, LAST_CHAR + 1)} | ui_characters(),
        key=ord)
    needs_cjk = any(ord(character) > LAST_CHAR for character in characters)
    cjk1x = load_cjk_font(SIZE) if needs_cjk else None
    widths1x = [
        max(1, round((latin1x if ord(character) <= LAST_CHAR else cjk1x)
                     .getlength(character)))
        for character in characters
    ]

    # The baseline-to-ink-top distance of the 1x atlas is the canonical layout
    # value; the runtime keeps text on the same baseline for every scale.
    _, _, _, ink_top1x, _, baseline1x = render_atlas(
        1, characters, widths1x, needs_cjk)
    ascent1x = baseline1x - ink_top1x

    for scale in SCALES:
        atlas, width, height, ink_top, font_height, baseline = render_atlas(
            scale, characters, widths1x, needs_cjk)
        png_path, metrics_path = atlas_paths(scale)
        atlas.save(png_path, optimize=True)
        header = "{},{},{},{},{},{},{},{}".format(
            CELL * scale, COLUMNS, PAD, ink_top, font_height, baseline,
            scale, ascent1x)
        glyphs = ",".join(
            "{},{}".format(ord(character), advance)
            for character, advance in zip(characters, widths1x))
        metrics_path.write_text(
            header + "\n" + glyphs + "\n", encoding="ascii")
        print("scale={}x glyphs={} atlas={}x{} top={} height={} baseline={}".format(
            scale, len(characters), width, height, ink_top, font_height, baseline))


if __name__ == "__main__":
    main()

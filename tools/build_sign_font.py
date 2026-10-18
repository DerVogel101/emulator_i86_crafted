"""Build the OFL-licensed Monosign Bitmap sign font. Requires Pillow and ReportLab."""

import argparse
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path

from PIL import Image, ImageFont, PngImagePlugin
from reportlab.pdfbase.ttfonts import TTFontFile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/emulator_i86_crafted"
SOURCE = ASSETS / "font/monosign.ttf"
DEFINITION = ASSETS / "font/monocraft.json"
ATLAS = ASSETS / "textures/font/monosign.png"
SOURCE_HASH = "f25dc860bbade389d46c7437cb79a6ffff87f11767e0422ad346cc18dd0c635f"
COPYRIGHT = "Copyright (c) 2022, Idrees Hassan (https://github.com/IdreesInc/Monocraft)"
ADVANCE = 6
COLUMNS = 16


def build():
    assert sha256(SOURCE.read_bytes()).hexdigest() == SOURCE_HASH, "Monosign source changed"
    # Minecraft rounds the original size 7.5, oversample 1 provider to 8 FreeType pixels.
    font = ImageFont.truetype(str(SOURCE), 8, layout_engine=ImageFont.Layout.BASIC)
    codepoints = sorted(cp for cp, glyph in TTFontFile(str(SOURCE)).charToGlyph.items() if glyph)
    glyphs, spaces = {}, {}
    for cp in codepoints:
        char = chr(cp)
        mask, (left, top) = font.getmask2(char, anchor="ls")
        image = Image.frombytes("L", mask.size, bytes(mask))
        bounds = image.getbbox()
        if bounds is None:
            spaces[char] = ADVANCE
            continue
        left += bounds[0]
        top += bounds[1]
        image = image.crop(bounds)
        if image.width > ADVANCE - 1:
            image = image.resize((ADVANCE - 1, image.height), Image.Resampling.NEAREST)
        left = max(0, min(left, ADVANCE - 1 - image.width))
        glyphs[char] = (image, left, top)

    ascent = -min(top for _, _, top in glyphs.values())
    height = ascent + max(top + image.height for image, _, top in glyphs.values())
    chars = list(glyphs)
    rows = ["".join(chars[i:i + COLUMNS]).ljust(COLUMNS, "\0") for i in range(0, len(chars), COLUMNS)]
    atlas = Image.new("RGBA", (COLUMNS * ADVANCE, len(rows) * height), (255, 255, 255, 0))
    for index, (image, left, top) in enumerate(glyphs.values()):
        alpha = Image.new("L", (ADVANCE, height))
        alpha.paste(image, (left, ascent + top))
        # shortcut: alpha-1 padding fixes advance; revisit if text shaders stop discarding alpha below 0.1.
        alpha.putpixel((ADVANCE - 2, 0), max(1, alpha.getpixel((ADVANCE - 2, 0))))
        glyph = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
        glyph.putalpha(alpha)
        atlas.paste(glyph, (index % COLUMNS * ADVANCE, index // COLUMNS * height))

    metadata = PngImagePlugin.PngInfo()
    for name, value in {
        "Title": "Monosign Bitmap",
        "Copyright": COPYRIGHT,
        "Author": "Original Monocraft font: Idrees Hassan; bitmap conversion: i86 Emulator project",
        "License": "SIL Open Font License 1.1; see assets/emulator_i86_crafted/font/monosign-license.txt",
        "Source": f"Monosign, based on Monocraft v4.2.1; source TTF SHA-256 {SOURCE_HASH}",
        "Description": "8-pixel rasterization, six-pixel advance; wide glyphs fitted into five ink columns",
    }.items():
        metadata.add_text(name, value)
    png = BytesIO()
    atlas.save(png, format="PNG", pnginfo=metadata)
    definition = {"providers": [
        {"type": "space", "advances": spaces},
        {"type": "bitmap", "file": "emulator_i86_crafted:font/monosign.png",
         "height": height, "ascent": ascent, "chars": rows},
    ]}
    data = json.dumps(definition, ensure_ascii=True, indent=2) + "\n"
    verify(definition, atlas, set(codepoints))
    return data, png.getvalue(), len(codepoints)


def verify(definition, atlas, codepoints):
    space, bitmap = definition["providers"]
    rows = bitmap["chars"]
    actual = {ord(char) for row in rows for char in row if char != "\0"} | set(map(ord, space["advances"]))
    assert actual == codepoints, "Bitmap must retain every supported source character"
    assert atlas.mode == "RGBA", "RGBA selects the color-text pipeline instead of grayscale"
    assert COPYRIGHT in (ASSETS / "font/monosign-license.txt").read_text(encoding="utf-8")
    assert len({len(row) for row in rows}) == 1
    assert atlas.size == (COLUMNS * ADVANCE, len(rows) * bitmap["height"])
    alpha = atlas.getchannel("A")
    for row_index, row in enumerate(rows):
        for column, char in enumerate(row):
            if char == "\0":
                continue
            cell = alpha.crop((column * ADVANCE, row_index * bitmap["height"],
                               (column + 1) * ADVANCE, (row_index + 1) * bitmap["height"]))
            assert cell.getbbox()[2] + 1 == ADVANCE, f"Non-monospace glyph U+{ord(char):04X}"
    assert all(value == ADVANCE for value in space["advances"].values())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check generated files without modifying them")
    args = parser.parse_args()
    definition, png, count = build()
    if args.check:
        assert DEFINITION.read_text(encoding="utf-8") == definition, "Stale font definition"
        assert ATLAS.read_bytes() == png, "Stale font atlas"
    else:
        ATLAS.parent.mkdir(parents=True, exist_ok=True)
        DEFINITION.write_text(definition, encoding="utf-8")
        ATLAS.write_bytes(png)
    print(f"PASS Monosign Bitmap: {count} characters, RGBA, {ADVANCE}-pixel advance, original copyright/OFL retained")

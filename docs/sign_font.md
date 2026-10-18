# Font for mod-written signs

Use Monosign Bitmap, derived from the font supplied in `MonocraftSign-26.2.zip`,
whenever the mod writes sign text. It is bundled as a named font; installing the
original resource pack separately is not required. Minecraft's default font is unchanged.

## Assets

- Font ID: `emulator_i86_crafted:monocraft`
- Definition: [monocraft.json](../src/main/resources/assets/emulator_i86_crafted/font/monocraft.json)
- Minecraft bitmap atlas: [monosign.png](../src/main/resources/assets/emulator_i86_crafted/textures/font/monosign.png)
- Original TrueType source, also used by the Swing debugger: [monosign.ttf](../src/main/resources/assets/emulator_i86_crafted/font/monosign.ttf)
- License: [SIL OFL 1.1](../src/main/resources/assets/emulator_i86_crafted/font/monosign-license.txt)
- Attribution/modification notes: [monosign-notes.txt](../src/main/resources/assets/emulator_i86_crafted/font/monosign-notes.txt)

The bitmap uses Minecraft's original rounded raster size of 8 pixels (the supplied
TTF provider used size `7.5`, oversampling `1.0`, and shift `[0, 0]`). It retains all
1,478 supported characters with a six-pixel advance, including spaces. Wide and
overhanging glyphs are fitted into five ink columns so 15-character terminal rows
stay aligned. Glyphs retain their vertical baseline and antialiasing.
An alpha-1 padding pixel keeps narrow glyphs at the same advance; Minecraft and
Iris's text alpha cutoff discards that pixel when drawing.

The atlas is RGBA and the provider uses `bitmap` and `space`, with no TTF fallback.
This avoids Iris's broken grayscale polygon-offset text pipeline while shaders
are enabled. See [Iris #3428](https://github.com/IrisShaders/Iris/issues/3428) and
[#3251](https://github.com/IrisShaders/Iris/issues/3251).

The original TrueType font remains unchanged, with SHA-256:

```text
f25dc860bbade389d46c7437cb79a6ffff87f11767e0422ad346cc18dd0c635f
```

The source pack's `pack.mcmeta` targets Minecraft 26.2. It is not copied into the
mod: the mod supplies the named font through its normal asset resources.

Regenerate with `python tools/build_sign_font.py` (Pillow and ReportLab required).
Run `python tools/build_sign_font.py --check` to verify the source hash, character
coverage, fixed advances, RGBA output, original copyright, and generated files.

## Sign writer contract

- Decode CPU character bytes as Windows-1252, retaining the existing zero
  termination and 15-character-per-line layout.
- Select `emulator_i86_crafted:monocraft` on every text component written by the mod
  to the front and back sign faces, while retaining the mapped formatting/color.
- Apply a sign update on a server tick when the bridge reports CPU-written sign
  data to update; do not rewrite every sign unconditionally on every tick.
- Let Minecraft handle glyph availability and rendering normally. Do not reject
  character bytes with a custom ASCII-only filter.
- Do not change the font of player-written signs the mod has not updated.

Monocraft v4.2.1 is by **Idrees Hassan**, copyright (c) 2022:
[original Monocraft project](https://github.com/IdreesInc/Monocraft).
The supplied pack's Monosign is a modified version; Monosign Bitmap is a further
bitmap conversion for this mod. Neither conversion changes ownership of the
original font. The complete SIL Open Font License 1.1 and modification notes are
distributed alongside the fonts, and the PNG also embeds attribution and license
metadata. Both font variants remain under SIL OFL 1.1, separate from the mod's BSD
license.

## Implementation status

The bitmap font was checked in an isolated Minecraft 26.3 client with Iris
1.11.7, Sodium 0.9.2, and Complementary Unbound r5.9.3 with Euphoria Patches 1.10.5.
The previously corrupt sign renders correctly with shaders enabled and no missing
pipeline error. The client also checked six-pixel advances and captured colored,
bold, italic, underlined, and strikethrough Windows-1252 text. Asset checks cover
all source characters, and the packaged JAR retains the original TTF hash, author
notice, full OFL license, modification notes, and PNG attribution metadata.

The named font and attribution are bundled. The server selects it on every written line on both sign sides and decodes bytes as Windows-1252. Native world tests verify stored text, styles, font selection and dirty-update behavior.

The standalone launcher sign editor loads the same TTF. A headed packaged Swing check renders extended characters, and a real Minecraft 26.3 client receives the named font on both sides. The unobstructed live sign capture was inspected with €, umlauts and ß. Font metrics remain those supplied by the resource pack.

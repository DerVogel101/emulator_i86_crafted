# Font for mod-written signs

Use the font supplied in `MonocraftSign-26.2.zip` whenever the mod writes sign text.
The font is bundled with the mod as a named font; installing the original resource
pack separately is not required. This does not replace Minecraft's default font.

## Assets

- Font ID: `emulator_i86_crafted:monocraft`
- Definition: [monocraft.json](../src/main/resources/assets/emulator_i86_crafted/font/monocraft.json)
- TrueType file: [monosign.ttf](../src/main/resources/assets/emulator_i86_crafted/font/monosign.ttf)
- License: [SIL OFL 1.1](../src/main/resources/assets/emulator_i86_crafted/font/monosign-license.txt)
- Attribution/modification notes: [monosign-notes.txt](../src/main/resources/assets/emulator_i86_crafted/font/monosign-notes.txt)

The provider retains the supplied size `7.5`, oversampling `1.0`, and shift `[0, 0]`.
Only its resource namespace changes from `monosign` to `emulator_i86_crafted`.
The TrueType font is copied unchanged, with SHA-256:

```text
f25dc860bbade389d46c7437cb79a6ffff87f11767e0422ad346cc18dd0c635f
```

The source pack's `pack.mcmeta` targets Minecraft 26.2. It is not copied into the
mod: the mod supplies the named font through its normal asset resources. Runtime
font loading and visual appearance still need checking in the target Minecraft 26.3.

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

The bundled font is the pack's modified Monosign, based on Monocraft v4.2.1 by
Idrees Hassan. Its license and source notes are included alongside the resources.

## Implementation status

The named font and attribution are bundled. The server selects it on every written line on both sign sides and decodes bytes as Windows-1252. Native world tests verify stored text, styles, font selection and dirty-update behavior.

The standalone launcher sign editor loads the same TTF. A headed packaged Swing check renders extended characters, and a real Minecraft 26.3 client receives the named font on both sides. The unobstructed live sign capture was inspected with €, umlauts and ß. Font metrics remain those supplied by the resource pack.

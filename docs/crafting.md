# Crafting

All items have survival recipes in `data/emulator_i86_crafted/recipe/` and
ingredient-based recipe-book unlocks. The recipe IDs use the full
`emulator_i86_crafted:` namespace.

## Materials and progression

The six crafting materials are ordinary stackable items with flat pixel sprites:
Silicon Wafer, Printed Circuit Board, Memory Chip, Blank Processing Material, FPU,
and Antenna Array. They have no behavior of their own.

Blast smooth quartz for 400 ticks to make a Silicon Wafer. Circuit boards combine
copper, honeycomb, dye, and redstone. Wafers supply memory and processing parts;
these combine with the Mainframe Super Structure to build machines. The supplied
recipe quantities are retained, including two structures, two boards, two memory
chips, and four decks per craft.

Wireless endpoints and their mainframe interface modules require an Antenna Array:
GPU Arrays, GPIO Controllers, Keyboard Controllers, Punchcard Controllers,
Redstone Ports, Sign Ports, Keyboards, Punchcard Readers/Writers, and Ender Tunnel
Arrays. The Ender Tunnel recipe replaces one of its two memory chips with an
antenna. Every machine recipe uses a Super Structure as its base.

The remaining machine recipes use their corresponding vanilla parts: clocks and
amethyst for the Atomic Clock, comparators for interrupt/GPIO circuitry, glass for
the Access Terminal, barrels and memory for the Harddisk Array, furnaces and copper
for the Power Supply, buttons/levers/targets for the CPU signal ports, a sign for
the Sign Port, and observers for the Punchcard Controller.

## Alternate Speed Shard III

The regular recipe uses the supplied heavy core, dragon breath, echo shards,
netherite, nether star, and Speed Shard II. The alternate uses the Shard II upgrade
pattern (breeze rods, diamond blocks, and amethyst), with **Speed Shard II** in the
center, and produces the same `emulator_i86_crafted:speed_shard_3` item with
`emulator_i86_crafted:crude=true`.

Both recipes share the `emulator_i86_crafted:speed_shard_3` recipe group. The
alternate keeps the same name, model, and current speed behavior. It is not a
separate item or recipe-viewer subtype. The boolean is persisted and synchronized
so future CPU behavior can distinguish it.

# Original mod artwork

Generated with the built-in image-generation tool for this project. These are source atlases, not separately installed resource packs.

Machine panels use an 8-column by 16-row grid; ROM burner frames use a 4-column by 2-row grid. Frame zero is static/off; the seven powered frames are extracted as a vertical Minecraft animation. The inventory source uses four transparent cells. Mechanical cropping and nearest-neighbor resizing produce the project resources; no global Minecraft texture overrides are installed.

The final panel resources are 64 by 64 pixels, with seven powered frames and three ticks per frame. Item icons are 32 by 32. Resource preparation retained 64 pixels for panel detail even though the initial prompt described an intended 32-pixel downsample.

Outputs are in `src/main/resources/assets/emulator_i86_crafted/textures/`. Validate references, frame sizes, drops, and item definitions with `node tools/verify_resources.mjs`.

## Machine panels

Source: [machine_panels.png](machine_panels.png)

Final generation prompt:

```text
Use case: stylized-concept. Asset type: one production Minecraft machine texture animation atlas. Create ORIGINAL crisp, low-resolution pixel art for a retro 8086 mainframe mod, no text, numbers, watermarks, borders around the atlas, perspective, lighting gradients, or background gaps. EXACT layout: 8 columns and 16 rows of equal square tiles edge-to-edge, total aspect ratio 1:2, ideally 1024x2048 pixels, each tile a flat front-facing block texture. Every row is ONE distinct machine panel, repeated across its eight animation frames with its geometry perfectly aligned. Column 0 is powered OFF (dark indicators and stationary fan); columns 1 through 7 are consecutive powered ON frames, showing subtle blinking LEDs and gradually rotating fan blades or gently shifting display bars; keep the housing identical between frames. Palette: charcoal and slate metal, warm beige front panels, copper screws, muted green and amber indicators; purple only for the memory tunnel. Cohesive vanilla-compatible pixel art, pixel-grid shapes, flat faces, broad readable features; these tiles will be downsampled to 32x32 in game. Rows from TOP to BOTTOM: 1 CPU mainframe controller with square dark green monitor and tiny status lights; 2 atomic clock with tiny wave display and timing LEDs; 3 interrupt controller with four rows of LEDs; 4 access terminal with large dark green terminal screen; 5 GPU array with grid of small chips and amber scanning lights; 6 GPIO controller with red and black sockets; 7 harddisk array with three small disk drawers and activity LEDs; 8 Ender memory tunnel with centered purple square coil aperture; 9 integrated NVRAM storage with three chips and green LED; 10 power supply with a prominent circular cooling fan and small green light; 11 RESET port with large red push button and small LED; 12 NMI port with amber push button and small LED; 13 TEST port with blue indicator and recessed socket; 14 structural filler with metal vents and corner screws; 15 external redstone port with red rectangular socket and red indicators; 16 external sign port with a tiny beige text display and green indicators. Critical invariant: maintain precisely EIGHT tiles per row and SIXTEEN rows; absolutely no labels or extraneous content. The whole image is one texture atlas asset.
```

## ROM burner

Source: [rom_burner.png](rom_burner.png)

Final generation prompt:

```text
Use case: stylized-concept. Asset type: one Minecraft ROM burner animation sprite atlas. Original crisp low-resolution pixel art, no lettering or numbers, front-facing flat tile only. EXACT layout four equal square tiles per row and two rows, eight frames total, no padding, no border around the image, aspect ratio 2:1. Reading left to right top to bottom, tile 0 is powered OFF; tiles 1-7 are consecutive powered ON frames. All frames have identical housing and perfectly aligned geometry: charcoal steel edges, warm beige front panel, four small copper corner screws, a prominent horizontal black ROM chip insertion slot in the middle, a recessed dark amber mini display above it and two green/amber activity indicators below. A tiny fan grille at lower left. OFF state has all lights/displays dark; ON states blink lights, scan a simple amber bar and turn a small fan. Vanilla-compatible pixel art with broad readable shapes; tiles will be resized to 64x64. Consistent worn retro 8086 mainframe hardware. No text, watermarks, gradients, perspective, shadows outside a tile, or unrelated objects.
```

## Inventory icons

Source: [inventory_items.png](inventory_items.png)

Final generation prompt:

```text
Use case: stylized-concept. Asset type: one Minecraft inventory item sprite sheet with a genuine TRANSPARENT background. Original crisp 32x32-style pixel art, flat inventory icons, precisely a 2 by 2 grid of four equally sized isolated icons centered in their square cells, no labels or lettering, no background, no shadows. Top left: an 8086-style black ROM DIP chip with a beige small label and silver pins. Top right: a copper-mounted small green clock crystal speed shard, one green crystal. Bottom left: a silver-mounted blue clock crystal speed shard, slightly faceted blue crystal. Bottom right: a gold-mounted purple clock crystal speed shard, purple faceted crystal. Cohesive vanilla Minecraft pixel art, strong dark outlines, limited palette, hard pixel edges, each icon fills approximately 75 percent of its cell with transparent margin. No text, numbers, watermarks, extra icons, pedestals or panels.
```

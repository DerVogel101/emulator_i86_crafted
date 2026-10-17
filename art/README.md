# Mainframe artwork

Original cuboid models and explicitly drawn pixel materials, exported by
[`tools/build_art.py`](../tools/build_art.py). No image-generation service or
Applied Energistics 2 assets are used. The old generated atlases are removed.

The design uses graphite housings, recessed panels, narrow metal rails, copper
contacts and restrained colored indicators. Each device has its own physical
hardware: displays, drawers, heatsinks, chip packages, sockets or push buttons.
Side and rear service panels are drawn onto a closed casing. The outer edges
reach the block boundary, including where Minecraft hides neighboring ground.
Frame bolts share one texture surface, and exposed coplanar faces are rejected
by the geometry checker.

Actual Minecraft captures: [powered](blocks-on.png), [off](blocks-off.png),
and [inventory items](items.png).

Corrections: [ground contact](ground-contact.png),
[controller and terminal](controller-terminal.png),
[standing-sign socket](sign-up.png), [hanging anchors](sign-down.png),
and [wall-sign contacts](sign-wall.png).

All seventeen block items use their actual 3D block geometry. ROM chips have
pins and a PCB, with a different label when programmed. Speed shards have
faceted crystals and one, two or three physical tier marks.

## States

| Indicator | Meaning |
| --- | --- |
| Narrow edge and lower power strip | Device powered; travelling light bands interpolate over a 2.4 second loop |
| Controller small lower lens | CPU powered and not paused |
| PSU small lower lens | Incoming redstone, including when startup cannot succeed |
| RESET / NMI / TEST small lower lens | Corresponding physical redstone pin is high |
| Redstone arrow towards socket, cyan | Input |
| Redstone arrow away from socket, amber | Output |
| External port small lower lens | Physical input/output signal is nonzero, including a retained output while the CPU is off |
| Sign port small lower lens | Text synchronized or sign clicked within the last six game ticks |
| ROM burner lights and display | Upload/burn session is active, using the existing burner timeout |

Off-state glass and lenses are dark. Hardware that does not expose activity
counters shows its power state; storage lights do not invent disk traffic.
Illuminated model elements use vanilla light emission, and only the small
indicator/display textures animate. The casing remains stationary.

The controller has a raised processor package with gold contacts and an animated
circuit core. The access terminal has a substantially larger text display and a
keyboard. Their shapes and display animations are deliberately different.

The sign port has a separate attachment face and an accessible status display.
Horizontal faces use wall-sign contacts; facing up uses a centered socket for a
standing-sign pole; facing down uses two anchors for hanging-sign chains.
Power rails and activity traces surround the selected attachment. Horizontal
status displays face away from the attached sign; vertical mounts keep them
upright on the side of the block.

## Editing and checking

Requires Python with Pillow to export; the mod itself has no new dependencies.

```text
python tools/build_art.py
node tools/verify_resources.mjs
python tools/verify_art_geometry.py
```

The committed PNGs are material swatches and explicitly drawn pixel displays.
The exporter is deterministic. Model geometry, all facings, state combinations,
texture references, animation dimensions, item models and portable drops are
checked by the resource and geometry verifiers. Native tests cover the server state signals;
the dedicated artwork client test captures the actual Minecraft renderer.

# Mainframe artwork

The implemented indicator layout, activity assignments and timing are recorded
in the [LED specification](../docs/led_indicators.md).

Close-ups of the current strip: [HDD](led-hdd.png), [sign port](led-sign.png),
[redstone port](led-redstone.png), and [failure status](led-failure.png).

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
| Narrow edge rails | Device powered; travelling light bands interpolate over a 2.4 second loop |
| Lower indicator strip | Four adjacent 0.8 by 0.8 segments: common status, then three device-specific activities; aligned right except on the Sign Port's centered attachment strip |
| First strip segment | Running green, powered idle/paused amber, pure red failure, off black/dark gray; lit segments use solid, saturated colors |
| Controller second segment | Unused and dark; final two segments show guest I/O reads and writes |
| RESET / NMI / TEST second segment | Corresponding physical redstone pin is high, including before boot |
| Redstone arrow towards socket, cyan | Input |
| Redstone arrow away from socket, amber | Output |
| Redstone port activity segments | CPU input read, CPU output write, and redstone strength change |
| Sign port activity segments | CPU read, CPU write, and attached sign clicked; writes do not impersonate clicks |
| ROM burner lights and display | Upload/burn session is active, using the existing burner timeout |

Off-state glass and lenses are dark. Storage activity is collected from real
accesses, including unchanged writes. Consecutive busy ticks keep a segment lit;
an inactive sample starts a 75 ms fade. Structural Filler has no LEDs or glowing
rails. Activity faces use a batched emissive client pass; physical pin levels use
steady emissive model faces. The casing remains stationary.

The controller has a raised processor package with gold contacts and an animated
circuit core. The access terminal has a substantially larger text display and a
keyboard. Their shapes and display animations are deliberately different.

The sign port has one attachment face and plain casing on the other faces.
Horizontal faces use wall-sign contacts; facing up uses a centered socket for a
standing-sign pole; facing down uses two anchors for hanging-sign chains.
Power rails surround the selected attachment. The full four-segment indicator
strip sits centered below the lower light bar on that same face, including on
the top and bottom mounts. The former display, socket and front rails are removed.
See the [connector close-up](led-sign.png) and [plain casing](sign-casing.png).
Its inventory icon faces the connector so the port remains recognizable.

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

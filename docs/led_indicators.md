# Machine LED indicators

The models, CPU telemetry, server synchronization and client rendering implement
this indicator design. See [the artwork guide](../art/README.md) for the assets.

## Confirmed layout and behavior

- Each machine and port has four independently controlled segments in its horizontal strip.
  Each LED measures 0.8 by 0.8 model pixels. The four LEDs are adjacent,
  without gaps, in one strip aligned to the right at the old strip position.
  The Sign Port instead centers its strip at the bottom of the sign-connected
  face, below the lower attachment light bar. The strip spans 3.2 model pixels.
- Structural Filler has no LEDs, including no illuminated indicator strips.
- LED 1 reports running, failure, or off; off uses a black/dark gray lens.
- The Mainframe Controller's former instruction-execution indicator (LED 2) is
  unused and stays dark. Its physical LED position remains present.
- The Sign Port's fourth LED reports a click on the attached sign (either side),
  rather than another read/write or sign-update indication.
- Activity in consecutive ticks keeps an LED continuously lit. An inactive tick
  starts the fade; renewed activity cancels the fade without a forced dark frame.
  Do not impose a half-tick cutoff between consecutive active ticks.
- Reads and writes count as accesses even when the data does not change. Track
  actual events rather than substituting an idle animation.

## Presentation

Use one continuous right-aligned strip with four 0.8-model-pixel segments.
The Sign Port centers this strip below the attachment light bars and has no
separate display front. The strip follows wall, standing and hanging sign mounts.
Use green for running, red for failure, and black/dark gray for off. Amber can
distinguish powered-but-paused or waiting status. A latched failure takes
precedence over off after a fault shuts down the machine.

Use cyan for read/input activity and amber for write/output activity where those
roles apply; use violet for auxiliary activity. Conditions such as a high input
or connected viewer remain steady while true instead of generating fake pulses.
Lit segments use solid, saturated colors at full emissive light: vivid green,
cyan, amber and violet, with pure red (255, 0, 0) for failure.

## Activity assignments

Positions are numbered from left to right when looking at the front. Each GPIO
or GPU array reports its own 32-channel range. Physical RESET/NMI/TEST input
segments reuse the existing pin-level state and remain available before boot.

| Machine | LED 1 | LED 2 | LED 3 | LED 4 |
| --- | --- | --- | --- | --- |
| Mainframe Controller | Status | Unused/dark | I/O read | I/O write |
| Atomic Clock | Status | Clock/timer read | Timer configuration written | Timer expired |
| Interrupt Controller | Status | IRQ queued | IRQ accepted by CPU | IRQ acknowledged |
| Access Terminal | Status | CPU output received | Terminal viewer connected | Exception reported |
| GPU Array | Status | Sign memory read | Sign memory written | Transfer between GPU and world signs |
| GPIO Controller | Status | Input register read | Output register written | Input IRQ generated |
| Harddisk Array | Status | Disk read | Disk write | Disk image saved |
| Ender Tunnel Array | Status | Shared memory read | Shared memory written | Another CPU accessed the shared region |
| Integrated Storage | Status | NVRAM read | NVRAM written | World save completed successfully |
| Power Supply | Status | Redstone power input high | Power-on event | Power-off event |
| Reset Port | Status | Reset input high | Reset requested | Reset completed |
| NMI Port | Status | NMI input high | NMI queued | NMI accepted by CPU |
| TEST Port | Status | TEST input high | CPU waiting on TEST | WAIT resumed |
| Redstone Port | Status | CPU read (input mode) | CPU write (output mode) | Redstone strength changed |
| Sign Port | Status | CPU read | CPU write | Attached sign clicked |
| ROM Burner | Status | Upload receiving | ROM validation | Chip programmed |
| Structural Filler | None | None | None | None |

## Persistence indication

The existing `ServerMachines` world-save hook calls `save()`, which captures each
loaded machine and then writes the retained mainframe records. `MainframeMachine`
captures NVRAM as part of that snapshot. NVRAM therefore already participates in
world saves; LED 4 indicates successful persistence, not a new guest access
or a new save operation. Signal completion only after persistence succeeds.

## Runtime and rendering

The CPU worker records compact activity bits and publishes copied snapshots at
tick boundaries. Server-side debugger inspection and output polling do not count
as guest reads. The server publishes the latest completed snapshot at the end of
its tick without waiting for the worker. It batches changes for players tracking
the relevant chunks, including initial state for newly tracking players and
removal when a device leaves their tracked area.

Client extraction builds immutable camera-relative LED faces. One emissive
geometry submission draws the activity/status faces; no blockstate changes or
world-light updates are made for individual accesses. LEDs remain fully on until
an inactive sample, then fade over 75 ms. Further activity cancels the fade.
The physical pin segment is a steady emissive model face, without an animated
texture. Decorative power rails and screens retain their existing smooth effects.

LED textures are 16 by 16 texels so they preserve Minecraft's atlas mipmap levels.
Their geometry remains exactly 0.8 by 0.8 model pixels. Each lit dynamic segment
uses one solid-color quad. Physical pin LEDs use the same saturated cyan.
Telemetry is transient and does not change the saved-world or CPU-state formats.

## Verification and performance limits

Runtime checks cover guest I/O, unchanged disk/memory writes, host-read exclusion,
per-channel routing, timer expiry without IRQs, reset/NMI/IRQ/WAIT events, terminal
output, immutable snapshots, continuous illumination, and fade cancellation.
Native world tests cover bounded packet round trips, failure/off precedence,
save-completion pulses, sign-click separation and blockstate stability, controller
status and the surrounding machine/port behavior. The artwork suite sends known
indicator states through the real client network and rendering paths; its gallery
states are controlled fixtures, not evidence of live CPU I/O by themselves.

A local JDK 25 microbenchmark used nine paired million-instruction trials with
tracking disabled/enabled and a snapshot per 1,000 instructions. Median paired
overhead was 2.84% for I/O, 0.71% for NVRAM, and 0.19% for shared-memory loops.
Individual paired runs ranged roughly from -4% to +6%, so small differences are
within noise. This measures incremental CPU telemetry, not whole-game FPS/TPS,
server scalability, or shader compatibility. Sodium/Iris were not tested.

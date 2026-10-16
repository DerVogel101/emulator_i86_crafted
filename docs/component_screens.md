# Component screens

The mod uses its own client `Screen` implementations and authenticated Fabric packets. It does not use Minecraft's datapack/custom-dialog UI system or custom-click action packets.

All registered blocks and their items use the same display-name keys. Items live in the **i86 Mainframes** creative tab, with a ROM chip icon.

## Appearance and controls

The screens use a dark equipment-rack frame, recessed displays, status accents, hardware icons and custom buttons. The Access Terminal uses green output text; the IRQ manager and ROM programmer use amber accents. Port settings expose readable IRQ choices instead of raw enum names. Narrow windows switch to one column with scrolling; text fields retain their values when resizing. Tab navigation scrolls focused controls into view.

- **Controller:** overview, installed hardware, ID/name, rename and **Open debugger**. **ROM / speed shard** opens a dedicated installation bay, with one ROM slot and one shard slot above the player's inventory. No unused chest slots are displayed. Shift-click routes each accepted item to its correct slot. Installation and removal require power off and debugger control when a lease exists.
- **Ender Tunnel Array:** selects a 1–32 character, case-insensitive shared-memory ID while powered off.
- **Harddisk Array:** shows disk identity and mainframe status, including duplicate-device faults.
- **Access Terminal:** pages the retained 16,384-character history, newest first. It exposes output/status without requesting CPU registers or memory. Latest output refreshes every five server ticks, with packets sent only when the view changes. Updates preserve the open screen and manual scroll position, following new output when already at the bottom. Older pages remain still for reading; **Latest / refresh** returns to the live view. Closing the screen stops updates.
- **Interrupt Controller:** lists assigned redstone/sign ports, including unloaded ports, with slot, name, coordinates and online/conflict state. Per-device settings select analog/edge IRQs or front/back sign-click IRQs.
- **External ports:** mainframe ID, slot, friendly name and input/output or sign-click settings. Binding/slot/direction changes require power off; name/IRQ edits preserve input baselines and cached sign output.
- **Power Supply, Reset Port and NMI Port:** show their purpose and provide guarded power-cycle, CPU-reset or NMI controls.
- **ROM Burner:** programmer view with upload name, multiline description, library navigation and chip burning. The desktop file picker and external debugger remain Swing windows as specified.
- Other components show their own controls and status. Only the controller lists
  present/missing components and detailed assembly errors; unassembled component
  screens report **Invalid assembly** without repeating those lists.

The controller also reports **Structure: VALID/INVALID**. Invalid reports list every missing required component, each excess count with its maximum and component coordinates, holes/unsupported block types, wrong facing and chunk-boundary/overlap problems. The checked 7 × 3 area and dimension are shown. For an unfinished wall, that area is inferred from the most existing wall blocks; missing components can be placed anywhere within it. Refresh recomputes the report, and repairing the wall clears old structural errors.

## Server authority

The server owns all settings and execution. Screen packets carry bounded fields/options/actions and a one-use player nonce. Submissions recheck proximity, dimension, surviving block/entity, mainframe identity, permission, power and debugger ownership. Closing an old screen cannot invalidate a newer screen's nonce. Closing the burner invalidates its current menu session.

The inventory menu uses ordinary server container synchronization, with custom client rendering. Slot insertion, extraction and shift-click recheck power and debugger ownership on the server, including leases acquired after the bay opens.

Inspecting a screen does not acquire a debugger lease. External debugger windows preserve the focus-pause option and prevent automatic focus-loss pausing while open; see [remote_debugger.md](remote_debugger.md).

Tests and screenshot evidence are recorded in [native_tests.md](native_tests.md).

Controller installation inventories save explicit slot indices, including an empty
ROM slot beside an installed speed shard. Loading legacy inventories repairs a
shard shifted into the ROM slot; carried controller items preserve the same slot
roles. Redstone input and output slot IDs are independent, so input 0 and output 0
may be bound together while duplicate inputs or duplicate outputs remain invalid.

Speed shard tooltips show configured **Steps per tick** and **Batches per tick**
for their tier. The active server sends all three tier settings when a player
connects; disconnecting clears those values so another server cannot inherit stale
tooltips. Outside a connection the standard tier defaults are shown.

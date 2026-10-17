# Confirmed mod behavior

This document records the agreed requirements. It is a specification, not a claim
that these features are already implemented. See [todo.md](todo.md) for the feature
list and [open_questions.md](open_questions.md) for specification status and
remaining implementation/validation work.

The existing emulator contracts are the baseline:

- [CPU execution and ROM](../../i8086Emulator/docs/cpu_execution.md)
- [I/O ports and device IRQs](../../i8086Emulator/docs/io_mapping.md)
- [Physical memory](../../i8086Emulator/docs/memory_mapping.md)

These links refer to the sibling emulator checkout. The mod-specific requirements
below take precedence where they extend that baseline. In particular, Windows-1252
and complete execution-state persistence are requirements; the existing documents
do not yet describe those capabilities fully.

## Mainframe and multiblock

- Each mainframe contains exactly one CPU.
- The mainframe is a 7 x 3 x 1 wall entirely inside one chunk.
- Components can occupy any position in the wall. All components face the same
  direction, forming the mainframe's front face. Super Structure fills unused positions.
- The counts in the feature list are maximum counts, not required counts.
- Components marked Optional may be absent. Unmarked components are required;
  at least one GPIO Controller is required.
- Each installed component contributes its listed functionality and capacity.
- The controller reports structural failures with block coordinates and all missing/excess component counts, including actual counts and allowed maxima. It identifies unsupported blocks, holes, facing mismatches, chunk crossings and overlapping valid walls. For incomplete walls, it shows the closest matching area used for diagnosis; component positions remain unrestricted within the wall.

| Component | Maximum | Optional | Function or capacity |
| --- | ---: | :---: | --- |
| Mainframe Controller | 1 | No | One CPU, identity, ROM/crystal slots, debugger access |
| Atomic Clock | 1 | Yes | Timers and mapped host clock |
| Interrupt Controller | 1 | Yes | Device IRQ queue and device-management GUI |
| Access Terminal | 1 | Yes | CPU output/log and exceptions |
| GPU Array | 4 | Yes | 32 sign ports per array |
| GPIO Controller | 4 | No | 32 redstone ports per controller; minimum one controller |
| Harddisk Array | 1 | Yes | Persistent I/O storage |
| Ender Tunnel Array | 1 | Yes | Shared-memory connection |
| Integrated Storage | 1 | Yes | NVRAM bound to the mainframe ID |
| Power Supply | 1 | No | Redstone-controlled mainframe power |
| Reset Port | 1 | No | RESET from a redstone pulse |
| NMI Port | 1 | No | NMI from a redstone pulse |
| TEST Port | 1 | No | TEST pin follows redstone power |

- Removing a component while running powers the mainframe off, using the power-off
  part of a power cycle. Once valid again, it restarts automatically if its PSU
  redstone input is powered; a valid mainframe follows that input without a
  separate manual restart requirement.
- The power supply must remain redstone powered for the mainframe to stay on.
- Loaded external ports use the I/O state currently held in the bridge, including
  while the mainframe is off or unloaded. There is no separate port-side forced
  zero rule; any change caused by CPU reset/power-cycle semantics is reflected
  through the bridge. Unloaded external ports do not interact with the world.
- Power Supply, Reset Port, and NMI Port also provide manual GUI controls.
- CPU Reset and Power Cycle follow the corresponding selected-CPU operations in
  the launcher. CPU Reset preserves memory and connected-device state. Power Cycle
  reloads the ROM and persisted nonvolatile images; shared-region lifetime is a
  separate decision because several mainframes can use the same region.
- Absent optional hardware provides no functioning device. For example, absent
  timers read as zero and do not run. Without Integrated Storage or an Ender
  Tunnel Array, the corresponding memory view reads entirely as zero and ignores
  writes. This does not erase the retained NVRAM image or a saved shared region.
- Without an Access Terminal, terminal output is discarded rather than buffered
  for a later terminal installation. Fault/status notifications still reach debuggers.
- Without a Harddisk Array, use the existing disconnected I/O-storage device
  contract. Installing it reconnects its retained image while the CPU is off.
- The Interrupt Controller manages maskable device IRQs only. NMI works without
  it; removing device IRQ support does not disable the CPU's NMI input.
- Installing hardware or attaching a new device/storage requires the mainframe to
  be powered off; debugger pause alone is not sufficient. Removal while running
  still powers the mainframe off. Adding hardware is completed while off, retained
  storage is attached/restored, and availability is established before automatic
  startup follows the PSU redstone input. Installation is not deferred into a
  running CPU. An already-bound external port loading again is reconnection,
  not installation of new hardware, and does not require powering off.

## CPU scheduling and tick bridge

- The CPU runs on a worker thread. The host bridge connects it to server ticks.
- At the beginning of a server tick, Minecraft-side input state is submitted to
  the bridge. Changed inputs are applied on the CPU worker between `step()` calls,
  at instruction boundaries, following the existing bridge contract.
- Changed CPU output I/O is applied to the Minecraft world during the next server
  tick. World changes occur on the server thread.
- A Clock Crystal sets the number of `step()` calls per game tick, as in the launcher;
  it does not count only completed instructions.
- The three crystal tiers are configurable, with defaults of 1,000, 5,000, and
  10,000 steps per tick.
- Each speed shard (the Clock Crystal item) also configures subdivision of its
  tick budget into smaller, time-spaced batches. Unused work does not carry into
  the next tick. See [cpu_scheduling.md](cpu_scheduling.md).
- A custom yield instruction lets a ROM give up its remaining batch or remaining
  tick according to an argument. Its opcode must have no documented original-8086
  purpose; coprocessor opcodes remain unsupported and must fail explicitly. The
  confirmed encoding is `0F 00` for batch yield and `0F 01` for tick yield. The
  standalone launcher must be adjusted along with the core and mod scheduler;
  see [yield_instruction.md](yield_instruction.md).
- The base configuration uses ten batches per tick for each speed-shard tier.
- Without a crystal, the mainframe cannot execute. Crystals can be changed only
  while the mainframe is powered off.
- When the mainframe chunk unloads, no further `step()` calls are executed. Its CPU
  stops ticking; unloading is not an explicit CPU reset or power cycle.
- Automatic CPU work and external-port world I/O also suspend outside the chunk's
  block-ticking range, before final unloading. Host world reads must not renew
  chunk-loading tickets and keep their own chunks loaded. Suspension preserves
  logical power and shared-memory membership. Inputs resample silently on return;
  outputs assume cached bridge state.
- Reloading does not synthesize interrupts for world changes missed while unloaded.
- Timer time is independent of CPU instruction progress. Real-time timers count
  elapsed real time while paused/unloaded and expire immediately when an overdue
  timer is next serviced. Tick timers do the same using elapsed server/world
  ticks, not completed CPU steps. Yielding a batch or tick does not freeze either
  clock. Existing timer reload/repeat and IRQ-queue semantics remain the baseline.
- A closed/stopped server produces no game ticks, so tick timers do not count
  imaginary offline ticks. Real-time timer deadlines continue to age in real time;
  save/restore must preserve enough timing state for overdue expiry on resumption.
- Missing Atomic Clock hardware remains inactive/zero, and power cycling still
  resets devices according to the launcher contract. Elapsed-time continuation is
  not a request to preserve timers through an explicit power cycle.

## Identity and external ports

- Mainframe IDs are unique within a world and allocated from an incrementing
  world-level counter, similar to map IDs.
- The controller item's tooltip displays its mainframe ID.
- Controller friendly names may repeat. Display the mainframe ID alongside its
  friendly name in controller UIs, debugger views, and mainframe selectors so
  identical names remain distinguishable.
- Mining a controller drops an item retaining its identity and stored controller
  data, like a shulker box. Placing that item restores the same identity and data.
- Multiple placed controllers with the same ID are not allowed to operate. Disable
  the conflicting controllers and show the other controller's coordinates and
  dimension in their controller UIs until the duplicate placement is resolved.
- Port friendly names are unrestricted and are not unique identifiers.
- External ports select their mainframe and numbered slot in their block GUI.
- Duplicate slot assignments for the same mainframe, device type, and redstone direction are invalid. Redstone input and output IDs have separate namespaces: input 0 and output 0 may coexist. Sign IDs share one namespace regardless of direction settings.
  Disable world reads and writes for all conflicting ports and report the conflict
  in the Access Terminal and Interrupt Controller's device manager. They do not
  produce world-input IRQs while disabled. Keep their bindings for correction.
- External ports can connect over arbitrary distances within the same dimension.
  They cannot connect directly across dimensions. Cross-dimensional CPU communication
  uses shared memory instead.
- Each GPIO Controller enables another 32 redstone input slots and 32 redstone output slots; each GPU Array enables
  another 32 sign slots. With `n` installed modules, valid slot IDs for that device
  type are `0` through `32 * n - 1`, up to `127` with four modules.
- If capacity shrinks below a linked port's slot ID, that port becomes offline but
  retains its mainframe/slot binding so it can reconnect when capacity is restored.
- Ports retain their binding when their controller is absent or destroyed. The
  original controller can be replaced, or the port can be explicitly relinked.
- Unloaded ports do not interact with the world or send interrupts. The mainframe
  retains their last I/O state and remembers their coordinates for display.
- When an already-bound input port loads or reconnects, sample its current world
  input and replace the retained input value silently. Do not synthesize IRQs for
  the difference from the old value. Later observed changes use the configured
  interrupt mode normally.
- Ports face their interaction target, like a dispenser.

## Redstone and signs

- Redstone ports are configured as input or output in their GUI. Input strength
- Redstone dust connects only to the selected target/output face, for all six port orientations. Other faces neither connect nor emit redstone output.
  uses Minecraft's full `0` to `15` range.
- Interrupts can be enabled through the port GUI and IRQ interface.
- Binary interrupt modes treat `0` as low and nonzero levels as high: HIGH triggers
  on low-to-high, LOW on high-to-low, and BOTH on either transition.
- Analog interrupt mode triggers on every change of redstone strength, including
  changes between nonzero levels. An unchanged level does not repeatedly trigger.
- Sign ports interact with the sign they face. All sign variants are supported,
  using front and back data even where the back is not visible.
- Sign-click IRQ configuration selects front and/or back clicks. Click state uses
  the existing I/O contract: front sets the general/front flags, back sets the
  general/back flags, and the CPU acknowledges them as documented there.
- If no usable sign target is present, the port performs no world interaction.
- CPU-written display signs are waxed and existing editor authorization is cleared.
  Clicking either side does not open the vanilla sign editor; configured front/back
  click interrupts still work. The lock is saved as vanilla wax state and remains
  after a port is disconnected.
- CPU `OUT` writes to sign data drive dirty updates on the next server tick.
  Writing an unchanged byte does not schedule an update, and multiple changes can
  coalesce into one tick update. Changes made by commands or other code are
  overwritten by the next CPU-driven update; unchanged signs are not rewritten
  on every tick.
- Sign character bytes and the Access Terminal must support Windows-1252, including
  its extended character range, rather than ASCII only. Sign lines retain the
  existing layout: one format byte followed by 15 character bytes, with zero
  termination/padding. Decode character bytes as Windows-1252 without a custom
  character whitelist. Minecraft handles the resulting text, unsupported glyphs,
  and font rendering normally; there is no custom unsupported-character rejection.
  Zero retains its existing end-of-text/padding meaning. World text is not
  imported into the CPU buffer, so they require no Unicode-to-byte conversion.
- Whenever the mod writes sign text, select the bundled named font
  `emulator_i86_crafted:monocraft` on the written text on both sign sides. This uses
  the supplied Monosign font without replacing Minecraft's default font or changing
  unrelated sign text. See [sign_font.md](sign_font.md) for the assets and provenance.

## Persistence and storage

- Saving and reopening the world preserves all state needed to continue execution
  as if execution had been suspended, not rebooted.
- This includes CPU registers/flags, execution and halt/fault state, volatile RAM,
  NVRAM, ROM references, device I/O, timer state, IRQ queue, pending bridge inputs
  and outputs, pin state, controller identity, port bindings/settings, and storage
  contents. Shared-memory groups require a consistent saved state across their CPUs.
- Saving needs a coherent execution boundary; it must not mix partially updated
  worker state with unrelated server-tick state.
- Integrated Storage contents are bound to the mainframe ID and retained when the
  controller or storage component is moved or absent.
- Harddisk Array storage belongs to its block and is retained in its dropped item,
  like a shulker box, so mining and placing it preserves its data.
- A disk identity must not be active on multiple CPUs at the same time. If a
  copied/recovered disk is attached or activated while that identity is already
  active elsewhere, warn the player, stop/refuse startup of the CPU attempting
  the duplicate attachment, and identify the active copy's coordinates and
  dimension. Report the conflict through the controller and installed terminal;
  do not allow the conflicting attachment to read or write the disk. Preserve its
  identity/data for correction rather than silently creating a blank replacement.
- Retain world-side recovery records for controller data, ID-bound NVRAM, disk
  images/identities, ROM references, and recorded item contents. Losing or
  destroying the physical item does not automatically delete its recovery record.
  Recovery restores the latest successfully persisted data, not writes lost in a
  crash before persistence. Disks need stable identities to support this registry.
- The emulator's existing dirty-snapshot acknowledgement rule still applies:
  acknowledge nonvolatile data as saved only after persistence succeeds.
- Storage file layout remains an implementation detail. Controller/disk recovery
  records are retained for admin recovery. Shared regions are retained until
  explicitly deleted by an admin.
  Complete CPU save/restore is implemented and checked; see [implementation.md](implementation.md).

## Admin recovery commands

- Provide server-admin commands for listing saved identities/data and recreating
  controller, disk, and ROM-chip items from their retained records.
- Listings should identify records by stable ID, friendly name where available,
  placement coordinates/dimension where recorded, and whether required data exists.
- Recovery preserves the original identity, ROM/storage references, and recorded
  contents; it must not silently create an unrelated new identity or blank image.
- Apply the same duplicate-controller protections to recovered items. Reject
  controller recovery while that ID is recorded as still placed, and report its
  location rather than creating a second usable controller.
- Server console must be able to recover items for a specified online player.
  Restrict these operations to admins; normal players cannot enumerate/recover
  other mainframes through this command surface.
- A minimal proposed command surface is:
  - `/i86 admin list <controllers|disks|roms|shared-memory> [query]`
  - `/i86 admin recover <controller|disk|rom-chip> <id> [player]`
- Exact implemented commands and permission rules are listed in [admin_recovery.md](admin_recovery.md). The command surface above describes the required operations rather than their final spelling.

## ROM burner and ROM chips

- The player selects a local ROM image through the ROM Burner workflow.
- Create ROM opens a draft with Upload, Name, Description, Cancel and Done.
  Upload stages the file; Done saves it with the current metadata. Cancel or
  closing the menu discards the draft without creating a library entry.
- The client uploads the image to the server. The server verifies that it is
  exactly 256 KiB before saving it in the world folder.
- Each player can start one upload every 30 real seconds across all burners.
  Cancellation, invalid transfers and duplicates consume the slot. Reconnecting
  does not reset the cooldown; restarting the server does.
- Saved images record the uploader's server-authenticated UUID and name at upload
  time. The library displays the uploader's name; duplicate uploads preserve the
  first uploader. Library index v2 has no migration support for older formats.
- A ROM chip stores a reference to the server's saved ROM image, not a client-local
  filesystem path. It must be inserted into the controller to be used.
- Saved ROM images are immutable. Multiple chips may reference the same image.
  Once burned, a chip keeps its image reference permanently; another ROM requires
  another chip.
- The server maintains a content-hash-to-file index. An upload identical to an
  existing ROM is rejected as a duplicate. The first uploaded image and its
  original name/description win; another upload cannot overwrite them. Identify
  the existing library entry so it can be selected without uploading again.
  Multiple chips can still reference that existing file. New image files use a
  UUIDv7 filename. Hash algorithm and index serialization are implementation
  details; deduplication must compare ROM content, not the user-provided name.
- Uploads allow an in-game ROM name and description. The ROM library lists all
  saved images and their metadata for browsing and selecting existing ROMs.
- ROM chips may be inserted, removed, or exchanged only while the mainframe is off.
- If its referenced file is missing, the chip is marked invalid and cannot supply
  a usable ROM. If a running mainframe's referenced ROM disappears or becomes
  unusable, stop execution and power that mainframe off. Show an error in its
  controller and installed Access Terminal, and send the normal debugger fault/
  status notification. Keep startup blocked until a valid ROM is available so the
  powered PSU cannot cause a restart loop. ROM files are not garbage-collected
  when a referencing chip is removed or destroyed.

## External debugger

- Keep the external Swing launcher/debugger window on the player's client.
- The server owns the mainframe CPU; the client window observes and controls that
  CPU through the mod connection rather than creating a separate CPU simulation.
- Multiple CPU viewing remains supported.
- For each mainframe, only the first player to open its debugger may pause, step,
  reset, or edit it. Other players can observe read-only while it is being debugged.
- Control releases when that player closes the debugger or disconnects. It passes
  to the next player in debugger-opening order who still has that debugger open.
  If nobody remains, the next opener obtains control.
- While a debugger control lease exists, it also restricts changes in controller/
  component/port GUIs and manual buttons to that player. Other players may inspect
  read-only views. With no debugger lease, normal block interaction rules apply.
- Full CPU/debugger state is transmitted when paused from the GUI or when stepping,
  not continuously during normal execution.
- Running mainframes still send fault and run-status notifications.
- Launcher operations that bypass mod-owned ROM/storage management are disabled;
  in particular, the debugger cannot load ROM files directly into a mainframe CPU.

## Shared-memory Ender Tunnel Array

- The array GUI allows a user-defined text memory ID to be assigned. IDs support
  letters, digits, and underscores, with a nonempty maximum length of 32 characters.
  Compare IDs case-insensitively, so `Test_RAM` and `test_ram` select the same region.
  A valid example is `test_shared_ram_987453267895`.
- A memory ID may be changed only while the mainframe is powered off; pausing in
  the debugger is not sufficient.
- All mainframes whose arrays use the same memory ID share the same region,
  including mainframes in different dimensions.
- Use the emulator's mapped 64 KiB shared region at `0xB0000` to `0xBFFFF`.
- Different memory IDs select different shared regions. Selecting an existing ID
  joins its existing region; selecting a new ID creates a zero-filled region for
  that ID.
- Regions are never automatically deleted, including when their last CPU leaves.
  Only an admin may delete one. Region registration and byte retention are distinct.
- Retain shared bytes while at least one CPU with access is powered on. Paused
  CPUs and CPUs in unloaded chunks still count if their logical power state is on.
  Power cycling one CPU does not clear the bytes while another such CPU retains
  access. When the last powered-on CPU with access powers off or leaves the group,
  reset its bytes to zero but keep the named region registered.
- Saving/closing/reopening the world is suspension, not a power-off transition.
  Save shared bytes and logical powered-on membership consistently and restore
  them before any CPU runs, avoiding an artificial reset during startup ordering.
- If an admin deletes a region that CPUs use, power off every connected mainframe
  and show the missing-region error. Block automatic restart until a valid region
  is explicitly selected while off; do not silently recreate the deleted region
  from its stale binding. The explicit selection may create a fresh zeroed region.
- Without an Ender Tunnel Array, shared-memory reads are zero and writes are ignored.

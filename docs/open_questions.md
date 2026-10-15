# Open questions and remaining work

The core behavior decisions previously listed here are resolved. There are no
remaining blocking behavior questions in the recorded specification.

- [mod_behavior.md](mod_behavior.md) defines lifecycle, devices, storage, ROMs,
  debugger control, conflicts, and shared-memory behavior.
- [cpu_scheduling.md](cpu_scheduling.md) defines paced batches, no carried budget,
  and the confirmed ten-batch base configuration for all tiers.
- [yield_instruction.md](yield_instruction.md) defines the confirmed `0F 00` batch
  yield and `0F 01` tick yield, including required standalone launcher changes.
- [sign_font.md](sign_font.md) defines the bundled font for mod-written signs.

## Shared-memory clarification

Active means logically powered on, including debugger-paused CPUs and CPUs in
unloaded chunks. Retain bytes while at least one such CPU has access; zero the
bytes when the last one powers off or leaves. The named region remains registered.
Saving/reopening the world is suspension and must not create a false last-power-off
transition. Admin deletion powers connected mainframes off until explicit region
selection resolves the missing binding.

## Later presentation and configuration choices

These can be handled during implementation rather than blocking core work:

- Whether the wall can face up/down as well as horizontally.
- Configuration location, supported setting ranges, and when changed server
  settings apply to existing mainframes.
- Recipes, survival progression, models/textures, and GUI presentation.
- Exact admin command spelling and permanent-delete safeguards.

## Implementation and validation still required

The documents describe requirements, not completed runtime functionality.

- Integrate the emulator core and a paced, non-overlapping batch scheduler through
  the host bridge. Expire old work instead of accumulating it.
- Implement the yield decoder/executor, CPU outcome, debugger/disassembler, mod
  scheduler, and standalone launcher consistently. Preserve explicit failure for
  every coprocessor opcode in `D8` through `DF`.
- Verify installed IA-16 GCC output and linked helpers do not accidentally emit
  the yield extension. The earlier WSL compiler probe was permission-blocked;
  this is pending validation, not an unanswered instruction-set decision.
- Implement coherent, versioned complete-state save/restore, including timer
  baselines, shared bytes, and logical powered-on membership. Restore them before
  workers run so world reload cannot clear live shared memory by startup ordering.
- Restore devices/storage while off before PSU-driven automatic startup. Keep
  retained backing data separate from zero-filled absent-device views.
- Enforce duplicate-controller, duplicate-port, and active-disk identity rules
  with location/dimension diagnostics.
- Retain recovery records and implement admin listing/recreation of items without
  changing identities or silently replacing data with blank contents.
- Implement the ROM hash-to-UUIDv7 library: reject duplicate uploads while keeping
  the first metadata, and power off affected mainframes if their ROM becomes invalid.
- Connect client Swing debugging to server-owned CPUs. Enforce the same control
  lease for debugger actions, block GUI changes, and manual buttons; send running
  fault/status notifications.
- Silently resample current inputs when existing ports reconnect, then resume
  ordinary IRQ behavior for later changes.
- Implement Windows-1252 sign/terminal handling and select the bundled named font.
- Run focused core/launcher/scheduler checks, resource/build checks, and in-game
  validation once the corresponding runtime work exists. Font resource inclusion
  alone does not prove target-version rendering compatibility.

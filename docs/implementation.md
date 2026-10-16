# Implementation and acceptance evidence

The mod specified in [mod_behavior.md](mod_behavior.md) is implemented, including the external Swing connection to server-owned CPUs. Recipes remain deferred. Both hosts compile the original sibling emulator sources and the same host-independent runtime; there is no separate CPU fork.

## Requirement audit

| Requirement | Implementation | Evidence |
| --- | --- | --- |
| One CPU in a same-chunk 7 × 3 wall; facing, required components and maxima | `WallLayout`, `HardwareProfile`, `ServerMachines` | Runtime layout checks and native powered-wall tests |
| Controller structural diagnoses | Shared wall scan, detailed issue positions/raw counts and custom controller panel | Runtime four-facing/chunk/overlap checks; native reports and inspected live invalid/Refresh/repair flow |
| Worker execution, per-tier paced batches, no carried work | `BatchedCpuWorker`, `TickBudget`, `MachineConfig`; standalone launcher uses the same runtime | Scheduling/yield checks; native unload and live running CPU |
| Private `0F 00` / `0F 01`, invalid modes/prefixes and unsupported `D8`–`DF` | Shared emulator decoder/executor, step outcomes, disassembler and both host schedulers | Sibling emulator suite; IA-16 probe |
| Absent devices, independent NMI, reset versus power cycle, installation while off | Shared hardware/bridge rules, `MainframeMachine`, guarded custom block screens | Emulator/runtime and native component tests |
| Controller identities, portable items, duplicate placement diagnostics | Controller block entity, world placement/recovery registry | Runtime records and native recovery/placement checks |
| Same-dimension remote ports, capacities, duplicate slots, retained bindings | `ServerPorts`, `PortBindings`, custom port screens | Native redstone/sign and unloaded-port checks |
| Analog/edge IRQs, silent input reconnection, cached output lifetime | Shared bridge and server port adapter | Emulator/runtime and native port checks |
| Both sign sides, Windows-1252, dirty writes and supplied font | `SignLine`, sign port writer, bundled Monosign | Native text/style tests; inspected live-client sign capture and headed Swing capture |
| ROM upload/library/burning, immutable chips, deduplication, missing-ROM shutdown | `RomLibrary`, `RomUpload`, `RomBurner`, client picker/packet adapter | Runtime file/pipe checks, native burner/network checks and ROM-loss regressions |
| Full CPU/device continuation and coherent shared storage | Shared CPU snapshots, `WorldRecords`, atomic `WorldStore`; workers stop before capture | Runtime round trips, actual chunk unload/reload, integrated-server reopen and fresh-JVM world reopen |
| Real-time and tick timers independent of executed steps | Epoch-millisecond and world-tick deltas saved with timer state | Emulator elapsed-timer tests; overdue real timer after JVM exit |
| Named cross-dimension shared regions and powered membership | Shared registry and Ender Tunnel screen | Runtime shared lifetime/snapshot checks; native and client persistence checks |
| Admin listing/recovery/deletion, retained identities/data | `MachineCommands`, recovery records and ROM/shared registries | Native console/player recovery, permission and inventory-overflow checks |
| Component/IRQ/terminal controls and debugger restrictions | Custom client screens with one-use player nonces and server revalidation | Native stale-form, ownership and component tests |
| Remote Swing owner/observer control and concurrent CPU viewing | `ServerDebugger`, authenticated Fabric packets, private client child pipes | Native three-player ownership/handover tests and simultaneous real-client helper windows |
| Paused CPU details; running status/fault/terminal updates | Bounded debugger snapshot protocol and server worker actions | Protocol/runtime checks, native running-data exclusion, live authenticated edits |
| Display names and custom creative tab | Block-item description prefix, language entries and Fabric tab with chip icon | Native key checks and live 21-item/tab validation |
| Purpose-built hardware screens | Client ControlPanelScreen/ControllerScreen, bounded Fabric packets, guarded installation menu | Panel/menu native regressions and inspected live client captures; no datapack dialog API remains |
| Original on/off textures and small animations | 17 block types with seven-frame powered sprites; 21 item definitions | Resource verifier, distinct-frame audit and inspected client off/on/animation captures |

## Current verification

Checked on 2026-10-16 with JDK 25 and Minecraft 26.3:

- Mod `build`, `verifyRuntime` and native server checks pass. There are **20 mod-specific GameTests plus one Minecraft baseline: 21 passed**.
- The sibling emulator suite passes: **168 tests, zero failures/errors, 10 existing optional skips**. It covers yields, hardware availability, timers, snapshots, sessions, ROM integrity and launcher behavior.
- The live Minecraft client verifies all 21 item names and the ROM-chip-icon creative tab, then opens the custom controller screen and actual external debugger, forwards authenticated edits, keeps two mainframe windows connected independently, releases leases when helpers close, and preserves the focus-pause option.
- Powered world continuation passes both an integrated-server reopen and a **separate Minecraft JVM**. AX, RAM, NVRAM, shared memory, disk bytes, pending IRQ and instruction progress survive; an overdue real-time timer expires after the process restarts.
- The packaged JAR opens a headed Swing window without Minecraft libraries. Owner Step forwarding, observer restrictions and Windows-1252 sign rendering pass the Swing smoke check.
- Resource checks cover blockstates, valid model parents, item references, drops and animation metadata. Latest client off/on and unobstructed sign screenshots were inspected.

Commands and log locations are in [native_tests.md](native_tests.md). Runtime, server-test and client-test classes are excluded from the installable mod JAR.

## Validation limits and deferred work

Recipes are intentionally absent. Multiplayer authentication and FIFO handover were tested with native server player connections; no multi-computer manual play session or large-server load benchmark was run. The live client also exercises custom rename, the two-slot installation bay, component panels, port Apply, ROM library selection/burning and the narrow layout. The desktop file picker has not been manually exercised end to end.

Representative IA-16 output and selected integer/soft-float helpers passed [compiler validation](ia16_validation.md). Arbitrary external ROM startup, custom `abort`, inline assembly and additional libraries still require their own validation.

## Build environment

Set `JAVA_HOME=C:/Program Files/Java/jdk-25`. Keep `../i8086Emulator` beside this checkout. Builds use the Gradle wrapper; `--offline` requires cached dependencies. Emulator changes, including the debugger backend/UI/status and ROM integrity checks, are committed directly in the sibling repository.

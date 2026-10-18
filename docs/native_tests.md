# Native development checks

The user accepted Minecraft's EULA for this project's isolated development tests on 2026-10-16. Acceptance files live in `build/gametest-run` and `build/client-gametest-run`.

## Commands

Punchcards add native server/client checks for hopper backpressure, pickup,
shift-click, native NBT restore, shared readers, deduplication, and tray/work models.
The standalone/core suite additionally runs `PunchcardRomTest` when
`PUNCHCARD_TEST_ROM` points to the IA-16 project's `build/punchcard_test.bin`.

Select JDK 25, then run:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
.\gradlew.bat --offline --console=plain --no-daemon build runGameTestServer
.\gradlew.bat --offline --console=plain --no-daemon runClientGameTest
.\gradlew.bat --offline --console=plain --no-daemon -Pi86Reopen runClientGameTest
.\gradlew.bat --offline --console=plain --no-daemon -Pi86Artwork runClientGameTest
node tools/verify_resources.mjs
python tools/verify_art_geometry.py
```

The command with `-Pi86Reopen` launches a new Minecraft JVM and reopens the world saved by the preceding client run. Keep their shared run directory and `i86-reopen.properties` checkpoint between those runs. Its use of Fabric's test world implementation is confined to the test source set. The artwork command uses a separate temporary gallery world.

`build` runs runtime self-checks and compiles native tests without starting Minecraft. Test classes and test-mod metadata are excluded from the production JAR. Server results are written to `build/gametest-results.xml`.

## Passed on 2026-10-16

**20 mod-specific server tests plus one Minecraft baseline: 21 passed.** Coverage includes:

- Redstone levels/IRQs, silent reconnection, retained output and duplicate-slot/capacity behavior.
- Sign text/styles/font, both sides, dirty writes, input acknowledgements and IRQ configuration.
- Same-chunk 7 × 3 mainframes, optional hardware and component screens.
- Custom panel codec bounds, reserved session fields, block-item translation keys, two-slot chip/shard routing and lease/power guards.
- Invalid controller reports: every missing/excess component count, actual coordinates, unsupported block names, wrong facing, optional-device handling and identity while unassembled. Runtime cases also cover all four facings, chunk boundaries and overlapping walls.
- Stale/replayed forms, lease races, block replacement and read-only observers.
- Burner upload context, sequence, duplicate content, immutable chips, missing references and activity cleanup.
- Console/player recovery, permissions, unavailable recipients, portable contents and full-inventory drops.
- Powered native chunk unload/reload with stable register/RAM/NVRAM/shared/disk state and a live debugger cursor target.
- Three-player authenticated debugger ownership, token rejection, FIFO handover, real server edits, running-state detail exclusion, bounded packets and flood cleanup.
- One player viewing two mainframes independently, closing one window and releasing all remaining sessions on disconnect.

**Live client and fresh process:** translated names for all 21 items, dedicated chip-icon creative tab, custom component/port/terminal/ROM screens, two-slot installation bay, rename, port Apply, library selection/burning, small-scale layout, invalid-wall diagnosis with current component counts, Refresh/repair clearing, external helper startup, authenticated memory/sign/I/O edits, two simultaneous helper windows, focus-loss pause guard and helper termination. Powered continuation passes both integrated-server reopen and a new Minecraft JVM, preserving AX, RAM, NVRAM, shared bytes, disk bytes, queued IRQ and instruction progress. A real-time timer ages across JVM exit and expires on resume.

**Headed Swing:** the packaged mod JAR opens without Minecraft libraries, forwards owner Step, disables observer mutations and renders Windows-1252 sign text. The production remote-helper entry point also starts and closes.

**Resources:** 17 animated block types and 21 item definitions pass model/reference/drop checks. Each powered sprite has seven distinct frames. Client captures of off/on, animation, controller dialog, font and reopened world were inspected.

## Port and controller regressions, 2026-10-17

**25 server tests passed**, including the existing suite and four added cases.
The three reported bugs were reproduced before the fixes. Coverage now includes
empty/chip-only/shard-only/full installation saves, legacy compact inventories,
portable controller contents, independent input/output IDs, retained duplicate
detection after reload, and real dust connection shapes for all six orientations.

The live client also saved/reopened a shard-only controller and verified the
empty ROM slot and occupied shard slot in the synced installation screen
(`I86_SHARD_ONLY_REOPEN_OK`). Screenshot:
`build/client-gametest-run/screenshots/0021_i86-shard-only-reopened.png`.

Logs: `build/port-inventory-fixes/regressions-before.log`,
`regressions-after.log` (build/runtime/world checks), and
`client-regressions.log` (live save/reopen).


## Terminal streaming and locked displays, 2026-10-17

**26 server tests passed** with the build and runtime checks. Added coverage
verifies live terminal session tokens, new history navigation, stable older pages,
close cleanup, update-packet round trips, and saved sign wax/editor state.

The real client confirms fresh output in the same screen, automatic tail follow,
manual-scroll preservation, rejection of unrelated/late updates, and no reopening
after close (`I86_TERMINAL_LIVE_OK`). A real click on a CPU-written sign leaves
the editor closed and delivers its sign IRQ (`I86_SIGN_LOCK_OK`).

Evidence: `build/terminal-sign-fixes/world-tests.log` and `client-tests.log`.

## Speed shard tooltips, 2026-10-17

**27 server tests passed**, including the fixed-size settings codec and invalid
budget rejection. The live client verified all three real item tooltips against
non-default server settings: 1739/7, 6811/13, and 12347/19 (steps/batches per tick).
It also checks default values after disconnect and fresh synchronization on
rejoining (`I86_SHARD_TOOLTIPS_OK`). The isolated test config is restored afterward.

Evidence: `build/shard-tooltips/world-tests.log` and `client-tests.log`.

## Evidence locations

- `build/mainframe-diagnostics-final-tests.log`: current build/runtime and all 21 native server checks, plus `I86_DIAGNOSTICS_UI_OK` for actual controller display, Refresh and repair clearing.
- `build/custom-ui-validation.log`: `I86_CUSTOM_UI_OK` and `I86_LIVE_CLIENT_OK`.
- `build/fresh-process-world-tests.log`: `I86_FRESH_PROCESS_OK`.
- `build/debugger-swing-smoke.log`: `SWING_SMOKE_OK`.
- `build/debugger-final-emulator-tests.log`: sibling emulator suite, 168 tests, 10 existing optional skips, no failures/errors.
- `build/client-gametest-run/screenshots/`: live client captures.
- `build/debugger-screens/`: headed Swing captures.

These are ignored local build artifacts. Reproduce the commands rather than treating their paths as committed fixtures.

## 3D artwork, 2026-10-17

The final offline build, runtime checks and **30 native server tests passed**.
New coverage distinguishes running/paused/off CPU indicators, physical pin
levels without a valid wall, input/output arrows, signal versus machine power,
retained output activity after shutdown, and dirty sign writes with pulse expiry.

The dedicated client gallery passed with `I86_ARTWORK_CLIENT_OK`. Every model's
powered state was checked on the client. Off/on, pulse, nighttime, both port
directions, rear geometry and all 21 inventory items (plus a programmed ROM chip)
were captured and inspected. Committed previews are in [art](../art/README.md);
full captures and the client log are under `build/client-gametest-run`.
The packaged production JAR was checked for the new geometry and conditional ROM
item, all nine animated textures, absence of replaced placeholder textures, and
exclusion of test classes. The resource verifier and `git diff --check` passed.

## Casing, effects and sign-mount corrections, 2026-10-17

**31 native tests passed**, including text synchronization to real standing,
wall and hanging signs in all six adjacent directions. Build and runtime
checks passed. The geometry regression first reproduced the inset casing and
visible coplanar overlaps, then passed all 216 state samples after the fixes.
Light checks verify a travelling band, visible contrast, bounded frame changes
and a continuous loop; all ten textures use sixteen interpolated frames.

The live artwork client passed with synchronized sign text. Ground-level views
of adjacent machines on grass show sealed outside edges. Close views cover
frame/device surfaces, the distinct controller and terminal, and standing,
hanging and wall-sign connections. Previews are saved under art/; full captures
and logs remain under build/client-gametest-run. Third-party rendering and
shader mods were not tested.

## Four-segment activity strips, 2026-10-17

Build and runtime checks passed with real-access telemetry, including unchanged
writes, host-inspection exclusion, per-channel routing, IRQ/NMI/reset/WAIT events,
terminal output and timer expiry without IRQs. Continuous busy samples stay fully
lit; an idle sample starts a 75 ms fade and new activity cancels it.

**37 native server tests passed.** New checks cover bounded indicator packet
round trips, latched failure versus off, save-completion events, sign clicks
without blockstate changes, and running/paused/off controller status. Existing
pin, port, sign, persistence, debugger and machine tests remain green.
The standalone emulator suite reports 172 tests, 13 skipped, zero failures/errors.

The artwork client passed with `I86_ARTWORK_CLIENT_OK`. Inspected close-ups show
the contiguous four-segment strips, leftmost
status, a dark controller slot and no filler lights. These captures use controlled
indicator packets to verify the network/rendering path; actual CPU activity is
covered separately by runtime/world checks. The 16 by 16 uniform LED textures
retain the block atlas's four mip levels. Geometry checks pass all 216 states.

A regression performs real successful and failed filesystem saves. It verifies
persisted bytes, success-only disk/NVRAM pulses, red status after failure,
preservation of the previous snapshot, and recovery after a successful retry.

Previews: [HDD](../art/led-hdd.png), [sign](../art/led-sign.png),
[redstone](../art/led-redstone.png), [failure](../art/led-failure.png).
Local evidence: `build/led-final-validation.log`, `build/led-package-check.log`,
`build/led-persistence-check.log`,
`build/led-core-tests.log`, `build/indicator-benchmark.txt` and the native XML.
The CPU microbenchmark is described in [the LED specification](led_indicators.md);
it is not an FPS/TPS or large-server benchmark.

## Sign Port attachment-face indicators, 2026-10-17

The Sign Port now has plain casing in place of its display front. All four
segments sit centered below the lower attachment light bar.
Resource and geometry checks pass all 216 states. The build/runtime checks and
live artwork client passed; client assertions verify the LED plane, segment size
and outward winding for all six attachment directions. Inspected captures cover
the connector, former display face, wall sign, standing sign and hanging sign.
Evidence: `build/sign-port-validation.log`; previews are under `art/`.

## Smaller, saturated LEDs, 2026-10-17

Each segment now measures 0.8 by 0.8 model pixels, with four adjacent segments
spanning 3.2 pixels. The solid palette uses stronger saturation and pure red for
failure. Resource/geometry checks, the build/runtime checks and the live artwork
client passed. Client assertions verify the reduced size and attachment-face
placement in all six directions. The block atlas retains four mip levels.
Evidence: `build/led-bright-validation.log`; current close-ups are under `art/`.

## Limits

The artwork suite does not cover third-party shader or rendering mods.

No multi-computer manual play session or large-server load benchmark was run. Native tests cover component/burner submissions; the live test verifies the custom screen flows and their appearance. File selection through the desktop chooser has not been manually tested end to end. Arbitrary external ROMs need their own startup/library validation as described in [ia16_validation.md](ia16_validation.md). Recipes are deferred.

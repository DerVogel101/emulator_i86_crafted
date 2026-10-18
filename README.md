# i86 Emulator for Fabric

Minecraft 26.3 mainframes running the shared i8086 emulator, with redstone/sign I/O, persistent storage, ROM burning and an external Swing debugger.

Blocks and items use original 3D models with graphite casings, recessed hardware
and smoothly interpolated indicators. Power, CPU activity, redstone direction
and physical signal states have separate lights; see [artwork](art/README.md).

## Install and start

Use Java 25, Fabric Loader 0.19.5 or newer, and Fabric API for Minecraft 26.3. Install `emulator_i86_crafted-1.0-SNAPSHOT.jar` on both client and server. The emulator, launcher views and named sign font are bundled. Desktop Java with Swing is required on clients opening the debugger or ROM file picker.

All items have [survival crafting recipes](docs/crafting.md), including six crafting materials and an alternate crude Speed Shard III. Items are also available in the creative **i86 Mainframes** tab (ROM chip icon) or through `/give` with the `emulator_i86_crafted:` namespace.

1. Build a 7 × 3 × 1 wall inside one chunk. Face every component in the same horizontal direction. Include one Controller, Power Supply, Reset Port, NMI Port, TEST Port and at least one GPIO Controller. Fill unused positions with Super Structure. Optional devices and their maximum counts are in [the behavior specification](docs/mod_behavior.md).
2. While power is off, right-click the controller to install a burned ROM chip and a speed shard. Use a ROM Burner to upload an exactly 256 KiB image and burn a blank chip. See [ROM burning](docs/rom_burner.md).
3. Configure an Ender Tunnel Array's memory ID before startup if one is installed. Configure external redstone/sign ports through their dialogs.
4. Power the PSU with redstone. Right-click the controller and choose **Open debugger**. Each viewed mainframe opens its own external window; CPUs remain on the server. The first opener controls it and others observe.

Tier defaults are 1,000/5,000/10,000 steps per tick, in ten paced batches each. Change `shard1.stepsPerTick` / `shard1.batchesPerTick` (and tiers 2–3) in `config/emulator_i86_crafted.properties`, then restart the server.

World data is stored under `<world>/data/emulator_i86_crafted/`. Back up that directory together with the world. [Admin commands](docs/admin_recovery.md) recover retained controller/disk/ROM identities.

## Build and verify

Keep the sibling `../i8086Emulator` checkout beside this project; the build compiles its core and launcher sources directly.

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
.\gradlew.bat --offline --console=plain --no-daemon build
```

The installable JAR is `build/libs/emulator_i86_crafted-1.0-SNAPSHOT.jar`; the runtime and sources JARs are development artifacts. See [implementation and acceptance evidence](docs/implementation.md) and [native test commands](docs/native_tests.md).

The yield extension is `0F 00` (batch) / `0F 01` (tick). Coprocessor opcodes remain unsupported. [IA-16 GCC validation](docs/ia16_validation.md) covers representative compiler output and selected helpers; validate each ROM's additional startup/library code.

# Specification status and deferred work

The recorded behavior decisions are resolved and implemented. There are no remaining core behavior questions blocking this mod. [implementation.md](implementation.md) maps the requirements to code and current evidence.

The contracts remain in:

- [mod_behavior.md](mod_behavior.md): hardware, lifecycle, storage, identity, ROMs, ownership, conflicts and shared memory.
- [cpu_scheduling.md](cpu_scheduling.md): paced batches, deadlines and expiring budgets.
- [yield_instruction.md](yield_instruction.md): `0F 00` / `0F 01`, shared by the emulator, launcher and mod.
- [sign_font.md](sign_font.md): Windows-1252 and the supplied named sign font.

Shared memory retains bytes while a CPU is logically powered on, including paused or unloaded CPUs. Last power-off zeroes bytes without deleting the region. Saving/reopening suspends execution and preserves powered membership.

## Deferred decisions

- Recipes and progression are deferred at the user's request.
- Custom rack-style screens are implemented; they replace the datapack dialogs and retain server-side rules.
- Each ROM chooses its own startup/runtime libraries and must validate those paths; representative toolchain checks do not certify every ROM.

## Validation limits

The dedicated server tests, headed Swing check, live client and fresh-JVM world continuation pass. See [native_tests.md](native_tests.md). Multi-computer manual play, large-server load measurement and exhaustive manual clicking of component/file-picker dialogs remain outside the completed checks.

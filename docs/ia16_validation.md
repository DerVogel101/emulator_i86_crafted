# IA-16 GCC yield compatibility check

Checked on 2026-10-15 using the installed WSL Ubuntu `ia16-elf-gcc` 6.3.0,
with `-march=i8086 -mcmodel=small -ffreestanding -fno-builtin`.

The [probe source](../tools/ia16_yield_probe.c) covers 16/32-bit integer math,
volatile byte copying, port I/O, float/double arithmetic, compiler trap generation,
and the two explicit inline yield helpers. It compiles at `-O0`, `-O2`, and `-Os`.
Ordinary generated instructions use the documented 8086 instruction set. The only
private byte encodings are the two explicitly written yield helpers.

Float/double operations call `__addsf3` and `__divdf3`; 32-bit division calls
`__udivsi3`. A relocatable link selects those and their actual transitive helpers
from the target's installed `libgcc.a`. The verifier inspects decoded instruction
boundaries, excluding the explicitly private yield functions. It checked **1,491
ordinary instructions**, with no `0F`, `D8`-`DF`, or later-CPU opcodes. This is not
a raw byte-pattern search through ROM data or immediate operands.

The compiler trap calls `abort`; that is the only unresolved symbol after linking
the probe and selected helpers. A freestanding ROM must supply its own `abort`
implementation. The probe does not prove that implementation or every possible
startup/library/inline-assembly path is compatible. Final ROMs still need their
normal instruction-level validation.

Reproduce from Windows in this checkout:

```powershell
wsl.exe -d Ubuntu -- sh /mnt/f/prog/IdeaProjects/emulator_i86_crafted/tools/check_ia16_yield.sh
node tools/verify_ia16_probe.mjs
```

Outputs stay under ignored `build/ia16-probe`. Adjust the WSL mount path when the
checkout moves. The verifier intentionally fails if the inspected instruction
set changes, requiring review of the new compiler/helper output. No toolchain
flags or external ROM-project files were modified.

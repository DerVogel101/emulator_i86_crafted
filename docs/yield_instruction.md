# Cooperative yield instruction extension

This document defines the confirmed encoding and behavior. The instruction and
its host/launcher integration are not implemented yet.

## Confirmed requirement

Add an emulator-specific instruction with an argument selecting whether it yields
the current execution batch or the remainder of the current tick. Use an opcode
with no documented purpose in the original 8086 instruction set that is not emitted
as an ordinary instruction by the supported IA-16 GCC toolchain. Do not repurpose
coprocessor instructions: the full `D8` through `DF` range must continue to fail
explicitly. Existing implemented base instructions, including `HLT` and `WAIT`,
retain their behavior.

The host schedules the resulting pause according to
[cpu_scheduling.md](cpu_scheduling.md); the instruction itself does not block the
Java worker or manipulate Minecraft objects.

## Compiler and compatibility boundary

The ROM toolchain is IA-16 GCC. The confirmed requirement is that ordinary,
supported compiler output must not accidentally execute the custom instruction.
The selected opcode must have no documented original-8086 purpose, and must not
belong to the coprocessor extension. A hardware crash on an original 8086 is not
required.

The current ROM project's CMake configuration selects `ia16-elf-gcc` with
`-march=i8086 -mcmodel=small -ffreestanding -fno-builtin`. This identifies the target
but does not itself prove the absence of 8087 instructions: the 8087 was designed
to accompany an 8086. The supported ROM contract excludes 8087 instructions from
compiler output, startup code, inline assembly, and linked runtime libraries.

Do not assume a particular soft-float compiler flag is available without checking
the installed IA-16 GCC version. If floating-point C is used, its generated code
and any linked arithmetic helpers must still satisfy the no-8087 contract.

An original 8086 has no invalid-instruction exception and executes undocumented
encodings; for example, byte `0F` performs undocumented `POP CS`. Physical-hardware
behavior is outside this extension's compatibility promise.

Sources:

- [SingleStepTests 8086 hardware notes](https://github.com/SingleStepTests/8086/blob/main/README.md#undefined-instructions)
- [Modern x86 undefined-instruction encodings](https://www.felixcloutier.com/x86/ud)

ROMs using the extension are explicitly emulator-specific. All omitted coprocessor
instructions remain unsupported and must report an explicit error rather than
being reinterpreted as a yield.

## Encoding: YIELD imm8

Reserve `0F imm8` as a two-byte instruction. Byte `0F` has no documented instruction
in the original 8086 ISA. Its undocumented physical `POP CS` behavior is not part
of this emulator's supported instruction contract.

| Bytes | Documented assembly spelling | Effect |
| --- | --- | --- |
| `0F 00` | `YIELD 0` / batch yield | Discard the current batch's remaining budget |
| `0F 01` | `YIELD 1` / tick yield | Discard all remaining budget in this tick |
| `0F 02` through `0F FF` | Reserved modes | Explicit unsupported-extension error |

The current emulator rejects `0F` and the entire `D8` through `DF` coprocessor
range. Only `0F` would gain extension semantics. Every coprocessor opcode retains
its current unsupported-instruction failure.

Later x86 processors use `0F` as an opcode escape, but this ROM environment targets
the documented 8086 ISA, not those later instructions. In particular, the modern
`UD2` sequence `0F 0B` would select an invalid mode here and fail explicitly; it
would not accidentally perform either yield operation.

Until toolchain support exists, emit bytes explicitly, for example GNU assembler:

```asm
.byte 0x0f, 0x00  # batch yield
.byte 0x0f, 0x01  # tick yield
```

The mnemonic is documentation syntax; existing assemblers do not gain a `YIELD`
instruction merely because the emulator implements it.

An eventual C inline-assembly helper must be volatile and prevent compiler memory
accesses from being moved across the cooperative scheduling point. It must emit
the bytes explicitly, not depend on a compiler-generated trap instruction.

## Compiler-output validation

Before implementation is considered compatible, inspect representative assembly
from the installed IA-16 GCC configuration, including floating-point helpers if
used and compiler trap/error paths. Supported instruction streams must contain no
accidental extension or coprocessor instructions. Matching bytes inside an immediate,
displacement, string, or ROM data table are not instruction collisions and must
not be rejected by a whole-ROM byte-pattern search.

The compiler/architecture flags and existing no-coprocessor emulator contract have
been inspected. A local compiler probe was blocked by WSL execution permissions;
generated assembly has not yet been verified. No compiler flags were changed.

## Architectural behavior

- Decode the immediate as an unsigned byte; only modes zero and one are valid.
- A valid yield advances IP beyond the complete instruction. It changes no general
  registers, segment registers, flags, memory, or device I/O.
- Count it as one completed instruction and one budgeted `step()` call, preserving
  the emulator's normal instruction-boundary and interrupt-shadow rules.
- Report the batch/tick scheduling request explicitly to the host. Do not reuse
  the halted state: yield resumes at a scheduling boundary without requiring an IRQ.
- Pending IRQ/NMI handling continues through normal host/CPU boundaries. A queued
  interrupt does not cause the scheduler to reclaim budget explicitly yielded for
  the current batch/tick. Reset/power-off still supersedes scheduled execution.
- A debugger's manual Step executes the yield instruction and displays the next
  IP; later manual steps are deliberate user actions rather than queued tick work.
- Invalid modes and unsupported extension opcodes produce an explicit diagnostic
  with the instruction's starting CS:IP. They must not silently behave as no-ops.
- Treat segment, LOCK, and repeat prefixes on this private instruction as unsupported
  unless an extension deliberately defines them later.

These details define the instruction contract, not completed implementation.

## Required implementation scope

The decoder/executor, CPU step outcome, disassembler/debugger, host schedulers,
and focused CPU tests must agree on encoding and mode semantics. The standalone
launcher also needs to honor yield results; changing only the mod scheduler would
leave emulator-specific ROM behavior inconsistent between hosts.

Update the launcher's worker scheduling, debugger/disassembly, and manual stepping
to use this same encoding and the batch/tick semantics. Batch yield waits for the
next scheduled batch; tick yield waits for the next eligible tick. The launcher
must not continue executing the remaining yielded budget immediately. A manual
debugger Step remains one explicit user-requested instruction as defined above.

Tests should cover both modes, IP/flags/register preservation, invalid modes,
prefix rejection, reset/interrupt interaction, and continued explicit rejection
of every `D8` through `DF` coprocessor opcode. Scheduler checks must prove
that yield discards budget rather than carrying it into later batches or ticks.

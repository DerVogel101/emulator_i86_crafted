#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/ia16-probe
ia16-elf-gcc --version | head -n 1
for level in 0 2 s; do
    ia16-elf-gcc -march=i8086 -mcmodel=small -ffreestanding -fno-builtin -O"$level" \
        -S tools/ia16_yield_probe.c -o "build/ia16-probe/probe-O$level.s"
    ia16-elf-gcc -march=i8086 -mcmodel=small -ffreestanding -fno-builtin -O"$level" \
        -c tools/ia16_yield_probe.c -o "build/ia16-probe/probe-O$level.o"
done
ia16-elf-gcc -march=i8086 -Q --help=target > build/ia16-probe/target-options.txt
libgcc=$(ia16-elf-gcc -march=i8086 -mcmodel=small -print-libgcc-file-name)
printf '%s\n' "$libgcc" > build/ia16-probe/libgcc-path.txt
ia16-elf-ld -r -o build/ia16-probe/probe-with-helpers.o build/ia16-probe/probe-O2.o "$libgcc"
ia16-elf-objdump -dr -mi8086 -Mintel build/ia16-probe/probe-with-helpers.o > build/ia16-probe/linked-disassembly.txt
ia16-elf-nm -u build/ia16-probe/probe-with-helpers.o > build/ia16-probe/unresolved.txt
printf 'Generated compiler probes under build/ia16-probe\n'

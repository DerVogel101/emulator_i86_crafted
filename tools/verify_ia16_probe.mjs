import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const folder = new URL('../build/ia16-probe/', import.meta.url);
const compilerInstructions = new Set(`addw call cmpw decw divw incw inw je jmp jne leaw movb movw
    mulw nop orb orw outw popw pushw rclw ret shlw subw testw xchgw xorw`.split(/\s+/));
for (const level of ['0', '2', 's']) {
    const assembly = await readFile(new URL(`probe-O${level}.s`, folder), 'utf8');
    assert.match(assembly, /call\s+abort\b/, 'Check the toolchain trap path again');
    const explicit = assembly.match(/\.byte\s+0x0f,\s*0x0[01]/g) ?? [];
    assert.equal(explicit.length, 2, 'Only the two explicit inline helpers should emit yields');
    for (const line of assembly.split('\n')) {
        const instruction = line.match(/^\t([a-z][a-z0-9]*)(?:\s|$)/);
        if (instruction) assert(compilerInstructions.has(instruction[1]), `Review O${level}: ${line}`);
    }
}

const documented = new Set(`adc add and call cmp cwd dec div in inc ja jb jbe jcxz je jg jge jl jle
    jmp jne lds lea les loop mov mul neg not or out pop push rcl rcr ret sbb shl shr sub test xchg xor`.split(/\s+/));
const prefixes = new Set(['26', '2e', '36', '3e', 'f0', 'f2', 'f3']);
const omitted = new Set(['0f', '60', '61', '62', '63', '64', '65', '66', '67', '68', '69', '6a', '6b',
    '6c', '6d', '6e', '6f', 'c0', 'c1', 'c8', 'c9', 'd8', 'd9', 'da', 'db', 'dc', 'dd', 'de', 'df']);
let current = '', instructions = 0;
const functions = new Set();
const disassembly = await readFile(new URL('linked-disassembly.txt', folder), 'utf8');
for (const line of disassembly.split('\n')) {
    const label = line.match(/^[0-9a-f]+ <([^>]+)>:/);
    if (label) { current = label[1]; functions.add(current); }
    const decoded = line.match(/^\s*([0-9a-f]+):\s+((?:[0-9a-f]{2}\s+)+)\s*([^\s].*)$/);
    if (!decoded || current.startsWith('yield_')) continue;
    const bytes = decoded[2].trim().split(/\s+/);
    let opcode = 0;
    while (prefixes.has(bytes[opcode])) opcode++;
    const mnemonic = decoded[3].split(/\s+/)[0];
    assert(!omitted.has(bytes[opcode]), `Unexpected opcode in ${current}: ${line}`);
    assert(documented.has(mnemonic), `Review instruction in ${current}: ${line}`);
    instructions++;
}
for (const helper of ['__udivsi3', '__addsf3', '__divdf3', '__pack_f', '__unpack_f', '__pack_d', '__unpack_d'])
    assert(functions.has(helper), `Missing linked helper ${helper}`);
assert(instructions > 100, 'Disassembly parser did not inspect the linked helpers');
const unresolved = await readFile(new URL('unresolved.txt', folder), 'utf8');
assert.deepEqual(unresolved.trim().split('\n').map(line => line.trim()), ['U abort']);
console.log(`IA-16 probe passed: O0/O2/Os; ${instructions} linked ordinary instructions, no private or coprocessor opcodes; abort must be supplied by the ROM.`);

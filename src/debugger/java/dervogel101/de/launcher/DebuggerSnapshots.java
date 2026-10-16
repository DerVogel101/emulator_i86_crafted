package dervogel101.de.launcher;

import dervogel101.de.CPU8086;
import java.util.*;
import static dervogel101.de.launcher.DebuggerBackend.*;

/** Captures only copied viewports; call on the paused CPU's worker. */
public final class DebuggerSnapshots {
    public static final class View {
        public int memoryBase = 0xFFF00, ioBase, codeCs = 0xFFFF, codeIp, channel, sign, timer;
        public boolean followPc = true;
    }
    private DebuggerSnapshots() {}
    public static Snapshot capture(CPU8086 cpu, Snapshot header, View view) {
        if (header.running() || !header.machine().powered()) throw new IllegalStateException("Pause a powered CPU before requesting detailed views");
        if (view.memoryBase < 0 || view.memoryBase > 0xFFFFF || view.ioBase < 0 || view.ioBase > 0xFFFF
                || view.codeCs < 0 || view.codeCs > 0xFFFF || view.codeIp < 0 || view.codeIp > 0xFFFF
                || view.channel < 0 || view.channel > 127 || view.sign < 0 || view.sign > 127 || view.timer < 0 || view.timer > 5)
            throw new IllegalArgumentException("Invalid debugger viewport");
        cpu.beforeInstruction();
        var r = cpu.registers();
        byte[] memory = new byte[256], io = new byte[256];
        for (int i = 0; i < 256; i++) {
            int address = (view.memoryBase + i) & 0xFFFFF;
            memory[i] = (byte) cpu.memory().read8(address >>> 4, address & 15);
            io[i] = (byte) cpu.io().read8((view.ioBase + i) & 0xFFFF);
        }
        var devices = new DeviceSnapshot(view.channel, view.sign, view.timer, ports(cpu, view.channel * 2, 2),
                ports(cpu, 0x0100 + view.sign * 0x100, 130), ports(cpu, 0x8100 + view.timer * 8, 5),
                ports(cpu, 0xFF00, 3), ports(cpu, 0x8160, 5), cpu.hostBridge().timerAutomatic(view.timer),
                cpu.hostBridge().redstoneIrqMode(view.channel), cpu.hostBridge().signIrqSides(view.sign));
        int cs = view.followPc ? r.getCs() : view.codeCs, ip = view.followPc ? r.getIp() : view.codeIp;
        var code = new ArrayList<Disassembler8086.Instruction>();
        for (int i = 0; i < 25; i++) { var row = Disassembler8086.decode(cpu.memory(), cs, ip); code.add(row); ip = row.nextIp(); }
        String registers = ("AX %04X BX %04X CX %04X DX %04X SP %04X BP %04X SI %04X DI %04X\n"
                + "CS %04X DS %04X ES %04X SS %04X IP %04X FLAGS %04X PC %05X\n"
                + "CF %s PF %s AF %s ZF %s SF %s TF %s IF %s DF %s OF %s").formatted(
                r.getAx(), r.getBx(), r.getCx(), r.getDx(), r.getSp(), r.getBp(), r.getSi(), r.getDi(),
                r.getCs(), r.getDs(), r.getEs(), r.getSs(), r.getIp(), r.getFlags(), cpu.memory().physicalAddress(r.getCs(), r.getIp()),
                bit(r.getCF()), bit(r.getPF()), bit(r.getAF()), bit(r.getZF()), bit(r.getSF()), bit(r.getTF()), bit(r.getIF()), bit(r.getDF()), bit(r.getOF()));
        return new Snapshot(header.romName(), header.status(), registers, header.instructionsPerSecond(), header.totalInstructions(),
                view.memoryBase, memory, view.ioBase, io, List.copyOf(code), false, header.faulted(), devices,
                cpu.hostBridge().terminalText(), header.images(), header.testHigh(), header.machine());
    }
    private static byte[] ports(CPU8086 cpu, int first, int size) {
        byte[] bytes = new byte[size]; for (int i = 0; i < size; i++) bytes[i] = (byte) cpu.io().read8(first + i); return bytes;
    }
    private static int bit(boolean value) { return value ? 1 : 0; }
    public static boolean writableMemory(int address) {
        return address >= 0 && address < 0x9FFF3 || address >= 0xA0000 && address < 0xC0000;
    }
    public static boolean writableIo(int port) {
        if (port < 0 || port > 0xFFFF) return false;
        if (port < 0x100) return (port & 1) == 1;
        if (port <= 0x80FF) return (port & 0xFF) < 0x80 || (port & 0xFF) == 0x81;
        if (port <= 0x812F) { int offset = (port - 0x8100) & 7; return offset >= 2 && offset <= 4; }
        if (port <= 0x8150) return true;
        if (port >= 0x8160 && port <= 0x8164) return true;
        return port == 0xFF02;
    }
}

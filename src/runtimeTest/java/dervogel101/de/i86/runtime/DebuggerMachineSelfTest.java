package dervogel101.de.i86.runtime;

import dervogel101.de.CPU8086;
import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.launcher.*;
import java.nio.file.*;
import java.util.*;

final class DebuggerMachineSelfTest {
    static void run() throws Exception {
        Path folder = Files.createTempDirectory("i86-debugger-machine").toAbsolutePath().normalize();
        var world = new WorldRecords(); var record = world.allocate(); record.paused = true;
        var library = new RomLibrary(folder);
        var rom = library.upload(new byte[256 * 1024], "Debugger check", "");
        var machine = new MainframeMachine(world, record, library, new DiskClaims());
        try {
            assert machine.powerOn(HardwareProfile.complete(), rom.id(), 1, "debugger_check", world.createDisk());
            machine.debugger(true);
            machine.onWorker(cpu -> {
                int[] program = {0xE8, 5, 0, 0x40, 0xEB, 0xFD, 0x90, 0x90, 0x43, 0xC3};
                for (int i = 0; i < program.length; i++) cpu.memory().write8(0, 0x100 + i, program[i]);
                cpu.registers().setSp(0x2000);
                cpu.memory().write16(0, 12, 0x400); cpu.memory().write16(0, 14, 0);
                cpu.memory().write8(0, 0x300, 0xCC); cpu.memory().write8(0, 0x301, 0x40); cpu.memory().write8(0, 0x302, 0xF4);
                cpu.memory().write8(0, 0x400, 0xCF);
                cpu.memory().write8(0, 0x500, 0x62); cpu.memory().write8(0, 0x501, 0x90);
                return null;
            });
            machine.setPc(0, 0x100); machine.stepOver(); drain(machine);
            assert record.paused && record.status.equals("Cursor reached");
            machine.onWorker(cpu -> {
                assert cpu.registers().getIp() == 0x103 && cpu.registers().getBx() == 1 && cpu.registers().getAx() == 0;
                return null;
            });
            machine.skipInstruction();
            assert machine.onWorker(cpu -> cpu.registers().getIp()) == 0x104;
            machine.setPc(0, 0x103); machine.runTo(0, 0x104); drain(machine);
            assert record.paused && machine.onWorker(cpu -> cpu.registers().getAx()) == 1;

            machine.setPc(0, 0x300); machine.pause(false); checkpointStop(machine);
            assert record.paused && record.status.equals("INT 3 breakpoint");
            assert machine.onWorker(cpu -> cpu.registers().getIp()) == 0x300;
            machine.runTo(0, 0x301); drain(machine);
            assert record.paused && machine.onWorker(cpu -> cpu.registers().getIp()) == 0x301 : "Continue bypasses the intercepted INT3 once";

            machine.setPc(0, 0x500);
            boolean fault = false;
            try { machine.step(); } catch (RuntimeException expected) { fault = true; }
            assert fault && record.faulted;
            machine.skipInstruction();
            assert !record.faulted && record.paused;
            assert machine.onWorker(cpu -> cpu.registers().getIp()) == 0x501;
            assert machine.step() == CPU8086.StepOutcome.INSTRUCTION;
            machine.setPc(0, 0x500);
            try { machine.step(); } catch (RuntimeException expected) {}
            assert record.faulted;
            machine.setPc(0, 0x501);
            assert !record.faulted && machine.step() == CPU8086.StepOutcome.INSTRUCTION;

            var header = header(record, false);
            var view = new DebuggerSnapshots.View(); view.memoryBase = 0x100;
            var snapshot = machine.onWorker(cpu -> DebuggerSnapshots.capture(cpu, header, view));
            assert snapshot.memory()[0] == (byte) 0xE8 && snapshot.code().size() == 25;
            assert snapshot.registers().contains("AX 0001") && snapshot.registers().contains("BX 0001");
            snapshot.memory()[0] = 0;
            assert machine.onWorker(cpu -> cpu.memory().read8(0, 0x100)) == 0xE8 : "Snapshots never expose live memory arrays";
            boolean denied = false;
            try { machine.onWorker(cpu -> DebuggerSnapshots.capture(cpu, header(record, true), view)); }
            catch (IllegalStateException expected) { denied = true; }
            assert denied : "Detailed capture is forbidden while running";
            assert DebuggerSnapshots.writableMemory(0xA0000) && !DebuggerSnapshots.writableMemory(0x9FFF3) && !DebuggerSnapshots.writableMemory(0xC0000);
            assert !DebuggerSnapshots.writableIo(0) && DebuggerSnapshots.writableIo(1) && !DebuggerSnapshots.writableIo(0xFF00) && DebuggerSnapshots.writableIo(0xFF02);
        } finally {
            machine.powerOff("Debugger checks complete");
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(folder)) throw new IllegalStateException("Cleanup escapes test directory");
                    Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("Server debugger machine checks passed: step-over, cursor, INT3 continue, fault skip/set-PC, copied paused viewports");
    }
    private static void checkpointStop(MainframeMachine machine) throws InterruptedException {
        for (int tick = 1; tick <= 50; tick++) {
            machine.tick(System.nanoTime(), 50_000_000, new ShardSpeed(1000, 10), tick, tick);
            Thread.sleep(10); machine.onWorker(cpu -> null);
            assert !machine.record.paused : "The next host tick must not hide a missing checkpoint latch";
            machine.capture();
            if (machine.record.paused) return;
        }
        throw new AssertionError("Debugger checkpoint never paused at INT3");
    }
    private static void drain(MainframeMachine machine) throws InterruptedException {
        var speed = new ShardSpeed(1000, 10);
        for (int tick = 1; tick <= 50 && !machine.record.paused; tick++) {
            machine.tick(System.nanoTime(), 50_000_000, speed, tick, tick);
            Thread.sleep(10);
            machine.onWorker(cpu -> null);
        }
    }
    private static DebuggerBackend.Snapshot header(WorldRecords.Mainframe record, boolean running) {
            return new DebuggerBackend.Snapshot("Debugger test [" + record.id + "]", record.status, "", 0, 0,
                    0, new byte[256], 0, new byte[256], List.of(), running, record.faulted, DebuggerProtocol.emptyDevices(), "",
                    new DebuggerBackend.ImageState(null, false, null, false, true), false,
                    new DebuggerBackend.MachineState(true, true, record.hardware, 1, "debugger_check", ShardSpeed.TIER_1));
    }
}

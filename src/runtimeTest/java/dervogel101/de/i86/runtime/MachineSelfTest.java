package dervogel101.de.i86.runtime;

import dervogel101.de.CPU8086;
import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.state.CPUMemory;
import java.nio.file.*;
import java.util.*;

final class MachineSelfTest {
    static void run() throws Exception {
        Path folder = Files.createTempDirectory("i86-machine-check");
        var opened = new ArrayList<MainframeMachine>();
        try {
            var library = new RomLibrary(folder); byte[] image = new byte[CPUMemory.ROM_SIZE];
            image[0x3FFF0] = 0x0F; image[0x3FFF1] = 1; image[0x3FFF2] = (byte) 0xEB; image[0x3FFF3] = (byte) 0xFC;
            var rom = library.upload(image, "Yield loop", "Test ROM");
            var hardware = new EnumMap<Hardware, Integer>(Hardware.class);
            for (var part : Hardware.values()) if (part != Hardware.GPU) hardware.put(part, 1);
            var profile = new HardwareProfile(hardware);
            var world = new WorldRecords(); var frame = world.allocate(); frame.paused = true;
            frame.structureKey = "stable-wall";
            frame.controllers.add(new WorldRecords.Location("minecraft:overworld", 1, 64, 1));
            UUID disk = world.createDisk(); var claims = new DiskClaims();
            var machine = new MainframeMachine(world, frame, library, claims); opened.add(machine);
            assert machine.powerOn(profile, rom.id(), 1, "Shared_Test", disk);
            machine.onWorker(cpu -> { cpu.memory().write8(0, 0x100, 42); cpu.memory().write8(0xA000, 3, 0xAB); cpu.memory().write8(0xB000, 2, 0xCD); return null; });
            machine.tick(System.nanoTime(), 50_000_000, ShardSpeed.TIER_1, 100, 6000);
            machine.onWorker(cpu -> { cpu.io().write16(0x811A, 5); cpu.io().write8(0x811C, 5); return null; });
            machine.tick(System.nanoTime(), 50_000_000, ShardSpeed.TIER_1, 110, 6010);
            machine.onWorker(cpu -> { assert cpu.io().read16(0x8118) == 0; assert cpu.io().read16(0xFF00) == 0x300; return null; });
            assert machine.instructions() == 0 : "Paused CPU must only refresh its bridge, never execute steps";
            assert machine.step() == CPU8086.StepOutcome.YIELD_TICK;
            machine.capture(); var store = new WorldStore(folder); store.save(world); machine.unload();
            assert frame.powered && world.shared.region("shared_test").read8(2) == 0xCD : "Unloading is suspension";
            var chunkResume = new MainframeMachine(world, frame, library, claims); opened.add(chunkResume);
            assert chunkResume.restore() : "Sparse hardware must survive chunk unload without a full world reload";
            chunkResume.onWorker(cpu -> { assert cpu.hardware().equals(profile); assert cpu.registers().getIp() == 2; return null; });
            chunkResume.unload();


            var reloaded = store.load(); var saved = reloaded.mainframe(frame.id).orElseThrow();
            var restoredClaims = new DiskClaims();
            var restored = new MainframeMachine(reloaded, saved, library, restoredClaims); opened.add(restored);
            assert restored.restore();
            assert restored.matchesInstalled(profile, rom.id(), 1, "SHARED_TEST", disk, "stable-wall") : "An empty host layout cache must not cold-reset a matching saved CPU";
            assert !restored.matchesInstalled(profile, rom.id(), 1, "shared_test", disk, "changed-wall");
            assert !restored.matchesInstalled(profile, rom.id(), 2, "shared_test", disk, "stable-wall");
            assert !restored.matchesInstalled(profile, rom.id(), 1, "other", disk, "stable-wall");
            assert saved.powered && saved.paused && restored.instructions() == 1;
            restored.onWorker(cpu -> { assert cpu.registers().getIp() == 2; assert cpu.memory().read8(0, 0x100) == 42; assert cpu.memory().read8(0xA000, 3) == 0xAB; return null; });
            assert reloaded.shared.region("SHARED_TEST").read8(2) == 0xCD;
            assert restored.step() == CPU8086.StepOutcome.INSTRUCTION;
            restored.onWorker(cpu -> { assert cpu.registers().getIp() == 0; return null; });

            var otherRecord = reloaded.allocate(); otherRecord.controllers.add(new WorldRecords.Location("minecraft:the_end", 2, 70, 2));
            var other = new MainframeMachine(reloaded, otherRecord, library, restoredClaims); opened.add(other);
            assert !other.powerOn(profile, rom.id(), 1, "shared_test", disk);
            assert otherRecord.status.contains("1, 64, 1 in minecraft:overworld") : "Disk conflict must identify the active copy";
            restored.powerOff("Powered off");
            assert saved.nvram[3] == (byte) 0xAB;
            assert reloaded.shared.region("shared_test").read8(2) == 0;
            assert other.powerOn(profile, rom.id(), 1, "shared_test", disk);
            Files.delete(folder.resolve(rom.id() + ".rom"));
            other.tick(System.nanoTime(), 50_000_000, ShardSpeed.TIER_1, 111, 6011);
            assert !otherRecord.powered && otherRecord.status.contains("ROM missing or invalid");


            Files.write(folder.resolve(rom.id() + ".rom"), image);
            assert other.powerOn(profile, rom.id(), 1, "shared_test", disk);
            other.pause(true); other.onWorker(cpu -> { cpu.memory().write8(0xA000, 3, 0xAB); return null; }); other.unload();
            var changedState = CPU8086.restoreState(otherRecord.execution, reloaded.shared.region("shared_test"));
            changedState.memory().loadNvram(new byte[CPUMemory.NVRAM_SIZE]); otherRecord.execution = changedState.saveState();
            var rejected = new MainframeMachine(reloaded, otherRecord, library, restoredClaims); opened.add(rejected);
            assert !rejected.restore() && otherRecord.faulted && otherRecord.powered && otherRecord.paused;
            assert otherRecord.nvram[3] == (byte) 0xAB : "An inconsistent execution snapshot must not erase retained NVRAM";
            image[0x3FFF0] = 0x62; var bad = library.upload(image, "Bad opcode", "Fault check");
            var badRecord = reloaded.allocate(); badRecord.controllers.add(new WorldRecords.Location("minecraft:overworld", 3, 64, 3));
            var fault = new MainframeMachine(reloaded, badRecord, library, restoredClaims); opened.add(fault);
            assert fault.powerOn(profile, bad.id(), 3, "fault", null);
            fault.onWorker(cpu -> { cpu.memory().write8(0, 0x100, 99); cpu.memory().write8(0xA000, 3, 0xEE); cpu.memory().write8(0xB000, 0, 0x55); return null; });
            try { fault.step(); throw new AssertionError("Expected CPU fault"); } catch (UnsupportedOperationException expected) { }
            fault.capture();
            assert badRecord.faulted && badRecord.powered && badRecord.paused;
            assert reloaded.shared.region("fault").read8(0) == 0x55 : "A CPU fault must retain powered shared memory";
            assert badRecord.faultCs == 0xFFFF && badRecord.faultIp == 0;
            store.save(reloaded);
            var faultWorld = store.load(); var persistedFault = faultWorld.mainframe(badRecord.id).orElseThrow();
            assert persistedFault.faulted && persistedFault.powered && persistedFault.paused : "A saved fault must stay paused after reopening";
            var faultResume = new MainframeMachine(faultWorld, persistedFault, library, new DiskClaims()); opened.add(faultResume);
            assert faultResume.restore();
            try { faultResume.step(); throw new AssertionError("Faulted CPU executed"); } catch (IllegalStateException expected) { }
            faultResume.reset();
            faultResume.onWorker(cpu -> {
                assert cpu.memory().read8(0, 0x100) == 99 && cpu.memory().read8(0xA000, 3) == 0xEE;
                assert cpu.memory().read8(0xB000, 0) == 0x55 && cpu.registers().getIp() == 0;
                return null;
            });
            assert !persistedFault.faulted && persistedFault.powered && !persistedFault.paused;

            var backgroundRecord = reloaded.allocate(); backgroundRecord.controllers.add(new WorldRecords.Location("minecraft:overworld", 4, 64, 4));
            var background = new MainframeMachine(reloaded, backgroundRecord, library, restoredClaims); opened.add(background);
            assert background.powerOn(profile, bad.id(), 3, "background_fault", null);
            background.tick(System.nanoTime(), 1_000_000_000, ShardSpeed.TIER_3, 112, 6012);
            awaitIp(background, 1);
            background.capture(); assert backgroundRecord.faulted && backgroundRecord.powered && backgroundRecord.paused;
            background.reset();
            assert !backgroundRecord.faulted && !backgroundRecord.paused : "Reset clears the fault and starts execution";
            background.onWorker(cpu -> { cpu.jumpTo(0, 0x200); cpu.memory().write8(0, 0x200, 0x90); cpu.memory().write8(0, 0x201, 0xF4); return null; });
            background.tick(System.nanoTime(), 1_000_000_000, ShardSpeed.TIER_3, 113, 6013);
            awaitIp(background, 0x202); background.pause(true);
            assert background.instructions() == 2 : "Reset must replace the failed worker while retaining the CPU";
            background.onWorker(cpu -> { assert cpu.isHalted(); return null; });
            background.nmi(); assert background.step() == CPU8086.StepOutcome.EVENT;
            background.onWorker(cpu -> { assert !cpu.isHalted(); return null; });

            var config = MachineConfig.load(folder.resolve("machine.properties"));
            assert config.speed(1).equals(ShardSpeed.TIER_1) && config.speed(3).equals(ShardSpeed.TIER_3);
            Files.writeString(folder.resolve("machine.properties"), "shard1.stepsPerTick=2\nshard1.batchesPerTick=3\n");
            try { MachineConfig.load(folder.resolve("machine.properties")); throw new AssertionError("Bad shard config accepted"); }
            catch (java.io.IOException expected) { }

            var outputFrame = world.allocate(); outputFrame.paused = true;
            outputFrame.controllers.add(new WorldRecords.Location("minecraft:overworld", 5, 64, 5));
            var outputProfile = new EnumMap<Hardware, Integer>(hardware); outputProfile.put(Hardware.GPU, 1);
            var outputMachine = new MainframeMachine(world, outputFrame, library, claims); opened.add(outputMachine);
            assert outputMachine.powerOn(new HardwareProfile(outputProfile), rom.id(), 1, "output_cache", null);
            outputMachine.onWorker(cpu -> { cpu.io().write8(1, 15); cpu.io().write8(0x101, 0x80); return null; });
            outputMachine.outputs();
            assert outputFrame.redstoneOutputs[0] == 15 && outputFrame.signOutputs[0][1] == (byte) 0x80;
            long signVersion = outputFrame.signVersions[0];
            outputMachine.onWorker(cpu -> { cpu.io().write8(1, 7); cpu.io().write8(0x101, 0xE4); return null; });
            outputMachine.unload();
            assert outputFrame.powered && outputFrame.redstoneOutputs[0] == 7 && outputFrame.signOutputs[0][1] == (byte) 0xE4;
            assert outputFrame.signVersions[0] > signVersion : "Unloading must retain pending dirty sign output";
            assert outputMachine.restore(); outputMachine.reset(); outputMachine.outputs();
            assert outputFrame.redstoneOutputs[0] == 7 && outputFrame.signOutputs[0][1] == (byte) 0xE4 : "CPU reset preserves bridge outputs";
            outputMachine.powerOff("Power cycle cache check");
            assert outputFrame.redstoneOutputs[0] == 7 : "Power-off ports retain the bridge state";
            assert outputMachine.powerOn(new HardwareProfile(outputProfile), rom.id(), 1, "output_cache", null);
            assert outputFrame.redstoneOutputs[0] == 0 && outputFrame.signOutputs[0][1] == 0 : "Cold start must expose reset I/O rather than stale cache";
            var leases = new DebuggerLeases(); UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
            leases.open(1, a); leases.open(1, b); leases.open(1, c); leases.open(1, a);
            assert leases.canControl(1, a) && !leases.canControl(1, b);
            leases.close(1, a); assert leases.owner(1).orElseThrow().equals(b);
            leases.disconnect(b); assert leases.owner(1).orElseThrow().equals(c);
            leases.close(1, c); assert leases.canControl(1, a);
        } finally {
            for (var machine : opened) machine.unload();
            try (var files = Files.list(folder)) { for (Path file : files.toList()) Files.delete(file); }
            Files.delete(folder);
        }
        System.out.println("Machine checks passed: paused timers, exact resume, retained NVRAM/shared memory, disk conflicts, ROM loss, faults and lease handover");
    }

    private static void awaitIp(MainframeMachine machine, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (machine.onWorker(cpu -> cpu.registers().getIp()) == expected) return;
            Thread.sleep(2);
        }
        throw new AssertionError("CPU did not reach expected IP " + expected);
    }
}

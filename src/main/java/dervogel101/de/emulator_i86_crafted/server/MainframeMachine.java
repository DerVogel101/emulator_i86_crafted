package dervogel101.de.emulator_i86_crafted.server;

import dervogel101.de.CPU8086;
import dervogel101.de.CpuHostBridge;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.i86.runtime.*;
import dervogel101.de.state.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

/** One loaded mainframe. World access stays on its host; only the CPU worker touches execution state. */
public final class MainframeMachine {
    private static final CPUIO.OutputChanges NO_OUTPUT = new CPUIO.OutputChanges(Map.of(), Map.of());
    private final WorldRecords world;
    public final WorldRecords.Mainframe record;
    private final RomLibrary roms;
    private final DiskClaims disks;
    private final AtomicLong instructions = new AtomicLong();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final AtomicBoolean faultLogged = new AtomicBoolean();
    private CPU8086 cpu;
    private BatchedCpuWorker worker;
    private volatile Throwable failure;
    private volatile int failureCs, failureIp;
    private volatile boolean debuggerAttached, bypassInt3;
    private volatile int targetCs = -1, targetIp;
    private volatile String debuggerStop;
    private volatile CpuActivity.Snapshot activity = CpuActivity.Snapshot.EMPTY;
    private final int[] hostActivity = new int[Hardware.values().length];
    public CpuActivity.Snapshot activity() { return activity; }
    public void activity(Hardware hardware, int lamps) { hostActivity[hardware.ordinal()] |= lamps; }
    public int takeActivity(Hardware hardware) {
        int index = hardware.ordinal(), result = hostActivity[index]; hostActivity[index] = 0; return result;
    }

    public MainframeMachine(WorldRecords world, WorldRecords.Mainframe record, RomLibrary roms, DiskClaims disks) {
        this.world = world; this.record = record; this.roms = roms; this.disks = disks;
        instructions.set(record.instructions);
    }

    public boolean powerOn(HardwareProfile hardware, UUID rom, int shard, String memoryId, UUID disk) {
        if (record.powered || record.faulted) return record.powered;
        targetCs = -1; debuggerStop = null; bypassInt3 = false;
        if (shard < 0 || shard > 3) throw new IllegalArgumentException("Invalid speed shard tier");
        try { if (!memoryId.isEmpty()) memoryId = SharedMemoryGroups.normalize(memoryId); }
        catch (IllegalArgumentException invalidId) { record.status = "Powered off: " + invalidId.getMessage(); return false; }
        record.hardware = hardware; record.romId = rom; record.shardTier = shard; record.diskId = disk;
        record.sharedId = memoryId;
        String invalid = hardware.validationError().orElse(null);
        if (record.conflicted()) invalid = "Duplicate controller ID: " + record.controllers;
        else if (shard < 1 || shard > 3) invalid = "No valid speed shard";
        else if (rom == null) invalid = "No valid ROM chip";
        else if (hardware.has(Hardware.SHARED_MEMORY) && (memoryId.isEmpty() || record.sharedReselection)) invalid = "Select a shared-memory ID";
        if (invalid != null) { record.status = "Powered off: " + invalid; return false; }
        if (hardware.has(Hardware.SHARED_MEMORY)) {
            try { record.sharedId = SharedMemoryGroups.normalize(memoryId); }
            catch (IllegalArgumentException invalidId) { record.status = "Powered off: " + invalidId.getMessage(); return false; }
        }
        try {
            if (!claimDisk()) return false;
            byte[] image = roms.read(rom);
            SharedMemoryRegion shared = hardware.has(Hardware.SHARED_MEMORY)
                    ? world.shared.powerOn(record.id, record.sharedId) : new SharedMemoryRegion();
            cpu = new CPU8086(shared); cpu.configureHardware(hardware);
            cpu.hostBridge().loadRom(image); cpu.hostBridge().loadNvram(record.nvram);
            if (disk != null && hardware.has(Hardware.DISK)) {
                var storage = new IoStorageDevice(); storage.load(world.disk(disk)); cpu.hostBridge().submitStorageDevice(storage);
            }
            cpu.beforeBatch();
            record.powered = true; record.faulted = false; record.status = record.paused ? "Paused" : "Running";
            instructions.set(0); record.instructions = 0; cacheOutputs(cpu); record.execution = cpu.saveState();
            startWorker(); return true;
        } catch (IOException | IllegalArgumentException problem) {
            world.shared.powerOff(record.id); disks.release(record.id); cpu = null; record.powered = false;
            record.status = "Powered off: " + problem.getMessage(); return false;
        }
    }

    /** Reattach a loaded controller without a power transition or loss of shared membership. */
    public boolean restore() {
        if (!record.powered) return false;
        if (record.conflicted()) { powerOff("Duplicate controller ID: " + record.controllers); return false; }
        byte[] image;
        try { image = roms.read(record.romId); }
        catch (IOException problem) { powerOff("Powered off: " + problem.getMessage()); return false; }
        if (!claimDisk()) { powerOff(record.status); return false; }
        try {
            SharedMemoryRegion region = record.hardware.has(Hardware.SHARED_MEMORY)
                    ? world.shared.region(record.sharedId) : new SharedMemoryRegion();
            CPU8086 restored = CPU8086.restoreState(record.execution, region);
            if (!restored.hardware().equals(record.hardware) || !Arrays.equals(image, restored.memory().romImage())
                    || !Arrays.equals(record.nvram, restored.memory().snapshotNvram().bytes()))
                throw new IOException("Saved CPU hardware, ROM or NVRAM reference does not match");
            var storage = restored.io().storageDevice();
            boolean attached = record.hardware.has(Hardware.DISK) && record.diskId != null;
            if (attached != (storage != null) || attached && !Arrays.equals(world.disk(record.diskId), storage.snapshot().bytes()))
                throw new IOException("Saved CPU disk does not match its retained storage");
            cpu = restored; cacheOutputs(cpu); startWorker();
            if (!record.faulted) record.status = record.paused ? "Paused" : "Running";
            return true;
        } catch (IOException | IllegalArgumentException problem) {
            record.faulted = true; record.paused = true;
            record.status = "Saved execution state invalid; power cycle required: " + problem.getMessage(); return false;
        }
    }

    public boolean matchesInstalled(HardwareProfile hardware, UUID rom, int shard, String memory, UUID disk, String structureKey) {
        return record.hardware.equals(hardware) && Objects.equals(record.romId, rom) && record.shardTier == shard
                && (!hardware.has(Hardware.SHARED_MEMORY) || record.sharedId.equalsIgnoreCase(memory))
                && (!hardware.has(Hardware.DISK) || Objects.equals(record.diskId, disk))
                && (record.structureKey.isEmpty() || record.structureKey.equals(structureKey));
    }

    private boolean claimDisk() {
        if (record.diskId == null || !record.hardware.has(Hardware.DISK)) return true;
        String location = record.controllers.stream().findFirst().map(Object::toString).orElse("unloaded controller " + record.id);
        var conflict = disks.claim(record.diskId, record.id, location);
        if (conflict.isEmpty()) return true;
        var active = conflict.get();
        record.status = "Powered off: disk already active on mainframe " + active.mainframe() + " at " + active.location();
        return false;
    }

    private void startWorker() {
        cpu.activity().enable(); activity = CpuActivity.Snapshot.EMPTY;
        failure = null; faultLogged.set(false);
        worker = new BatchedCpuWorker("i86-mainframe-" + record.id, Long.hashCode(record.id * 0x9E3779B97F4A7C15L), cpu::beforeBatch, () -> {
            var result = execute(cpu, true);
            return switch (result) {
                case YIELD_BATCH -> TickBudget.Outcome.YIELD_BATCH;
                case YIELD_TICK -> TickBudget.Outcome.YIELD_TICK;
                default -> TickBudget.Outcome.CONTINUE;
            };
        }, problem -> failure = problem);
    }

    private CPU8086.StepOutcome execute(CPU8086 core, boolean debug) {
        if (debug && debuggerAttached) {
            int cs = core.registers().getCs(), ip = core.registers().getIp();
            if (targetCs == cs && targetIp == ip) { debuggerStop = "Cursor reached"; return CPU8086.StepOutcome.YIELD_TICK; }
            if (!bypassInt3 && !core.isHalted() && core.memory().read8(cs, ip) == 0xCC) {
                debuggerStop = "INT 3 breakpoint"; return CPU8086.StepOutcome.YIELD_TICK;
            }
            bypassInt3 = false;
        }
        int cs = core.registers().getCs(), ip = core.registers().getIp();
        try {
            var result = core.step();
            if (result == CPU8086.StepOutcome.INSTRUCTION || result == CPU8086.StepOutcome.YIELD_BATCH || result == CPU8086.StepOutcome.YIELD_TICK)
                instructions.incrementAndGet();
            return result;
        } catch (RuntimeException | Error problem) {
            failureCs = cs; failureIp = ip; faultLogged.set(false); failure = problem; throw problem;
        }
    }

    private void latchFault() {
        if (failure == null) return;
        stopWork(); record.faulted = true; record.paused = true; record.faultCs = failureCs; record.faultIp = failureIp;
        record.status = "Error at %04X:%04X: %s".formatted(failureCs, failureIp, failure.getMessage());
        if (cpu != null && faultLogged.compareAndSet(false, true)) cpu.hostBridge().reportHostError(record.status + "\n");
    }

    private void latchDebuggerStop() {
        String stopped = debuggerStop;
        if (stopped != null && debuggerAttached && !record.faulted) { pause(true); record.status = stopped; }
    }

    public void tick(long start, long duration, ShardSpeed speed, long gameTime, long dayTime) {
        if (!record.powered || worker == null) return;
        latchFault();
        latchDebuggerStop();
        if (!roms.available(record.romId)) { powerOff("Powered off: ROM missing or invalid: " + record.romId); return; }
        cpu.hostBridge().submitWorldTime(gameTime, dayTime);
        if (refreshQueued.compareAndSet(false, true)) worker.submit(() -> {
            try { activity = cpu.activity().drain(gameTime, cpu.isHalted()); cpu.beforeTick(); }
            catch (RuntimeException | Error problem) {
                failureCs = cpu.registers().getCs(); failureIp = cpu.registers().getIp(); faultLogged.set(false); failure = problem; throw problem;
            }
            finally { refreshQueued.set(false); }
            return null;
        });
        if (!record.paused && !record.faulted) worker.beginTick(start, duration, speed);
    }

    public CpuHostBridge bridge() { return cpu == null ? null : cpu.hostBridge(); }
    public CPUIO.OutputChanges outputs() {
        var changes = cpu == null ? NO_OUTPUT : cpu.hostBridge().pollChangedOutputs();
        record.acceptOutputs(changes);
        return changes;
    }
    private void cacheOutputs(CPU8086 core) {
        for (int slot = 0; slot < 128; slot++) {
            record.redstoneOutputs[slot] = (byte) core.io().read8(slot * 2 + 1);
            byte[] sign = new byte[128];
            for (int offset = 0; offset < 128; offset++) sign[offset] = (byte) core.io().read8(0x100 + slot * 0x100 + offset);
            if (!Arrays.equals(record.signOutputs[slot], sign)) {
                System.arraycopy(sign, 0, record.signOutputs[slot], 0, 128); record.signVersions[slot]++;
            }
        }
    }
    public long instructions() { return instructions.get(); }
    public void stopWork() { if (worker != null) worker.stop(); }

    public void capture() {
        if (worker == null || cpu == null) return;
        stopWork();
        record Capture(byte[] execution, byte[] nvram, byte[] disk, Throwable fault) {}
        Capture captured = onWorker(core -> {
            try {
                Throwable fault = failure;
                if (fault != null && faultLogged.compareAndSet(false, true))
                    core.hostBridge().reportHostError("Error at %04X:%04X: %s\n".formatted(failureCs, failureIp, fault.getMessage()));
                record.acceptOutputs(core.hostBridge().pollChangedOutputs());
                cacheOutputs(core);
                byte[] execution = core.saveState();
                byte[] disk = record.diskId != null && record.hardware.has(Hardware.DISK) && core.io().storageDevice() != null
                        ? core.io().storageDevice().snapshot().bytes() : null;
                return new Capture(execution, core.memory().snapshotNvram().bytes(), disk, fault);
            } catch (IOException problem) { throw new IllegalStateException("Cannot capture mainframe " + record.id, problem); }
        });
        record.execution = captured.execution(); record.nvram = captured.nvram();
        if (captured.disk() != null) world.updateDisk(record.diskId, captured.disk());
        record.instructions = instructions.get();
        if (captured.fault() != null) {
            latchFault();
        }
        latchDebuggerStop();
    }

    public void unload() {
        try { capture(); }
        finally {
            if (worker != null) { worker.close(); worker = null; }
            cpu = null;
        }
    }

    public void powerOff(String reason) {
        if (cpu != null && failure == null) cpu.hostBridge().reportHostError(reason + "\n");
        try { unload(); }
        finally {
            world.shared.powerOff(record.id); disks.release(record.id);
            record.powered = false; record.status = reason;
        }
    }

    public void pause(boolean paused) {
        if (!paused && record.faulted) throw new IllegalStateException("Reset the faulted CPU before running it");
        if (paused) { targetCs = -1; debuggerStop = null; bypassInt3 = false; }
        else if (record.status.equals("INT 3 breakpoint")) bypassInt3 = true;
        record.paused = paused; if (paused) stopWork();
        if (record.powered && !record.faulted) record.status = paused ? "Paused" : "Running";
    }

    public void nmi() {
        if (!record.powered || worker == null) throw new IllegalStateException("Mainframe is powered off or unloaded");
        cpu.hostBridge().submitNmiPulse();
    }

    public CPU8086.StepOutcome step() {
        if (!record.powered || worker == null || record.faulted) throw new IllegalStateException("Mainframe is powered off, unloaded or faulted");
        pause(true);
        try {
            return onWorker(core -> { core.beforeBatch(); return execute(core, false); });
        } catch (RuntimeException problem) { latchFault(); throw problem; }
    }

    public void reset() {
        if (!record.powered) { record.faulted = false; return; }
        if (worker == null) throw new IllegalStateException("Execution state is unavailable; power cycle the mainframe");
        pause(true); onWorker(core -> { core.reset(); return null; });
        worker.close(); record.faulted = false; record.faultCs = record.faultIp = 0; startWorker(); pause(false);
    }

    public record DebugTarget(int cs, int ip) {}
    public DebugTarget debugTarget() { return targetCs < 0 ? null : new DebugTarget(targetCs, targetIp); }
    public void debugger(boolean attached) {
        debuggerAttached = attached;
        if (!attached) { targetCs = -1; debuggerStop = null; bypassInt3 = false; }
    }
    public void runTo(int cs, int ip) {
        if ((cs & ~0xFFFF) != 0 || (ip & ~0xFFFF) != 0) throw new IllegalArgumentException("Invalid CS:IP");
        boolean bypass = record.status.equals("INT 3 breakpoint");
        pause(true);
        onWorker(core -> null);
        targetCs = cs; targetIp = ip; bypassInt3 = bypass;
        pause(false);
        record.status = "Running to cursor";
    }
    public void stepOver() {
        pause(true);
        var row = onWorker(core -> dervogel101.de.launcher.Disassembler8086.decode(core.memory(), core.registers().getCs(), core.registers().getIp()));
        if (row.call()) runTo(row.cs(), row.nextIp()); else step();
    }
    public void setPc(int cs, int ip) {
        if ((cs & ~0xFFFF) != 0 || (ip & ~0xFFFF) != 0) throw new IllegalArgumentException("Invalid CS:IP");
        pause(true);
        onWorker(core -> { core.jumpTo(cs, ip); return null; });
        clearEditedFault();
        record.status = "PC set";
    }
    public void skipInstruction() {
        pause(true);
        onWorker(core -> {
            int cs = record.faulted ? record.faultCs : core.registers().getCs();
            int ip = record.faulted ? record.faultIp : core.registers().getIp();
            var row = dervogel101.de.launcher.Disassembler8086.decode(core.memory(), cs, ip);
            core.jumpTo(cs, row.nextIp());
            return null;
        });
        clearEditedFault();
        record.status = "Instruction skipped";
    }
    private void clearEditedFault() {
        if (failure != null) { worker.close(); startWorker(); }
        record.faulted = false; record.faultCs = record.faultIp = 0;
    }

    public <T> T onWorker(Function<CPU8086, T> action) {
        if (worker == null) throw new IllegalStateException("Mainframe is unloaded");
        try { return worker.submit(() -> action.apply(cpu)).get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(failed.getCause());
        }
    }
}

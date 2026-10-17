package dervogel101.de.i86.runtime;

import dervogel101.de.CPU8086;
import dervogel101.de.state.IoStorageDevice;

public final class IndicatorSelfTest {
    private IndicatorSelfTest() {}
    public static void run() throws Exception {
        var cpu = new CPU8086(); cpu.activity().enable();
        var io = cpu.io();
        io.activity().drain(1, false);
        io.read8(0); io.write8(1, 7); cpu.hostBridge().pollChangedOutputs();
        assert cpu.activity().drain(2, false).device(Hardware.CONTROLLER) == 0 : "Host polling is not guest I/O";
        io.readGuest8(64); io.writeGuest8(65, 7); io.writeGuest8(65, 7);
        io.readGuest8(0x2100); io.writeGuest8(0x2100, 0);
        var accesses = cpu.activity().drain(3, false);
        assert accesses.device(Hardware.CONTROLLER) == 6 && (accesses.device(Hardware.CONTROLLER) & 1) == 0;
        assert accesses.inputs()[32] == 1 && accesses.outputs()[32] == 2 && accesses.inputs()[0] == 0;
        assert accesses.signs()[32] == 3 : "Same-value writes and reads are both activity";
        assert cpu.activity().drain(4, false).device(Hardware.GPIO) == 0 : "Inactive tick clears activity";
        assert accesses.inputs()[32] == 1 : "Published snapshots must remain unchanged";

        var disk = new IoStorageDevice(); cpu.hostBridge().submitStorageDevice(disk); cpu.beforeInstruction();
        io.write8(0x8164, 2); cpu.beforeInstruction();
        assert cpu.activity().drain(5, false).device(Hardware.DISK) == 2 : "Identical disk write is still an access";
        io.write8(0x8164, 1); cpu.beforeInstruction();
        assert cpu.activity().drain(6, false).device(Hardware.DISK) == 1;

        cpu.memory().read8(0xa000, 0); cpu.memory().write8(0xa000, 0, 0);
        assert cpu.activity().drain(7, false).device(Hardware.NVRAM) == 0 : "Host memory inspection is not CPU access";
        cpu.memory().write8(0, 0, 0xa0); cpu.memory().write16(0, 1, 0); // MOV AL,[0000]
        cpu.memory().write8(0, 3, 0xa2); cpu.memory().write16(0, 4, 0); // MOV [0000],AL
        cpu.registers().setCs(0); cpu.registers().setIp(0); cpu.registers().setDs(0xa000);
        cpu.step(); cpu.step();
        assert cpu.activity().drain(8, false).device(Hardware.NVRAM) == 3;
        cpu.memory().write8(0, 0, 0xa1); cpu.memory().write8(0, 3, 0xa3); // Word shared-memory fast paths
        cpu.registers().setIp(0); cpu.registers().setDs(0xb000); cpu.step(); cpu.step();
        assert cpu.activity().drain(9, false).device(Hardware.SHARED_MEMORY) == 3;

        io.writeGuest8(0x811a, 1); io.writeGuest8(0x811c, 1); // Timer without IRQ enabled
        cpu.hostBridge().submitWorldTime(0, 0); cpu.beforeTick();
        cpu.activity().drain(10, false);
        cpu.hostBridge().submitWorldTime(1, 1); cpu.beforeTick();
        assert cpu.activity().drain(11, false).device(Hardware.CLOCK) == 4 : "Expiry does not require an IRQ";
        cpu.hostBridge().submitIrq(0, 32); cpu.beforeInstruction();
        assert cpu.activity().drain(12, false).inputs()[32] == 4;
        io.write8(0xff02, 1); cpu.beforeInstruction();
        assert cpu.activity().drain(13, false).device(Hardware.INTERRUPTS) == 4;

        cpu.hostBridge().submitIrq(0, 1); cpu.registers().setIF(true); cpu.step();
        assert (cpu.activity().drain(14, false).device(Hardware.INTERRUPTS) & 2) != 0 : "IRQ acceptance has a distinct indicator";
        cpu.hostBridge().submitNmiPulse(); cpu.step();
        assert cpu.activity().drain(15, false).device(Hardware.NMI) == 6 : "NMI queued and accepted";
        cpu.reset();
        assert cpu.activity().drain(16, false).device(Hardware.RESET) == 6;
        cpu.memory().write8(0, 0, 0x9b); // WAIT
        cpu.registers().setCs(0); cpu.registers().setIp(0);
        cpu.hostBridge().submitTestPin(true); cpu.step();
        assert cpu.activity().drain(17, false).waiting();
        cpu.hostBridge().submitTestPin(false); cpu.step();
        var resumed = cpu.activity().drain(18, false);
        assert !resumed.waiting() && resumed.device(Hardware.TEST) == 4;
        io.write8(0x8130, 'A'); io.write8(0x8150, 1); cpu.beforeInstruction();
        assert cpu.activity().drain(19, false).device(Hardware.TERMINAL) == 1;

        var lamps = new IndicatorState.Lamps();
        int busy = IndicatorState.pack(IndicatorState.RUNNING, 1);
        lamps.update(busy, 0);
        for (int tick = 1; tick <= 100; tick++) {
            long now = tick * 50_000_000L;
            lamps.update(busy, now);
            assert lamps.brightness(1, now + 49_999_999) == 1 : "No power-down during consecutive activity";
        }
        long idle = 5_050_000_000L;
        lamps.update(IndicatorState.RUNNING, idle);
        assert lamps.brightness(1, idle + 25_000_000) > 0 && lamps.brightness(1, idle + 25_000_000) < 1;
        lamps.update(busy, idle + 30_000_000);
        assert lamps.brightness(1, idle + 30_000_000) == 1 : "New access cancels the fade";
        lamps.update(IndicatorState.FAILURE, idle + 40_000_000);
        assert lamps.status() == IndicatorState.FAILURE && lamps.brightness(1, idle + 200_000_000) == 0;
        System.out.println("Indicator checks passed: real accesses, host exclusion, channels, persistence-free snapshots, timer expiry, continuous hold and fade");
    }
}

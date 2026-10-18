package dervogel101.de.i86.runtime;

import java.util.Arrays;

/** Transient telemetry owned by the CPU worker; published snapshots never change. */
public final class CpuActivity {
    public static final int FIRST = 1, SECOND = 2, THIRD = 4;
    private static final int[] NO_PORTS = new int[128];
    private final int[] devices = new int[Hardware.values().length];
    private final int[] inputs = new int[128], outputs = new int[128], signs = new int[128];
    private boolean enabled, executing, inputDirty, outputDirty, signDirty, waiting;

    public record Snapshot(long sequence, int[] devices, int[] inputs, int[] outputs, int[] signs,
                           boolean halted, boolean waiting) {
        public static final Snapshot EMPTY = new Snapshot(0, new int[Hardware.values().length],
                NO_PORTS, NO_PORTS, NO_PORTS, false, false);
        public int device(Hardware hardware) { return devices[hardware.ordinal()]; }
    }

    public void enable() { enabled = true; }
    public void executing(boolean value) { executing = value; }
    public void memory(Hardware hardware, int lamps) { if (executing) mark(hardware, lamps); }

    public void mark(Hardware hardware, int lamps) {
        if (enabled) devices[hardware.ordinal()] |= lamps;
    }

    public void readIo(int port) {
        if (!enabled) return;
        mark(Hardware.CONTROLLER, SECOND);
        if (port >= 0x8170 && port <= 0x817f) mark(Hardware.KEYBOARD, FIRST);
        if (port < 0x100 && (port & 1) == 0) {
            inputs[port >>> 1] |= FIRST; inputDirty = true; mark(Hardware.GPIO, FIRST);
        } else if (port >= 0x100 && port <= 0x80ff && (port & 255) < 0x82) {
            signs[(port - 0x100) >>> 8] |= FIRST; signDirty = true; mark(Hardware.GPU, FIRST);
        } else if (port >= 0x8100 && port <= 0x812f) mark(Hardware.CLOCK, FIRST);
    }

    public void writeIo(int port) {
        if (!enabled) return;
        mark(Hardware.CONTROLLER, THIRD);
        if (port >= 0x8170 && port <= 0x817f) mark(Hardware.KEYBOARD, SECOND);
        if (port < 0x100 && (port & 1) != 0) {
            outputs[port >>> 1] |= SECOND; outputDirty = true; mark(Hardware.GPIO, SECOND);
        } else if (port >= 0x100 && port <= 0x80ff && (port & 255) < 0x82) {
            signs[(port - 0x100) >>> 8] |= SECOND; signDirty = true; mark(Hardware.GPU, SECOND);
        } else if (port >= 0x8100 && port <= 0x812f) mark(Hardware.CLOCK, SECOND);
    }

    public void waiting(boolean value) {
        if (waiting && !value) mark(Hardware.TEST, THIRD);
        waiting = value;
    }

    public void redstoneIrq(int slot) {
        if (!enabled || slot >= 128) return;
        inputs[slot] |= THIRD; inputDirty = true; mark(Hardware.GPIO, THIRD);
    }

    public Snapshot drain(long sequence, boolean halted) {
        var result = new Snapshot(sequence, devices.clone(), take(inputs, inputDirty),
                take(outputs, outputDirty), take(signs, signDirty), halted, waiting);
        Arrays.fill(devices, 0);
        inputDirty = outputDirty = signDirty = false;
        return result;
    }

    private static int[] take(int[] ports, boolean dirty) {
        if (!dirty) return NO_PORTS;
        int[] result = ports.clone(); Arrays.fill(ports, 0); return result;
    }
}

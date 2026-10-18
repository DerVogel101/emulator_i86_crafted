package dervogel101.de.launcher;

import dervogel101.de.i86.runtime.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dervogel101.de.launcher.DebuggerBackend.*;

public final class DebuggerProtocolSelfTest {
    public static void run() throws Exception {
        var paused = snapshot(false, "Register secret", (byte) 0xE4);
        byte[] wire = DebuggerProtocol.encode(new DebuggerProtocol.State(9182, true, paused));
        var state = (DebuggerProtocol.State) DebuggerProtocol.event(wire);
        assert state.mainframe() == 9182 && state.control();
        assert state.snapshot().registers().equals("Register secret");
        assert Arrays.equals(state.snapshot().memory(), paused.memory());
        assert state.snapshot().devices().timerIndex() == 5;
        assert state.snapshot().code().getFirst().assembly().equals("YIELD TICK");
        assert state.snapshot().terminalLog().equals(paused.terminalLog());
        assert state.snapshot().images().nvramPath() == null && state.snapshot().images().storagePath() == null;

        byte[] running = DebuggerProtocol.encode(new DebuggerProtocol.State(9182, false, snapshot(true, "Register secret", (byte) 0xE4)));
        byte[] otherDetails = DebuggerProtocol.encode(new DebuggerProtocol.State(9182, false, snapshot(true, "Different secret", (byte) 0x99)));
        assert Arrays.equals(running, otherDetails) : "Running frames must exclude every detailed CPU view";
        assert !new String(running, StandardCharsets.ISO_8859_1).contains("Register secret");
        var live = (DebuggerProtocol.State) DebuggerProtocol.event(running);
        assert live.snapshot().registers().isEmpty() && live.snapshot().code().isEmpty();
        assert Arrays.equals(live.snapshot().memory(), new byte[256]);
        var retained = DebuggerProtocol.retainPausedViews(paused, live.snapshot());
        assert retained.running() && retained.registers().equals(paused.registers());
        assert retained.memory()[0] == (byte) 0xE4;
        assert !live.control();
        var unloaded = (DebuggerProtocol.State) DebuggerProtocol.event(DebuggerProtocol.encode(new DebuggerProtocol.State(9182, true, paused, false)));
        assert !unloaded.detailed() && !unloaded.snapshot().running() && unloaded.snapshot().registers().isEmpty();
        fails(() -> DebuggerProtocol.encode(new DebuggerProtocol.State(9182, true, snapshot(true, "secret", (byte) 1), true)));

        var request = new DebuggerProtocol.Request(DebuggerProtocol.Action.WRITE_SIGN_LINE, 127, 7, 0, "€äÖß");
        assert DebuggerProtocol.request(DebuggerProtocol.encode(request)).equals(request);
        assert DebuggerProtocol.event(DebuggerProtocol.encode(new DebuggerProtocol.Failure("CPU fault"))) instanceof DebuggerProtocol.Failure;
        assert DebuggerProtocol.event(DebuggerProtocol.encode(new DebuggerProtocol.Closed("Disconnected"))) instanceof DebuggerProtocol.Closed;
        var pipe = new ByteArrayOutputStream();
        DebuggerProtocol.writeFrame(pipe, wire); DebuggerProtocol.writeFrame(pipe, running);
        var input = new ByteArrayInputStream(pipe.toByteArray());
        assert Arrays.equals(DebuggerProtocol.readFrame(input), wire);
        assert Arrays.equals(DebuggerProtocol.readFrame(input), running);
        assert DebuggerProtocol.readFrame(input) == null;
        assert wire.length < DebuggerProtocol.MAX_FRAME;

        fails(() -> DebuggerProtocol.encode(new DebuggerProtocol.Request(DebuggerProtocol.Action.RUN, 0, 0, 0, "x".repeat(1025))));
        fails(() -> DebuggerProtocol.encode(new DebuggerProtocol.Closed("x".repeat(4097))));
        fails(() -> DebuggerProtocol.request(DebuggerProtocol.encode(new DebuggerProtocol.Closed("Not a command"))));
        fails(() -> DebuggerProtocol.event(DebuggerProtocol.encode(request)));
        fails(() -> DebuggerProtocol.event(new byte[DebuggerProtocol.MAX_FRAME + 1]));
        fails(() -> DebuggerProtocol.readFrame(new ByteArrayInputStream(new byte[]{0, 2, 0, 1})));
        fails(() -> DebuggerProtocol.readFrame(new ByteArrayInputStream(new byte[]{-1, -1, -1, -1})));
        fails(() -> DebuggerProtocol.readFrame(new ByteArrayInputStream(new byte[]{0, 0, 0})));
        fails(() -> DebuggerProtocol.readFrame(new ByteArrayInputStream(new byte[]{0, 0, 0, 4, 1})));
        var trailing = Arrays.copyOf(wire, wire.length + 1); fails(() -> DebuggerProtocol.event(trailing));
        var version = wire.clone(); version[0] = 1; fails(() -> DebuggerProtocol.event(version));
        var kind = wire.clone(); kind[1] = 99; fails(() -> DebuggerProtocol.event(kind));
        var action = DebuggerProtocol.encode(request); action[2] = (byte) 255; fails(() -> DebuggerProtocol.request(action));
        for (int size : new int[]{0, 1, 2, 3, 11, 16, wire.length / 2, wire.length - 1}) {
            var shortFrame = Arrays.copyOf(wire, size); fails(() -> DebuggerProtocol.event(shortFrame));
        }
        System.out.println("Debugger protocol checks passed: bounded framing, paused views, running-data exclusion, copied views, command/event separation");
    }

    static Snapshot snapshot(boolean running, String registers, byte marker) {
        byte[] memory = new byte[256], io = new byte[256]; Arrays.fill(memory, marker); Arrays.fill(io, marker);
        var devices = new DeviceSnapshot(127, 127, 5, new byte[]{marker, marker}, new byte[130], new byte[5], new byte[3], new byte[5], false, RedstoneIrqMode.ANALOG, 3);
        var images = new ImageState(java.nio.file.Path.of("private-client-file"), true, java.nio.file.Path.of("private-disk-file"), true, true);
        var machine = new MachineState(true, true, HardwareProfile.complete(), 3, "named_region", ShardSpeed.TIER_3);
        var code = List.of(new Disassembler8086.Instruction(0xFFFF, 0, new byte[]{15, 1}, "YIELD", "TICK", false, false));
        return new Snapshot("Mainframe [9182]", "Running or paused", registers, 123, 987654321, 0xFFFF0, memory, 0xFF00, io, code,
                running, false, devices, "€äÖß".repeat(4096), images, true, machine);
    }
    private interface IO { void run() throws IOException; }
    private static void fails(IO action) throws Exception {
        try { action.run(); throw new AssertionError("Malformed debugger data was accepted"); }
        catch (IOException expected) {}
    }
}

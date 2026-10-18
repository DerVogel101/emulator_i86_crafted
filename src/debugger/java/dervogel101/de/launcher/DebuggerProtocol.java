package dervogel101.de.launcher;

import dervogel101.de.i86.runtime.*;
import java.io.*;
import java.util.*;
import static dervogel101.de.launcher.DebuggerBackend.*;

/** Bounded binary messages shared by Minecraft's connection and the JDK-only Swing helper. */
public final class DebuggerProtocol {
    public static final int MAX_FRAME = 128 * 1024;
    private static final int VERSION = 3, STATE = 1, FAILURE = 2, CLOSED = 3, REQUEST = 4;
    public enum Action {
        RUN, RUN_TO, STEP_OVER, PAUSE, STEP, SKIP, SET_PC, RESET, POWER_CYCLE, NMI,
        MEMORY_BASE, IO_BASE, CODE_ADDRESS, FOLLOW_PC, SELECT_CHANNEL, SELECT_SIGN, SELECT_TIMER,
        SEND_IRQ, TIMER_AUTOMATIC, WRITE_TIMER, RESET_TIMER, WRITE_SIGN_LINE, WRITE_MEMORY, WRITE_IO,
        REDSTONE_IRQ, SIGN_IRQ, CLOSE, SELECT_KEYBOARD, KEYBOARD_INPUT
    }
    public record Request(Action action, int a, int b, int c, String text) {
        public Request { Objects.requireNonNull(action); Objects.requireNonNull(text); }
        public Request(Action action, int a, int b, int c) { this(action, a, b, c, ""); }
    }
    public sealed interface Event permits State, Failure, Closed {}
    public record State(long mainframe, boolean control, Snapshot snapshot, boolean detailed) implements Event {
        public State(long mainframe, boolean control, Snapshot snapshot) { this(mainframe, control, snapshot, !snapshot.running() && snapshot.machine().powered()); }
    }
    public record Failure(String message) implements Event {}
    public record Closed(String reason) implements Event {}
    private DebuggerProtocol() {}

    public static byte[] encode(Request request) throws IOException {
        return encode(REQUEST, out -> {
            out.writeByte(request.action().ordinal());
            out.writeInt(request.a()); out.writeInt(request.b()); out.writeInt(request.c());
            text(out, request.text(), 1024);
        });
    }
    public static Request request(byte[] bytes) throws IOException {
        try (var in = input(bytes, REQUEST)) {
            var action = value(Action.values(), in.readUnsignedByte());
            var request = new Request(action, in.readInt(), in.readInt(), in.readInt(), text(in, 1024));
            end(in); return request;
        }
    }
    public static byte[] encode(Event event) throws IOException {
        if (event instanceof State state) return encode(STATE, out -> state(out, state));
        if (event instanceof Failure failure) return encode(FAILURE, out -> text(out, failure.message(), 4096));
        if (event instanceof Closed closed) return encode(CLOSED, out -> text(out, closed.reason(), 4096));
        throw new IOException("Unknown debugger event");
    }
    public static Event event(byte[] bytes) throws IOException {
        try (var in = input(bytes, 0)) {
            Event event = switch (in.readUnsignedByte()) {
                case STATE -> state(in);
                case FAILURE -> new Failure(text(in, 4096));
                case CLOSED -> new Closed(text(in, 4096));
                default -> throw new IOException("Invalid debugger event");
            };
            end(in); return event;
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid debugger state", invalid); }
    }

    public static void writeFrame(OutputStream output, byte[] frame) throws IOException {
        length(frame.length);
        var out = new DataOutputStream(output); out.writeInt(frame.length); out.write(frame); out.flush();
    }
    public static byte[] readFrame(InputStream input) throws IOException {
        int first = input.read(); if (first < 0) return null;
        var in = new DataInputStream(input);
        int size = first << 24 | in.readUnsignedByte() << 16 | in.readUnsignedByte() << 8 | in.readUnsignedByte();
        length(size);
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new EOFException("Incomplete debugger frame");
        return bytes;
    }

    private static void state(DataOutputStream out, State state) throws IOException {
        if (state.mainframe() <= 0) throw new IOException("Invalid mainframe ID");
        out.writeLong(state.mainframe()); out.writeBoolean(state.control()); out.writeBoolean(state.detailed());
        var s = state.snapshot();
        text(out, s.romName(), 512); text(out, s.status(), 2048);
        nonnegative(s.instructionsPerSecond()); nonnegative(s.totalInstructions());
        out.writeLong(s.instructionsPerSecond()); out.writeLong(s.totalInstructions());
        out.writeBoolean(s.running()); out.writeBoolean(s.faulted()); out.writeBoolean(s.testHigh());
        text(out, s.terminalLog(), 16384); out.writeBoolean(s.images().storageConnected());
        var m = s.machine();
        out.writeBoolean(m.powered()); out.writeBoolean(m.psuHigh());
        for (var part : Hardware.values()) { bounded(m.hardware().count(part), 0, part.maximum); out.writeByte(m.hardware().count(part)); }
        bounded(m.shardTier(), 0, 3); out.writeByte(m.shardTier()); text(out, m.memoryId(), 32);
        out.writeInt(m.speed().stepsPerTick()); out.writeInt(m.speed().batchesPerTick());
        var keyboard = s.devices().keyboard();
        bounded(s.devices().keyboardIndex(), 0, 7); out.writeByte(s.devices().keyboardIndex());
        out.writeBoolean(keyboard.connected()); out.writeBoolean(keyboard.ready()); out.writeBoolean(keyboard.ackPending());
        byte[] queue = keyboard.queue(); bounded(queue.length, 0, 8); out.writeByte(queue.length); out.write(queue);
        // Running, powered-off and unloaded updates carry only the available header state.
        if (state.detailed() && s.running()) throw new IOException("Running debugger state cannot contain detailed views");
        if (!state.detailed()) return;
        text(out, s.registers(), 4096);
        bounded(s.memoryBase(), 0, 0xFFFFF); bounded(s.ioBase(), 0, 0xFFFF);
        out.writeInt(s.memoryBase()); fixed(out, s.memory(), 256); out.writeInt(s.ioBase()); fixed(out, s.io(), 256);
        if (s.code().size() > 25) throw new IOException("Too many disassembly rows");
        out.writeByte(s.code().size());
        for (var row : s.code()) {
            bounded(row.cs(), 0, 0xFFFF); bounded(row.ip(), 0, 0xFFFF);
            out.writeShort(row.cs()); out.writeShort(row.ip());
            byte[] code = row.bytes(); bounded(code.length, 1, 15); out.writeByte(code.length); out.write(code);
            text(out, row.mnemonic(), 128); text(out, row.operands(), 256); out.writeBoolean(row.call()); out.writeBoolean(row.unsupported());
        }
        var d = s.devices();
        bounded(d.channel(), 0, 127); bounded(d.signIndex(), 0, 127); bounded(d.timerIndex(), 0, 5);
        out.writeByte(d.channel()); out.writeByte(d.signIndex()); out.writeByte(d.timerIndex());
        fixed(out, d.redstone(), 2); fixed(out, d.sign(), 130); fixed(out, d.timer(), 5); fixed(out, d.irq(), 3); fixed(out, d.storage(), 5);
        out.writeBoolean(d.timerAutomatic()); out.writeByte(d.redstoneIrq().ordinal());
        bounded(d.signIrqSides(), 0, 3); out.writeByte(d.signIrqSides());
    }
    private static State state(DataInputStream in) throws IOException {
        long id = in.readLong(); if (id <= 0) throw new IOException("Invalid mainframe ID");
        boolean control = in.readBoolean(), detailed = in.readBoolean(); String name = text(in, 512), status = text(in, 2048);
        long rate = in.readLong(), count = in.readLong(); nonnegative(rate); nonnegative(count);
        boolean running = in.readBoolean(), faulted = in.readBoolean(), test = in.readBoolean();
        String log = text(in, 16384); boolean disk = in.readBoolean();
        boolean powered = in.readBoolean(), psu = in.readBoolean();
        var parts = new EnumMap<Hardware, Integer>(Hardware.class);
        for (var part : Hardware.values()) { int amount = in.readUnsignedByte(); bounded(amount, 0, part.maximum); parts.put(part, amount); }
        int tier = in.readUnsignedByte(); bounded(tier, 0, 3);
        var machine = new MachineState(powered, psu, new HardwareProfile(parts), tier, text(in, 32), new ShardSpeed(in.readInt(), in.readInt()));
        var images = new ImageState(null, false, null, false, disk);
        String registers = ""; int memoryBase = 0, ioBase = 0; byte[] memory = new byte[256], io = new byte[256];
        int keyboardIndex = in.readUnsignedByte(); bounded(keyboardIndex, 0, 7);
        boolean connected = in.readBoolean(), ready = in.readBoolean(), pending = in.readBoolean();
        int queued = in.readUnsignedByte(); bounded(queued, 0, 8);
        var keyboard = new KeyboardDevice.Snapshot(connected, fixed(in, queued), ready, pending);
        var code = new ArrayList<Disassembler8086.Instruction>(); var devices = emptyDevices().withKeyboard(keyboardIndex, keyboard);
        if (detailed && running) throw new IOException("Running debugger state contains detailed views");
        if (detailed) {
            registers = text(in, 4096); memoryBase = in.readInt(); bounded(memoryBase, 0, 0xFFFFF); memory = fixed(in, 256);
            ioBase = in.readInt(); bounded(ioBase, 0, 0xFFFF); io = fixed(in, 256);
            int rows = in.readUnsignedByte(); bounded(rows, 0, 25);
            for (int i = 0; i < rows; i++) {
                int cs = in.readUnsignedShort(), ip = in.readUnsignedShort(), size = in.readUnsignedByte(); bounded(size, 1, 15);
                code.add(new Disassembler8086.Instruction(cs, ip, fixed(in, size), text(in, 128), text(in, 256), in.readBoolean(), in.readBoolean()));
            }
            int channel = in.readUnsignedByte(), sign = in.readUnsignedByte(), timer = in.readUnsignedByte();
            bounded(channel, 0, 127); bounded(sign, 0, 127); bounded(timer, 0, 5);
            devices = new DeviceSnapshot(channel, sign, timer, fixed(in, 2), fixed(in, 130), fixed(in, 5), fixed(in, 3), fixed(in, 5),
                    in.readBoolean(), value(RedstoneIrqMode.values(), in.readUnsignedByte()), in.readUnsignedByte(), keyboardIndex, keyboard);
            bounded(devices.signIrqSides(), 0, 3);
        }
        return new State(id, control, new Snapshot(name, status, registers, rate, count, memoryBase, memory, ioBase, io,
                List.copyOf(code), running, faulted, devices, log, images, test, machine), detailed);
    }
    public static DeviceSnapshot emptyDevices() {
        return new DeviceSnapshot(0, 0, 0, new byte[2], new byte[130], new byte[5], new byte[3], new byte[5], true, RedstoneIrqMode.DISABLED, 0, 0, KeyboardDevice.Snapshot.EMPTY);
    }
    public static Snapshot retainPausedViews(Snapshot previous, Snapshot update) {
        if (previous == null) return update;
        return new Snapshot(update.romName(), update.status(), previous.registers(), update.instructionsPerSecond(), update.totalInstructions(),
                previous.memoryBase(), previous.memory(), previous.ioBase(), previous.io(), previous.code(), update.running(), update.faulted(), previous.devices().withKeyboard(update.devices().keyboardIndex(), update.devices().keyboard()),
                update.terminalLog(), update.images(), update.testHigh(), update.machine());
    }

    private interface Writer { void write(DataOutputStream out) throws IOException; }
    private static byte[] encode(int kind, Writer writer) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { out.writeByte(VERSION); out.writeByte(kind); writer.write(out); }
        length(bytes.size()); return bytes.toByteArray();
    }
    private static DataInputStream input(byte[] bytes, int kind) throws IOException {
        length(bytes.length); var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readUnsignedByte() != VERSION || kind != 0 && in.readUnsignedByte() != kind) throw new IOException("Invalid debugger message version or kind");
        return in;
    }
    private static void end(DataInputStream in) throws IOException { if (in.read() != -1) throw new IOException("Trailing debugger data"); }
    private static void length(int size) throws IOException { bounded(size, 2, MAX_FRAME); }
    private static void bounded(int value, int first, int last) throws IOException { if (value < first || value > last) throw new IOException("Debugger value outside bounds"); }
    private static void nonnegative(long value) throws IOException { if (value < 0) throw new IOException("Negative debugger counter"); }
    private static <T> T value(T[] values, int index) throws IOException { bounded(index, 0, values.length - 1); return values[index]; }
    private static void text(DataOutputStream out, String text, int maximum) throws IOException {
        if (text.length() > maximum) throw new IOException("Debugger text too long"); out.writeUTF(text);
    }
    private static String text(DataInputStream in, int maximum) throws IOException {
        String text = in.readUTF(); if (text.length() > maximum) throw new IOException("Debugger text too long"); return text;
    }
    private static void fixed(DataOutputStream out, byte[] bytes, int size) throws IOException {
        if (bytes.length != size) throw new IOException("Invalid debugger byte view"); out.write(bytes);
    }
    private static byte[] fixed(DataInputStream in, int size) throws IOException {
        byte[] bytes = new byte[size]; in.readFully(bytes); return bytes;
    }
}

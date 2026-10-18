package dervogel101.de.emulator_i86_crafted.server;

import dervogel101.de.emulator_i86_crafted.block.ControllerBlockEntity;
import dervogel101.de.emulator_i86_crafted.network.DebuggerPackets;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.i86.runtime.*;
import dervogel101.de.launcher.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.function.BiConsumer;
import static dervogel101.de.launcher.DebuggerBackend.*;

/** Player-authenticated sessions; all world, lease and worker access starts on the server thread. */
public final class ServerDebugger {
    private static final Charset CP1252 = Charset.forName("windows-1252");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("i86-debugger");
    private final ServerMachines owner;
    private final BiConsumer<ServerPlayer, CustomPacketPayload> send;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Map<Long, MainframeMachine.DebugTarget> suspendedTargets = new HashMap<>();
    private static final class Session {
        ServerPlayer player;
        final net.minecraft.server.network.ServerGamePacketListenerImpl connection;
        final long mainframe;
        final UUID token = UUID.randomUUID();
        final DebuggerSnapshots.View view = new DebuggerSnapshots.View();
        int requestTick, requests, lastPublish = -100;
        boolean dirty = true;
        long rateStart = System.nanoTime(), rateCount, rate;
        byte[] terminalSource;
        String savedTerminal = "";
        Session(ServerPlayer player, long mainframe) { this.player = player; connection = player.connection; this.mainframe = mainframe; }
    }
    public ServerDebugger(ServerMachines owner) { this(owner, ServerPlayNetworking::send); }
    public ServerDebugger(ServerMachines owner, BiConsumer<ServerPlayer, CustomPacketPayload> send) { this.owner = owner; this.send = send; }

    public UUID open(ServerPlayer player, ControllerBlockEntity controller) {
        if (controller.isRemoved() || controller.getLevel() != player.level()
                || !player.level().hasChunkAt(controller.getBlockPos()) || player.level().getBlockEntity(controller.getBlockPos()) != controller
                || !player.level().mayInteract(player, controller.getBlockPos())
                || player.distanceToSqr(Vec3.atCenterOf(controller.getBlockPos())) > 64)
            throw new IllegalStateException("Open the debugger at a surviving nearby controller");
        owner.attach(controller);
        var record = owner.records.mainframe(controller.id()).orElseThrow(() -> new IllegalArgumentException("Unknown mainframe ID"));
        var old = sessions.values().stream().filter(session -> session.player.getUUID().equals(player.getUUID()) && session.mainframe == record.id).findFirst().orElse(null);
        if (old != null && old.mainframe == record.id && old.connection == player.connection) { old.player = player; old.dirty = true; return old.token; }
        if (old != null) close(old, "Player connection replaced", true);
        var session = new Session(player, record.id); sessions.put(session.token, session);
        owner.leases.open(record.id, player.getUUID());
        var machine = owner.machine(record.id); if (machine != null) { machine.debugger(true); session.rateCount = machine.instructions(); }
        try { send.accept(player, new DebuggerPackets.Open(session.token)); }
        catch (RuntimeException unavailable) { close(session, "Client connection unavailable", false); throw new IllegalStateException("Debugger client connection unavailable", unavailable); }
        mark(record.id); publish(session); return session.token;
    }
    public void disconnect(UUID player) {
        for (var session : List.copyOf(sessions.values())) if (session.player.getUUID().equals(player)) close(session, "Disconnected", false);
    }
    public void shutdown() { for (var session : List.copyOf(sessions.values())) close(session, "Server stopping", true); }
    private void close(Session session, String reason, boolean notify) {
        if (!sessions.remove(session.token, session)) return;
        owner.leases.close(session.mainframe, session.player.getUUID());
        if (owner.leases.owner(session.mainframe).isEmpty()) suspendedTargets.remove(session.mainframe);
        var machine = owner.machine(session.mainframe);
        if (machine != null) machine.debugger(owner.leases.owner(session.mainframe).isPresent());
        if (notify) event(session, new DebuggerProtocol.Closed(reason));
        mark(session.mainframe);
    }
    public void unloading(MainframeMachine machine) {
        var target = machine.debugTarget();
        if (target != null && machine.record.powered && !machine.record.paused && owner.leases.owner(machine.record.id).isPresent())
            suspendedTargets.put(machine.record.id, target);
        else suspendedTargets.remove(machine.record.id);
    }
    public void restored(MainframeMachine machine) {
        var target = suspendedTargets.remove(machine.record.id);
        if (target != null && machine.record.powered && !machine.record.paused && !machine.record.faulted
                && owner.leases.owner(machine.record.id).isPresent()) {
            machine.debugger(true); machine.runTo(target.cs(), target.ip());
        }
        mark(machine.record.id);
    }
    public void tick(int tick) {
        for (var session : List.copyOf(sessions.values())) if (session.dirty || tick - session.lastPublish >= 4) {
            session.lastPublish = tick; session.dirty = false; publish(session);
        }
    }
    private void mark(long id) { for (var session : sessions.values()) if (session.mainframe == id) session.dirty = true; }
    public void request(ServerPlayer player, UUID token, byte[] bytes) {
        var session = sessions.get(token);
        if (session == null || !session.player.getUUID().equals(player.getUUID()) || session.connection != player.connection) return;
        session.player = player;
        int tick = player.level().getServer().getTickCount();
        if (session.requestTick != tick) { session.requestTick = tick; session.requests = 0; }
        if (++session.requests > 32) { close(session, "Too many debugger requests in one tick", true); return; }
        try {
            if (bytes.length > DebuggerPackets.MAX_REQUEST) throw new IllegalArgumentException("Debugger request too large");
            var request = DebuggerProtocol.request(bytes);
            if (request.action() == DebuggerProtocol.Action.CLOSE) { close(session, "Debugger closed", true); return; }
            var record = owner.records.mainframe(session.mainframe).orElseThrow();
            if (view(session.view, request)) { session.dirty = true; return; }
            if (player.isSpectator() || record.conflicted() || !owner.leases.canControl(record.id, player.getUUID()))
                throw new IllegalStateException("Read only: another player controls this mainframe's debugger");
            action(session, record, request); mark(record.id);
        } catch (IOException | IllegalArgumentException | IllegalStateException invalid) {
            event(session, new DebuggerProtocol.Failure(limit(Objects.toString(invalid.getMessage(), "Debugger request failed"), 4096)));
            session.dirty = true;
        }
    }
    private static boolean view(DebuggerSnapshots.View view, DebuggerProtocol.Request request) {
        int a = request.a(), b = request.b();
        switch (request.action()) {
            case MEMORY_BASE -> view.memoryBase = range(a, 0xFFFFF);
            case IO_BASE -> view.ioBase = range(a, 0xFFFF);
            case CODE_ADDRESS -> { view.codeCs = range(a, 0xFFFF); view.codeIp = range(b, 0xFFFF); view.followPc = false; }
            case FOLLOW_PC -> view.followPc = true;
            case SELECT_CHANNEL -> view.channel = range(a, 127);
            case SELECT_SIGN -> view.sign = range(a, 127);
            case SELECT_TIMER -> view.timer = range(a, 5);
            case SELECT_KEYBOARD -> view.keyboard = range(a, 7);
            case SELECT_PUNCHCARD -> view.punchcard = range(a,31);
            default -> { return false; }
        }
        return true;
    }
    private void action(Session session, WorldRecords.Mainframe record, DebuggerProtocol.Request request) {
        int a = request.a(), b = request.b(), c = request.c();
        var machine = owner.machine(record.id);
        switch (request.action()) {
            case RUN -> {
                if (!record.powered || record.faulted) throw new IllegalStateException("Power on and clear CPU faults before running");
                if (machine != null) machine.pause(false); else { record.paused = false; record.status = "Suspended (chunk unloaded)"; }
            }
            case PAUSE -> {
                if (machine != null) machine.pause(true); else { record.paused = true; record.status = "Paused (chunk unloaded)"; }
            }
            case POWER_CYCLE -> { suspendedTargets.remove(record.id); owner.powerOff(record.id, "Debugger power cycle"); record.faulted = false; record.paused = false; }
            case RESET -> { if (!record.powered) record.faulted = false; else loaded(machine).reset(); }
            case STEP -> loaded(machine).step();
            case STEP_OVER -> loaded(machine).stepOver();
            case RUN_TO -> loaded(machine).runTo(range(a, 0xFFFF), range(b, 0xFFFF));
            case NMI -> loaded(machine).nmi();
            case SET_PC -> { paused(record); loaded(machine).setPc(range(a, 0xFFFF), range(b, 0xFFFF)); session.view.followPc = true; }
            case SKIP -> { paused(record); loaded(machine).skipInstruction(); }
            case WRITE_MEMORY -> {
                paused(record); range(a, 0xFFFFF); range(b, 255);
                if (!DebuggerSnapshots.writableMemory(a)) throw new IllegalArgumentException("Memory address is read only");
                loaded(machine).onWorker(cpu -> { cpu.memory().write8(a >>> 4, a & 15, b); return null; });
            }
            case WRITE_IO -> {
                paused(record); range(a, 0xFFFF); range(b, 255);
                if (!DebuggerSnapshots.writableIo(a)) throw new IllegalArgumentException("I/O address is read only");
                loaded(machine).onWorker(cpu -> { cpu.io().write8(a, b); cpu.beforeInstruction(); return null; });
            }
            case PUNCHCARD_COMMAND -> {
                paused(record); range(a,31); range(b,255); range(c,1);
                if (Integer.bitCount(b) != 1) throw new IllegalArgumentException("One punchcard command required");
                byte[] bytes = b == 2 || b == 8 ? HexFormat.of().parseHex(request.text().replace(" ","")) : new byte[0];
                if ((b == 2 || b == 8) && bytes.length != 32) throw new IllegalArgumentException("Exactly 32 bytes required");
                loaded(machine).onWorker(cpu -> {
                    int base = 0x8200+a*64;
                    cpu.io().write8(base+0x2c,c);
                    for (int i=0;i<bytes.length;i++) cpu.io().write8(base+i,bytes[i]&255);
                    cpu.io().write8(base+32,b); cpu.beforeInstruction(); return null;
                });
            }
            case SEND_IRQ -> {
                paused(record); range(a, 0xFFFF);
                loaded(machine).onWorker(cpu -> { cpu.hostBridge().submitIrq(a); cpu.beforeInstruction(); return null; });
            }
            case KEYBOARD_INPUT -> {
                range(a, 7);
                if (!dervogel101.de.i86.runtime.KeyboardInput.allowedByte(b)) throw new IllegalArgumentException("Invalid keyboard byte");
                if (!record.powered) throw new IllegalStateException("Power on the mainframe before typing");
                var bridge = loaded(machine).bridge();
                if (bridge == null || !record.hardware.has(Hardware.KEYBOARD) || !bridge.keyboardSnapshot(a).connected())
                    throw new IllegalStateException("Keyboard is offline or unbound");
                if (!bridge.submitKeyboardInput(a, b)) throw new IllegalStateException("Keyboard FIFO is full");
            }
            case TIMER_AUTOMATIC -> {
                paused(record); range(a, 5); range(b, 1);
                loaded(machine).onWorker(cpu -> { cpu.hostBridge().submitTimerAutomatic(a, b == 1); cpu.beforeInstruction(); return null; });
            }
            case WRITE_TIMER -> {
                paused(record); range(a, 5); range(b, 0xFFFF); if ((c & ~0x37) != 0) throw new IllegalArgumentException("Invalid timer control");
                loaded(machine).onWorker(cpu -> { int base = 0x8100 + a * 8; cpu.io().write16(base + 2, b); cpu.io().write8(base + 4, c); cpu.beforeInstruction(); return null; });
            }
            case RESET_TIMER -> {
                paused(record); range(a, 5);
                loaded(machine).onWorker(cpu -> { cpu.io().write8(0x8104 + a * 8, 8); cpu.beforeInstruction(); return null; });
            }
            case WRITE_SIGN_LINE -> {
                paused(record); range(a, 127); range(b, 7); String text = request.text();
                if (text.length() > 15 || !CP1252.newEncoder().canEncode(text)) throw new IllegalArgumentException("Sign lines require at most 15 Windows-1252 characters");
                byte[] bytes = text.getBytes(CP1252);
                loaded(machine).onWorker(cpu -> { int base = 0x0101 + a * 0x100 + b * 16;
                    for (int i = 0; i < 15; i++) cpu.io().write8(base + i, i < bytes.length ? bytes[i] & 255 : 0); return null; });
            }
            case REDSTONE_IRQ -> {
                paused(record); range(a, 127); range(b, RedstoneIrqMode.values().length - 1); var mode = RedstoneIrqMode.values()[b];
                loaded(machine);
                configurePorts(session, PortBindings.Kind.REDSTONE, a, mode, 0);
                loaded(machine).onWorker(cpu -> { cpu.hostBridge().submitRedstoneIrqMode(a, mode); cpu.beforeInstruction(); return null; });
            }
            case SIGN_IRQ -> {
                paused(record); range(a, 127); range(b, 3); loaded(machine); configurePorts(session, PortBindings.Kind.SIGN, a, RedstoneIrqMode.DISABLED, b);
                loaded(machine).onWorker(cpu -> { cpu.hostBridge().submitSignIrqSides(a, b); cpu.beforeInstruction(); return null; });
            }
            default -> throw new IllegalArgumentException("Unsupported debugger action");
        }
    }
    private void configurePorts(Session session, PortBindings.Kind kind, int slot, RedstoneIrqMode mode, int sides) {
        for (var entry : owner.records.ports.entries().entrySet()) {
            var port = entry.getValue(); var settings = port.settings();
            if (port.kind() == kind && settings.mainframe() == session.mainframe && settings.slot() == slot)
                owner.ports.configureInterrupts(entry.getKey(), session.mainframe, session.player.getUUID(),
                        kind == PortBindings.Kind.REDSTONE ? mode : settings.irq(), kind == PortBindings.Kind.SIGN ? sides : settings.signSides());
        }
    }
    private static MainframeMachine loaded(MainframeMachine machine) {
        if (machine == null || !machine.record.powered) throw new IllegalStateException("Mainframe is powered off or unloaded"); return machine;
    }
    private static void paused(WorldRecords.Mainframe record) { if (!record.powered || !record.paused) throw new IllegalStateException("Pause a powered CPU before editing"); }
    private static int range(int value, int maximum) { if (value < 0 || value > maximum) throw new IllegalArgumentException("Debugger argument outside bounds"); return value; }
    private void publish(Session session) {
        session.player = session.connection.getPlayer();
        var record = owner.records.mainframe(session.mainframe).orElse(null);
        if (record == null) { close(session, "Mainframe no longer exists", true); return; }
        try {
            var machine = owner.machine(record.id);
            long count = machine == null ? record.instructions : machine.instructions(), now = System.nanoTime();
            if (count < session.rateCount) { session.rateCount = count; session.rateStart = now; session.rate = 0; }
            if (now - session.rateStart >= 1_000_000_000L) {
                session.rate = (long) ((count - session.rateCount) * 1_000_000_000d / (now - session.rateStart));
                session.rateCount = count; session.rateStart = now;
            }
            String log;
            if (machine != null && machine.bridge() != null) log = machine.bridge().terminalText();
            else {
                if (session.terminalSource != record.execution) {
                    try { session.savedTerminal = owner.terminal(record.id); }
                    catch (IllegalStateException invalid) { session.savedTerminal = "Terminal unavailable: " + invalid.getMessage(); }
                    session.terminalSource = record.execution;
                }
                log = session.savedTerminal;
            }
            String name = record.displayName() + " — " + (record.romId == null ? "No ROM" : owner.roms.entry(record.romId).map(RomLibrary.Entry::name).orElse("Missing ROM"));
            String status = record.powered && machine == null ? "Suspended (chunk unloaded)" : record.status;
            var hardware = record.hardware;
            var images = new ImageState(null, false, null, false, hardware.has(Hardware.DISK) && record.diskId != null);
            var state = new MachineState(record.powered, record.powered, hardware, record.shardTier, record.sharedId, owner.shardSpeed(record.shardTier));
            boolean running = record.powered && !record.paused && !record.faulted;
            var snapshot = new Snapshot(limit(name, 512), limit(status, 2048), "", session.rate, count, 0, new byte[256], 0, new byte[256], List.of(),
                    running, record.faulted, DebuggerProtocol.emptyDevices().withKeyboard(session.view.keyboard,
                            machine == null || machine.bridge() == null ? dervogel101.de.i86.runtime.KeyboardDevice.Snapshot.EMPTY
                                    : machine.bridge().keyboardSnapshot(session.view.keyboard)).withPunchcard(session.view.punchcard,
                                    machine == null || machine.bridge() == null ? new byte[64] : machine.bridge().punchcardSnapshot(session.view.punchcard), machine == null || machine.bridge() == null ? "" : machine.bridge().punchcardName(session.view.punchcard)), limit(log, 16384), images, false, state);
            boolean detailed = machine != null && machine.bridge() != null && record.powered && record.paused;
            if (detailed) {
                var header = snapshot;
                snapshot = machine.onWorker(cpu -> DebuggerSnapshots.capture(cpu, header, session.view));
            }
            boolean control = !session.player.isSpectator() && !record.conflicted() && owner.leases.canControl(record.id, session.player.getUUID());
            event(session, new DebuggerProtocol.State(record.id, control, snapshot, detailed));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            event(session, new DebuggerProtocol.Failure(limit(Objects.toString(failure.getMessage(), "Debugger snapshot unavailable"), 4096)));
        }
    }
    private void event(Session session, DebuggerProtocol.Event event) {
        try { send.accept(session.player, new DebuggerPackets.State(session.token, DebuggerProtocol.encode(event))); }
        catch (IOException invalid) {
            close(session, "Debugger state unavailable", false);
            LOG.warn("Cannot encode mainframe {} debugger state", session.mainframe, invalid);
            try { send.accept(session.player, new DebuggerPackets.State(session.token, DebuggerProtocol.encode(new DebuggerProtocol.Closed("Debugger state unavailable")))); }
            catch (IOException | RuntimeException unavailable) { LOG.debug("Debugger client already unavailable", unavailable); }
        } catch (RuntimeException unavailable) {
            close(session, "Client connection unavailable", false);
            LOG.debug("Cannot deliver mainframe {} debugger update", session.mainframe, unavailable);
        }
    }
    private static String limit(String value, int maximum) { return value.length() > maximum ? value.substring(0, maximum) : value; }
}

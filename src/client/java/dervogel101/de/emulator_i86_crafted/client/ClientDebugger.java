package dervogel101.de.emulator_i86_crafted.client;

import dervogel101.de.emulator_i86_crafted.network.DebuggerPackets;
import dervogel101.de.launcher.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.ConcurrentHashMap;

/** A private child pipe is relayed only over the current authenticated Minecraft connection. */
public final class ClientDebugger {
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private ClientDebugger() {}
    public static boolean isOpen() { return SESSIONS.values().stream().anyMatch(session -> !session.stopped.get()); }
    static void initialize() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EPOCH.incrementAndGet();
            var old = List.copyOf(SESSIONS.values()); SESSIONS.clear(); old.forEach(session -> session.stop(false));
        });
        ClientPlayNetworking.registerGlobalReceiver(DebuggerPackets.Open.TYPE, (packet, context) -> {
            var next = new Session(context.client(), packet.session());
            if (SESSIONS.putIfAbsent(packet.session(), next) != null) return;
            Thread.ofPlatform().daemon().name("i86-debugger-helper").start(next::start);
        });
        ClientPlayNetworking.registerGlobalReceiver(DebuggerPackets.State.TYPE, (packet, context) -> {
            var session = SESSIONS.get(packet.session()); if (session != null) session.accept(packet.bytes());
        });
    }
    private static final class Session {
        final Minecraft client;
        final Object connection;
        final UUID token;
        final long epoch = EPOCH.get();
        final AtomicBoolean stopped = new AtomicBoolean();
        final Object pending = new Object();
        final ArrayDeque<byte[]> notices = new ArrayDeque<>();
        byte[] latest;
        volatile Process process;
        volatile boolean serverClosed;
        Session(Minecraft client, UUID token) { this.client = client; connection = client.getConnection(); this.token = token; }
        boolean connected() { return epoch == EPOCH.get() && client.getConnection() == connection && connection != null; }
        void accept(byte[] bytes) {
            try {
                var event = DebuggerProtocol.event(bytes);
                synchronized (pending) {
                    if (stopped.get()) return;
                    if (event instanceof DebuggerProtocol.State) latest = bytes;
                    else {
                        if (notices.size() >= 8) throw new IOException("Debugger helper stopped consuming notifications");
                        if (event instanceof DebuggerProtocol.Closed) serverClosed = true;
                        notices.add(bytes);
                    }
                    pending.notifyAll();
                }
            } catch (IOException failure) { fail(failure.getMessage()); }
        }
        void start() {
            try {
                String executable = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
                String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
                var child = new ProcessBuilder(java, "-Djava.awt.headless=false", "-cp", RemoteLauncherMain.helperClasspath(), RemoteLauncherMain.class.getName())
                        .redirectError(ProcessBuilder.Redirect.INHERIT).start();
                synchronized (pending) {
                    process = child;
                    if (stopped.get() || SESSIONS.get(token) != this || !connected()) { child.destroy(); return; }
                }
                Thread.ofPlatform().daemon().name("i86-debugger-requests").start(this::read);
                while (!stopped.get()) {
                    byte[] frame;
                    synchronized (pending) {
                        while (!stopped.get() && notices.isEmpty() && latest == null) pending.wait();
                        if (stopped.get()) break;
                        frame = notices.pollFirst();
                        if (frame == null) { frame = latest; latest = null; }
                    }
                    DebuggerProtocol.writeFrame(child.getOutputStream(), frame);
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); stop(true); }
            catch (Exception | LinkageError failure) { fail("Cannot start or update debugger: " + failure.getMessage()); }
        }
        private void read() {
            try {
                byte[] frame;
                while (!stopped.get() && (frame = DebuggerProtocol.readFrame(process.getInputStream())) != null) {
                    if (frame.length > DebuggerPackets.MAX_REQUEST) throw new IOException("Debugger helper request too large");
                    var request = DebuggerProtocol.request(frame);
                    if (request.action() == DebuggerProtocol.Action.CLOSE) { stop(true); return; }
                    byte[] bytes = frame;
                    client.execute(() -> {
                        if (!stopped.get() && SESSIONS.get(token) == this && connected()) ClientPlayNetworking.send(new DebuggerPackets.Request(token, bytes));
                    });
                }
                if (!stopped.get() && !serverClosed && !process.isAlive() && process.exitValue() != 0)
                    fail("Debugger helper exited with code " + process.exitValue());
                else stop(true);
            } catch (IOException failure) { fail("Debugger helper connection: " + failure.getMessage()); }
        }
        void fail(String message) {
            if (stopped.get()) return;
            if (!serverClosed) client.execute(() -> {
                if (connected() && client.player != null) client.player.sendSystemMessage(Component.literal(message));
            });
            stop(true);
        }
        void stop(boolean notify) {
            if (!stopped.compareAndSet(false, true)) return;
            synchronized (pending) { pending.notifyAll(); notices.clear(); latest = null; }
            var child = process; if (child != null) child.destroy();
            client.execute(() -> {
                SESSIONS.remove(token, this);
                if (notify && connected()) try {
                    ClientPlayNetworking.send(new DebuggerPackets.Request(token,
                            DebuggerProtocol.encode(new DebuggerProtocol.Request(DebuggerProtocol.Action.CLOSE, 0, 0, 0))));
                } catch (IOException invalid) { throw new IllegalStateException(invalid); }
            });
        }
    }
}

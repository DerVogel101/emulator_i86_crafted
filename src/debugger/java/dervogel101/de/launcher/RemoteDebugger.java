package dervogel101.de.launcher;

import dervogel101.de.i86.runtime.*;
import dervogel101.de.state.SharedMemoryRegion;
import javax.swing.SwingUtilities;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static dervogel101.de.launcher.DebuggerProtocol.Action.*;

/** A remote window owns a pipe and copied views, never an emulator CPU. */
public final class RemoteDebugger implements DebuggerBackend {
    private final InputStream input;
    private final OutputStream output;
    private final Consumer<Snapshot> snapshots;
    private final Consumer<String> errors;
    private final Runnable onClosed;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean control;
    private volatile boolean detailed;
    private volatile Snapshot latest = new Snapshot("Connecting", "Connecting to Minecraft", "", 0, 0,
            0, new byte[256], 0, new byte[256], List.of(), false, false, DebuggerProtocol.emptyDevices(), "",
            new ImageState(null, false, null, false, false), false,
            new MachineState(false, false, new HardwareProfile(Map.of()), 0, "", ShardSpeed.TIER_1));
    private long mainframe;

    public RemoteDebugger(InputStream input, OutputStream output, Consumer<Snapshot> snapshots, Consumer<String> errors, Runnable onClosed) {
        this.input = input; this.output = output; this.snapshots = snapshots; this.errors = errors; this.onClosed = onClosed;
        var reader = new Thread(this::read, "i86-remote-debugger"); reader.setDaemon(true); reader.start();
    }
    private void read() {
        try {
            byte[] frame;
            while (!closed.get() && (frame = DebuggerProtocol.readFrame(input)) != null) {
                var event = DebuggerProtocol.event(frame);
                if (event instanceof DebuggerProtocol.State state) {
                    if (mainframe != 0 && mainframe != state.mainframe()) throw new IOException("Mainframe changed within a debugger session");
                    mainframe = state.mainframe(); control = state.control(); detailed = state.detailed();
                    var snapshot = detailed ? state.snapshot() : DebuggerProtocol.retainPausedViews(latest, state.snapshot()); latest = snapshot;
                    SwingUtilities.invokeLater(() -> { if (!closed.get()) snapshots.accept(snapshot); });
                } else if (event instanceof DebuggerProtocol.Failure failure) report(failure.message());
                else if (event instanceof DebuggerProtocol.Closed) break;
            }
        } catch (IOException failure) { if (!closed.get()) report("Debugger connection: " + failure.getMessage()); }
        finally { finish(false); }
    }
    private void report(String text) { SwingUtilities.invokeLater(() -> errors.accept(text + "\n")); }
    private synchronized void send(DebuggerProtocol.Action action, int a, int b, int c, String text) {
        if (closed.get()) return;
        try { DebuggerProtocol.writeFrame(output, DebuggerProtocol.encode(new DebuggerProtocol.Request(action, a, b, c, text))); }
        catch (IOException failure) { report("Debugger command: " + failure.getMessage()); finish(false); }
    }
    private void view(DebuggerProtocol.Action action, int a, int b) { send(action, a, b, 0, ""); }
    private void command(DebuggerProtocol.Action action, int a, int b, int c, String text, boolean paused) {
        if (!control) { report("Read only: another player controls this mainframe's debugger"); return; }
        if (paused && latest.running()) { report("Pause the CPU before editing it"); return; }
        if (paused && !detailed) { report("Detailed CPU state is unavailable while the mainframe is off or unloaded"); return; }
        send(action, a, b, c, text);
    }
    private void command(DebuggerProtocol.Action action) { command(action, 0, 0, 0, "", false); }
    private UnsupportedOperationException localOnly() { return new UnsupportedOperationException("Use the Minecraft block GUI for hardware and world-owned images"); }
    @Override public boolean remote() { return true; }
    @Override public void selectPunchcard(int index) { if (index < 0 || index >= 32) throw new IllegalArgumentException("Punchcard slot"); view(SELECT_PUNCHCARD,index,0); }
    @Override public void punchcardCommand(int index,int operation,String hex) { punchcardCommand(index,operation,hex,0); }
    @Override public void punchcardCommand(int index,int operation,String hex,int metadata) { command(PUNCHCARD_COMMAND,index,operation,metadata,hex,true); }
    @Override public boolean canControl() { return control && !closed.get(); }
    @Override public boolean canEdit() { return canControl() && detailed && !latest.running() && latest.machine().powered(); }
    @Override public void run() { command(RUN); }
    @Override public void runTo(int cs, int ip) { command(RUN_TO, cs, ip, 0, "", false); }
    @Override public void stepOver() { command(STEP_OVER); }
    @Override public void pause() { command(PAUSE); }
    @Override public void step() { command(STEP); }
    @Override public void skipInstruction() { command(SKIP, 0, 0, 0, "", true); }
    @Override public void setPc(int cs, int ip) { command(SET_PC, cs, ip, 0, "", true); }
    @Override public void reset() { command(RESET); }
    @Override public void powerCycle() { command(POWER_CYCLE); }
    @Override public void nmi() { command(NMI); }
    @Override public void selectChannel(int channel) { view(SELECT_CHANNEL, channel, 0); }
    @Override public void selectSign(int sign) { view(SELECT_SIGN, sign, 0); }
    @Override public void selectTimer(int timer) { view(SELECT_TIMER, timer, 0); }
    @Override public void selectKeyboard(int slot) { view(SELECT_KEYBOARD, slot, 0); }
    @Override public void keyboardInput(int slot, int value) { command(KEYBOARD_INPUT, slot, value, 0, "", false); }
    @Override public void memoryBase(int address) { view(MEMORY_BASE, address, 0); }
    @Override public void ioBase(int port) { view(IO_BASE, port, 0); }
    @Override public void codeAddress(int cs, int ip) { view(CODE_ADDRESS, cs, ip); }
    @Override public void followPc() { view(FOLLOW_PC, 0, 0); }
    @Override public void sendIrq(int device) { command(SEND_IRQ, device, 0, 0, "", true); }
    @Override public void timerAutomatic(int timer, boolean automatic) { command(TIMER_AUTOMATIC, timer, automatic ? 1 : 0, 0, "", true); }
    @Override public void writeTimer(int timer, int reload, int flags) { command(WRITE_TIMER, timer, reload, flags, "", true); }
    @Override public void resetTimer(int timer) { command(RESET_TIMER, timer, 0, 0, "", true); }
    @Override public void writeSignLine(int sign, int line, String text) { command(WRITE_SIGN_LINE, sign, line, 0, text, true); }
    @Override public void writeMemory(int address, int value) { command(WRITE_MEMORY, address, value, 0, "", true); }
    @Override public void writeIo(int port, int value) { command(WRITE_IO, port, value, 0, "", true); }
    @Override public void redstoneIrq(int channel, RedstoneIrqMode mode) { command(REDSTONE_IRQ, channel, mode.ordinal(), 0, "", true); }
    @Override public void signIrq(int sign, int sides) { command(SIGN_IRQ, sign, sides, 0, "", true); }
    @Override public ImageState inspectImages() { return latest.images(); }
    @Override public MachineState inspectMachine() { return latest.machine(); }

    @Override public void loadRom(Path path) { throw localOnly(); }
    @Override public void configure(int steps, int ticks) { throw localOnly(); }
    @Override public void configure(int steps, int ticks, int batches) { throw localOnly(); }
    @Override public void pauseAndWait() { throw localOnly(); }
    @Override public void openNvram(Path path) { throw localOnly(); }
    @Override public void newNvram() { throw localOnly(); }
    @Override public void saveNvram(Path path) { throw localOnly(); }
    @Override public void discardNvram() { throw localOnly(); }
    @Override public void openStorage(Path path) { throw localOnly(); }
    @Override public void newStorage() { throw localOnly(); }
    @Override public void saveStorage(Path path) { throw localOnly(); }
    @Override public void discardStorage() { throw localOnly(); }
    @Override public void disconnectStorage() { throw localOnly(); }
    @Override public void powerCycleWithSharedMemory(SharedMemoryRegion next) { throw localOnly(); }
    @Override public void testPin(boolean high) { throw localOnly(); }
    @Override public void redstone(int channel, int strength) { throw localOnly(); }
    @Override public void signClick(int sign, boolean front) { throw localOnly(); }
    @Override public void powerSupply(boolean high) { throw localOnly(); }
    @Override public void configureMachine(HardwareProfile hardware, boolean shard, String id) { throw localOnly(); }
    @Override public void configureMachine(HardwareProfile hardware, int shard, String id) { throw localOnly(); }
    @Override public void sharedRegionDeleted(String id) { throw localOnly(); }
    @Override public boolean powerOffForCycle() { throw localOnly(); }
    @Override public void powerOnAfterCycle(boolean restart) { throw localOnly(); }
    @Override public byte[] saveSessionState(boolean resume) { throw localOnly(); }
    @Override public boolean restoreSessionState(byte[] bytes) { throw localOnly(); }
    @Override public boolean suspendForSession() { throw localOnly(); }

    @Override public void close() { finish(true); }
    private synchronized void finish(boolean notify) {
        if (!closed.compareAndSet(false, true)) return;
        if (notify) try { DebuggerProtocol.writeFrame(output, DebuggerProtocol.encode(new DebuggerProtocol.Request(CLOSE, 0, 0, 0))); }
        catch (IOException ignored) {}
        control = false; SwingUtilities.invokeLater(onClosed);
        // Closing a pipe can wait for a blocked read; never hold the Swing thread there.
        var cleanup = new Thread(() -> {
            try { input.close(); } catch (IOException ignored) {}
            try { output.close(); } catch (IOException ignored) {}
        }, "i86-debugger-pipe-close");
        cleanup.setDaemon(true); cleanup.start();
    }
}

package dervogel101.de.launcher;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import static dervogel101.de.launcher.DebuggerProtocol.*;

public final class RemoteDebuggerSelfTest {
    public static void run() throws Exception {
        String classpath = RemoteLauncherMain.helperClasspath();
        assert !classpath.isBlank() && !classpath.contains("private-rom-file");
        assert RemoteLauncherMain.class.getResource("/assets/emulator_i86_crafted/font/monosign.ttf") != null;
        var views = new LinkedBlockingQueue<DebuggerBackend.Snapshot>();
        var errors = new LinkedBlockingQueue<String>();
        var ended = new CountDownLatch(1);
        var input = new PipedInputStream(MAX_FRAME); var server = new PipedOutputStream(input);
        var commands = new ByteArrayOutputStream();
        try (var debugger = new RemoteDebugger(input, commands, views::add, errors::add, ended::countDown)) {
            var paused = DebuggerProtocolSelfTest.snapshot(false, "Copied registers", (byte) 42);
            writeFrame(server, encode(new State(22, true, paused)));
            var first = take(views); assert first.registers().equals("Copied registers") && debugger.canControl() && debugger.canEdit();
            var panel = new java.util.concurrent.atomic.AtomicReference<DeviceViewPanel>();
            SwingUtilities.invokeAndWait(() -> {
                Runnable localFile = () -> { throw new AssertionError("Remote file control invoked"); };
                panel.set(new DeviceViewPanel(() -> debugger, localFile, localFile, localFile, localFile, localFile));
                panel.get().update(first.devices(), first.images(), true);
                for (JButton button : buttons(panel.get())) {
                    if (Set.of("Set input", "Front click", "Back click", "Open", "New", "Save", "Save As", "Disconnect").contains(button.getText())) {
                        assert !button.isVisible() && !button.isEnabled(); button.doClick();
                    }
                    if (button.getText().equals("Apply")) assert button.isEnabled();
                }
            });
            debugger.step(); debugger.writeMemory(0x1234, 0x5A);
            var requests = requests(commands);
            assert requests.size() == 2 && requests.getFirst().action() == Action.STEP;
            assert requests.get(1).equals(new Request(Action.WRITE_MEMORY, 0x1234, 0x5A, 0));
            assert views.isEmpty() : "Remote commands cannot execute a local CPU or manufacture its state";
            int size = commands.size();
            writeFrame(server, encode(new State(22, false, paused))); take(views);
            SwingUtilities.invokeAndWait(() -> {
                panel.get().update(first.devices(), first.images(), true);
                assert buttons(panel.get()).stream().filter(button -> button.getText().equals("Apply")).noneMatch(JButton::isEnabled);
                assert buttons(panel.get()).stream().filter(button -> button.getText().startsWith("Reload")).allMatch(JButton::isEnabled);
            });
            debugger.step(); debugger.writeMemory(0, 1);
            assert commands.size() == size : "Observer mutations are blocked before entering the pipe";
            assert take(errors).contains("Read only") && take(errors).contains("Read only");
            debugger.selectSign(127);
            assert requests(commands).getLast().equals(new Request(Action.SELECT_SIGN, 127, 0, 0));

            writeFrame(server, encode(new State(22, true, DebuggerProtocolSelfTest.snapshot(true, "Must not arrive", (byte) 99))));
            var live = take(views); assert live.running() && live.registers().equals(first.registers());
            size = commands.size(); debugger.writeMemory(0, 9);
            assert commands.size() == size && take(errors).contains("Pause the CPU");
            debugger.pause(); assert requests(commands).getLast().action() == Action.PAUSE;
            writeFrame(server, encode(new State(22, true, paused, false))); take(views);
            assert debugger.canControl() && !debugger.canEdit();
            size = commands.size(); debugger.writeMemory(0, 9);
            assert commands.size() == size && take(errors).contains("unavailable");
            boolean refused = false;
            try { debugger.loadRom(java.nio.file.Path.of("private-rom-file")); }
            catch (UnsupportedOperationException expected) { refused = true; }
            assert refused : "A remote window cannot load a private ROM into a local substitute CPU";
            debugger.close();
            assert ended.await(5, TimeUnit.SECONDS);
            assert requests(commands).getLast().action() == Action.CLOSE;
            assert !debugger.canControl();
        } finally { server.close(); }

        var eofInput = new PipedInputStream(); var eofServer = new PipedOutputStream(eofInput);
        var eof = new CountDownLatch(1); var eofCommands = new ByteArrayOutputStream();
        try (var debugger = new RemoteDebugger(eofInput, eofCommands, ignored -> {}, errors::add, eof::countDown)) {
            eofServer.close(); assert eof.await(5, TimeUnit.SECONDS); assert !debugger.canControl(); assert eofCommands.size() == 0;
        }
        System.out.println("Remote debugger checks passed: command forwarding, observer guards, paused edits, retained views, close/EOF, no local ROM/CPU fallback");
    }
    private static <T> T take(BlockingQueue<T> queue) throws Exception {
        T value = queue.poll(5, TimeUnit.SECONDS); if (value == null) throw new AssertionError("Debugger callback timed out"); return value;
    }
    private static List<Request> requests(ByteArrayOutputStream output) throws IOException {
        var in = new ByteArrayInputStream(output.toByteArray()); var requests = new ArrayList<Request>(); byte[] frame;
        while ((frame = readFrame(in)) != null) requests.add(request(frame)); return requests;
    }
    private static List<JButton> buttons(java.awt.Container root) {
        var result = new ArrayList<JButton>();
        for (var child : root.getComponents()) {
            if (child instanceof JButton button) result.add(button);
            if (child instanceof java.awt.Container container) result.addAll(buttons(container));
        }
        return result;
    }
}

package dervogel101.de.launcher;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static dervogel101.de.launcher.DebuggerBackend.*;

/** Optional headed check; run against the packaged mod JAR without Minecraft on the classpath. */
public final class RemoteWindowSmokeTest {
    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("A desktop is required for this check");
        Path output = Path.of(args[0]).toAbsolutePath().normalize(); Files.createDirectories(output);
        var pipe = new PipedInputStream(DebuggerProtocol.MAX_FRAME); var server = new PipedOutputStream(pipe);
        var commands = new ByteArrayOutputStream(); var errors = new ArrayList<String>();
        var backend = new AtomicReference<RemoteDebugger>(); var window = new AtomicReference<JFrame>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                LauncherMain.openRemote((snapshots, failures, closed) -> {
                    var remote = new RemoteDebugger(pipe, commands, snapshots, text -> { errors.add(text); failures.accept(text); }, closed);
                    backend.set(remote); return remote;
                });
                for (Window candidate : Window.getWindows()) if (candidate instanceof JFrame frame && frame.isDisplayable()) window.set(frame);
            });
            JFrame frame = Objects.requireNonNull(window.get(), "No visible remote frame");
            var snapshot = sample(); DebuggerProtocol.writeFrame(server, DebuggerProtocol.encode(new DebuggerProtocol.State(42, true, snapshot)));
            await(() -> frame.getTitle().contains("UI check [42]") && button(frame, "Step").isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                if (findButton(frame, "Load ROM") != null || findButton(frame, "Hardware") != null)
                    throw new AssertionError("Remote mode exposes local file/hardware actions");
                button(frame, "Step").doClick();
            });
            var request = DebuggerProtocol.request(Objects.requireNonNull(DebuggerProtocol.readFrame(new ByteArrayInputStream(commands.toByteArray()))));
            if (request.action() != DebuggerProtocol.Action.STEP) throw new AssertionError("Step did not enter the helper pipe");
            capture(frame, output.resolve("memory.png"));
            SwingUtilities.invokeAndWait(() -> {
                var mainTabs = tabs(frame, "Memory"); mainTabs.setSelectedIndex(mainTabs.indexOfTab("I/O"));
                var tabs = tabs(frame, "Devices"); tabs.setSelectedIndex(tabs.indexOfTab("Devices"));
                var devices = components(frame).stream().filter(c -> c instanceof DeviceViewPanel).map(c -> (DeviceViewPanel) c).findFirst().orElseThrow();
                devices.setSelectedIndex(1);
                frame.validate();
                if (!devices.isShowing() || !devices.getSelectedComponent().isShowing()) throw new AssertionError("Sign view is not visible");
            });
            capture(frame, output.resolve("signs.png"));
            DebuggerProtocol.writeFrame(server, DebuggerProtocol.encode(new DebuggerProtocol.State(42, false, snapshot)));
            await(() -> !button(frame, "Step").isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                if (components(frame).stream().filter(c -> c instanceof JLabel).map(c -> ((JLabel) c).getText()).noneMatch(text -> text != null && text.contains("read only")))
                    throw new AssertionError("Observer window lacks a read-only status");
            });
            capture(frame, output.resolve("observer.png"));
            DebuggerProtocol.writeFrame(server, DebuggerProtocol.encode(new DebuggerProtocol.Closed("Smoke check complete")));
            await(() -> !frame.isDisplayable());
            if (!errors.isEmpty()) throw new AssertionError(errors.toString());
            packagedEntryPoint(output, snapshot);
            System.out.println("SWING_SMOKE_OK: owner step, observer guards, sign font, layout and server-driven close; helper classpath=" + RemoteLauncherMain.helperClasspath());
        } finally {
            if (backend.get() != null) backend.get().close(); server.close();
            SwingUtilities.invokeAndWait(() -> { for (Window frame : Window.getWindows()) frame.dispose(); });
        }
    }
    private static void packagedEntryPoint(Path output, Snapshot snapshot) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
        var helper = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Djava.awt.headless=false", "-cp", RemoteLauncherMain.helperClasspath(), RemoteLauncherMain.class.getName())
                .redirectError(output.resolve("helper-stderr.log").toFile()).start();
        try {
            DebuggerProtocol.writeFrame(helper.getOutputStream(), DebuggerProtocol.encode(new DebuggerProtocol.State(42, true, snapshot)));
            Thread.sleep(1000);
            if (!helper.isAlive()) throw new AssertionError("Packaged helper did not stay open; exit=" + helper.exitValue());
            DebuggerProtocol.writeFrame(helper.getOutputStream(), DebuggerProtocol.encode(new DebuggerProtocol.Closed("Packaged helper check complete")));
            if (!helper.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || helper.exitValue() != 0)
                throw new AssertionError("Packaged helper did not close cleanly");
        } finally { helper.destroy(); }
    }
    private static Snapshot sample() {
        byte[] memory = new byte[256], io = new byte[256], sign = new byte[130];
        for (int i = 0; i < memory.length; i++) memory[i] = (byte) i;
        for (int line = 0; line < 8; line++) {
            byte[] text = (line < 4 ? "Front € Grüße " : "Back  äÖü ß " ).getBytes(java.nio.charset.Charset.forName("windows-1252"));
            System.arraycopy(text, 0, sign, line * 16 + 1, text.length);
        }
        var devices = new DeviceSnapshot(0, 0, 0, new byte[]{7, 12}, sign, new byte[5], new byte[3], new byte[5], true, dervogel101.de.i86.runtime.RedstoneIrqMode.ANALOG, 3);
        var machine = new MachineState(true, true, dervogel101.de.i86.runtime.HardwareProfile.complete(), 1, "ui_check", dervogel101.de.i86.runtime.ShardSpeed.TIER_1);
        return new Snapshot("UI check [42] — Example ROM", "Paused", "AX 1234 BX 0042 CX 0000 DX 0000 SP 2000 BP 0000 SI 0000 DI 0000\nCS FFFF DS 0000 ES 0000 SS 0000 IP 0000 FLAGS 0002 PC FFFF0", 0, 12,
                0xFFF00, memory, 0, io, List.of(new Disassembler8086.Instruction(0xFFFF, 0, new byte[]{15, 1}, "YIELD", "TICK", false, false)), false, false,
                devices, "Connected to the server-owned CPU.\nWindows-1252: € ä ö ü ß\n", new ImageState(null, false, null, false, true), false, machine);
    }
    private static void capture(JFrame frame, Path path) throws Exception {
        var image = new AtomicReference<BufferedImage>();
        SwingUtilities.invokeAndWait(() -> {
            var root = frame.getContentPane(); root.validate();
            var pixels = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = pixels.createGraphics(); root.printAll(graphics); graphics.dispose(); image.set(pixels);
        });
        ImageIO.write(image.get(), "png", path.toFile());
    }
    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 250; i++) {
            var ready = new AtomicBoolean(); SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return; Thread.sleep(20);
        }
        throw new AssertionError("Swing window update timed out");
    }
    private static List<Component> components(Container root) {
        var result = new ArrayList<Component>();
        for (Component child : root.getComponents()) { result.add(child); if (child instanceof Container container) result.addAll(components(container)); }
        return result;
    }
    private static JButton findButton(Container root, String name) {
        return components(root).stream().filter(c -> c instanceof JButton button && button.getText().equals(name)).map(c -> (JButton) c).findFirst().orElse(null);
    }
    private static JButton button(Container root, String name) { return Objects.requireNonNull(findButton(root, name), name); }
    private static JTabbedPane tabs(Container root, String name) {
        return components(root).stream().filter(c -> c instanceof JTabbedPane tabs && tabs.indexOfTab(name) >= 0).map(c -> (JTabbedPane) c).findFirst().orElseThrow();
    }
}

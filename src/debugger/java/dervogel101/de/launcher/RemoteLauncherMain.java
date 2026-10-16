package dervogel101.de.launcher;

import javax.swing.SwingUtilities;
import dervogel101.de.CPU8086;
import dervogel101.de.i86.runtime.Hardware;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

/** Separate Swing process: Minecraft's client JVM remains headless. */
public final class RemoteLauncherMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> LauncherMain.openRemote((snapshots, errors, closed) ->
                new RemoteDebugger(System.in, System.out, snapshots, errors, closed)));
    }
    public static String helperClasspath() throws Exception {
        var entries = new LinkedHashSet<String>();
        for (Class<?> type : List.of(RemoteLauncherMain.class, CPU8086.class, Hardware.class))
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        var font = RemoteLauncherMain.class.getResource("/assets/emulator_i86_crafted/font/monosign.ttf");
        if (font != null && font.getProtocol().equals("file")) {
            Path root = Path.of(font.toURI()); for (int i = 0; i < 4; i++) root = root.getParent(); entries.add(root.toString());
        }
        return String.join(File.pathSeparator, entries);
    }
    private RemoteLauncherMain() {}
}

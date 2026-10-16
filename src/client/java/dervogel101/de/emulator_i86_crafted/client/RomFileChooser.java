package dervogel101.de.emulator_i86_crafted.client;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

/** A JDK-only helper process: Minecraft's headless AWT state never reaches this JVM. */
public final class RomFileChooser {
    static final int IMAGE_BYTES = 256 * 1024;
    private RomFileChooser() {}
    public static void main(String[] args) {
        Thread.ofPlatform().daemon().name("i86-picker-parent").start(() -> {
            try { while (System.in.read() != -1) { } } catch (IOException ignored) { }
            System.exit(0);
        });
        try {
            var selection = new AtomicReference<Path>();
            SwingUtilities.invokeAndWait(() -> {
                var chooser = new JFileChooser(); chooser.setDialogTitle("Choose a 256 KiB i8086 ROM");
                chooser.setFileFilter(new FileNameExtensionFilter("8086 ROM images (*.bin, *.rom)", "bin", "rom"));
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selection.set(chooser.getSelectedFile().toPath());
            });
            writeSelection(System.out, selection.get());
        } catch (Exception | LinkageError failure) {
            try {
                var output = new DataOutputStream(System.out); output.writeInt(-1);
                String message = (failure.getCause() == null ? failure : failure.getCause()).toString(); output.writeUTF(message.substring(0, Math.min(512, message.length()))); output.flush();
            } catch (IOException closed) { System.err.println("Cannot report ROM selection: " + closed); }
        }
        System.exit(0);
    }
    static void writeSelection(OutputStream stream, Path path) throws IOException {
        var output = new DataOutputStream(stream);
        if (path == null) { output.writeInt(0); output.flush(); return; }
        byte[] image;
        try (var input = Files.newInputStream(path)) { image = input.readNBytes(IMAGE_BYTES + 1); }
        if (image.length != IMAGE_BYTES) throw new IOException("ROM must be exactly 256 KiB");
        output.writeInt(image.length); output.write(image); output.flush();
    }
    static byte[] readSelection(InputStream stream) throws IOException {
        var input = new DataInputStream(stream); int length = input.readInt();
        if (length == 0) return null;
        if (length == -1) throw new IOException(input.readUTF());
        if (length != IMAGE_BYTES) throw new IOException("Invalid file chooser response size");
        byte[] image = input.readNBytes(length);
        if (image.length != length) throw new EOFException("Incomplete file chooser response");
        return image;
    }
}

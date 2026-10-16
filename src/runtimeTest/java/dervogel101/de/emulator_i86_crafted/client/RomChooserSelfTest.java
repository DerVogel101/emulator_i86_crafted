package dervogel101.de.emulator_i86_crafted.client;

import dervogel101.de.state.CPUMemory;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class RomChooserSelfTest {
    private RomChooserSelfTest() {}
    public static void run() throws Exception {
        assert RomFileChooser.IMAGE_BYTES == CPUMemory.ROM_SIZE;
        var response = new ByteArrayOutputStream();
        RomFileChooser.writeSelection(response, null);
        assert RomFileChooser.readSelection(new ByteArrayInputStream(response.toByteArray())) == null;
        for (int length : new int[]{-2, 1, Integer.MAX_VALUE, CPUMemory.ROM_SIZE}) {
            response.reset(); new DataOutputStream(response).writeInt(length);
            expectIo(() -> RomFileChooser.readSelection(new ByteArrayInputStream(response.toByteArray())));
        }
        response.reset(); var error = new DataOutputStream(response); error.writeInt(-1); error.writeUTF("Read failed");
        expectIo(() -> RomFileChooser.readSelection(new ByteArrayInputStream(response.toByteArray())));
        Path folder = Files.createTempDirectory("i86 picker check");
        try {
            Path file = folder.resolve("€ ROM image.rom");
            for (int size : new int[]{0, CPUMemory.ROM_SIZE - 1, CPUMemory.ROM_SIZE + 1}) {
                Files.write(file, new byte[size]); response.reset();
                expectIo(() -> RomFileChooser.writeSelection(response, file));
                assert response.size() == 0 : "File validation must finish before the response header";
            }
            byte[] image = new byte[CPUMemory.ROM_SIZE]; image[0] = 42; image[image.length - 1] = (byte) 255;
            Files.write(file, image); response.reset(); RomFileChooser.writeSelection(response, file);
            assert response.size() == 4 + CPUMemory.ROM_SIZE : "File paths must not enter the transfer protocol";
            assert Arrays.equals(image, RomFileChooser.readSelection(new ByteArrayInputStream(response.toByteArray())));
            response.reset(); expectIo(() -> RomFileChooser.writeSelection(response, folder.resolve("missing.rom")));
        } finally {
            try (var files = Files.list(folder)) { for (Path file : files.toList()) Files.delete(file); }
            Files.delete(folder);
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String helper = Path.of(RomFileChooser.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var process = new ProcessBuilder(javaBinary, "-Djava.awt.headless=true", "-cp", helper, RomFileChooser.class.getName()).start();
        try {
            assert process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 : "Helper must exit after reporting errors";
            try { RomFileChooser.readSelection(process.getInputStream()); throw new AssertionError("Headless helper unexpectedly selected a file"); }
            catch (IOException expected) { assert expected.getMessage().contains("HeadlessException") : expected; }
        } finally { process.destroyForcibly(); }
        process = new ProcessBuilder(javaBinary, "-Djava.awt.headless=true", "-cp", helper, RomFileChooser.class.getName()).start();
        try {
            process.getOutputStream().close();
            assert process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0 : "Parent pipe closure must stop the helper";
        } finally { process.destroyForcibly(); }
        System.out.println("ROM picker checks passed: cancellation, bounded IPC/files, incomplete responses, Unicode paths, no transmitted file paths, helper process shutdown");
    }
    private interface IoAction { void run() throws IOException; }
    private static void expectIo(IoAction action) throws IOException {
        try { action.run(); throw new AssertionError("Invalid picker response or file accepted"); }
        catch (IOException expected) { }
    }
}

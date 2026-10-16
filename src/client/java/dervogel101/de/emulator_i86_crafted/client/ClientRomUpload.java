package dervogel101.de.emulator_i86_crafted.client;

import dervogel101.de.emulator_i86_crafted.network.RomPackets;
import dervogel101.de.emulator_i86_crafted.server.storage.RomUpload;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.*;

final class ClientRomUpload {
    private static final AtomicBoolean CHOOSING = new AtomicBoolean();
    private static final AtomicLong CONNECTION_EPOCH = new AtomicLong();
    private static final AtomicReference<Process> PICKER = new AtomicReference<>();
    private ClientRomUpload() {}
    static void initialize() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            CONNECTION_EPOCH.incrementAndGet(); var process = PICKER.getAndSet(null);
            if (process != null) process.destroy();
        });
        ClientPlayNetworking.registerGlobalReceiver(RomPackets.ChooseFile.TYPE, (packet, context) -> {
            var client = context.client(); var connection = client.getConnection(); long epoch = CONNECTION_EPOCH.get();
            if (!CHOOSING.compareAndSet(false, true)) {
                ClientPlayNetworking.send(new RomPackets.Cancel(packet.token(), "A local ROM file chooser is already open.")); return;
            }
            Thread.ofPlatform().daemon().name("i86-rom-picker").start(() -> {
                byte[] image = null; String error = null; Process process = null;
                try {
                    Path helper = Path.of(RomFileChooser.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                    String executable = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
                    String javaBinary = Path.of(System.getProperty("java.home"), "bin", executable).toString();
                    process = new ProcessBuilder(javaBinary, "-Djava.awt.headless=false", "-cp", helper.toString(), RomFileChooser.class.getName())
                            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
                    PICKER.set(process);
                    if (CONNECTION_EPOCH.get() != epoch) return;
                    try (var input = process.getInputStream()) { image = RomFileChooser.readSelection(input); }
                    if (process.waitFor() != 0) throw new IOException("File chooser exited before completing");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); error = "ROM selection interrupted"; }
                catch (Exception | LinkageError failure) { error = "Cannot select or read ROM: " + failure.getMessage(); }
                finally {
                    if (process != null) { PICKER.compareAndSet(process, null); process.destroy(); }
                    CHOOSING.set(false);
                }
                byte[] upload = image; String notice = error;
                client.execute(() -> {
                    if (client.getConnection() != connection || connection == null) return;
                    if (notice != null) message(client, notice);
                    if (upload == null || notice != null) ClientPlayNetworking.send(new RomPackets.Cancel(packet.token(), notice == null ? "Upload cancelled"
                            : "Cannot upload: choose a readable ROM file of exactly 256 KiB. Details are in client chat."));
                    else {
                        message(client, "Uploading ROM to the server…");
                        for (int index = 0; index < RomUpload.CHUNKS; index++) {
                            byte[] chunk = Arrays.copyOfRange(upload, index * RomUpload.CHUNK_BYTES, (index + 1) * RomUpload.CHUNK_BYTES);
                            ClientPlayNetworking.send(new RomPackets.Chunk(packet.token(), index, chunk));
                        }
                    }
                });
            });
        });
    }
    private static void message(Minecraft client, String text) {
        if (client.player != null) client.player.sendSystemMessage(Component.literal(text));
    }
}

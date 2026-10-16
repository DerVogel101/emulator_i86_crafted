package dervogel101.de.emulator_i86_crafted.server.storage;

import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Atomic world snapshots; an unreadable existing snapshot never becomes a blank world. */
public final class WorldStore {
    private static final int MAX_BYTES = 512 * 1024 * 1024;
    private final Path folder;

    public WorldStore(Path folder) throws IOException {
        this.folder = folder.toAbsolutePath().normalize();
        Files.createDirectories(this.folder);
    }

    public WorldRecords load() throws IOException {
        Path path = folder.resolve("world.dat");
        if (!Files.exists(path)) return new WorldRecords();
        try (var in = new GZIPInputStream(Files.newInputStream(path))) {
            byte[] state = in.readNBytes(MAX_BYTES + 1);
            if (state.length > MAX_BYTES) throw new IOException("Mainframe world snapshot exceeds 512 MiB");
            return WorldRecords.load(state);
        }
    }

    public void save(WorldRecords records) throws IOException {
        byte[] state = records.save();
        if (state.length > MAX_BYTES) throw new IOException("Mainframe world snapshot exceeds 512 MiB");
        Path next = Files.createTempFile(folder, ".world-", ".tmp");
        Path previous = null;
        try {
            try (var out = new GZIPOutputStream(Files.newOutputStream(next))) { out.write(state); }
            Path target = folder.resolve("world.dat");
            if (Files.exists(target)) {
                previous = Files.createTempFile(folder, ".world-previous-", ".tmp");
                Files.copy(target, previous, StandardCopyOption.REPLACE_EXISTING);
                replace(previous, folder.resolve("world.dat.previous"));
            }
            replace(next, target);
        } finally {
            Files.deleteIfExists(next);
            if (previous != null) Files.deleteIfExists(previous);
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}

package dervogel101.de.emulator_i86_crafted.server.storage;

import dervogel101.de.state.CPUMemory;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.*;
import java.util.*;

/** Immutable ROM images and their world-owned, first-upload metadata. */
public final class RomLibrary {
    public record Entry(UUID id, String hash, String name, String description) {}
    public static final class DuplicateRom extends IOException {
        public final Entry existing;
        DuplicateRom(Entry existing) { super("ROM already exists: " + existing.name() + " (" + existing.id() + ")"); this.existing = existing; }
    }
    private static final int MAGIC = 0x49383652;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path folder;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<String, UUID> hashes = new HashMap<>();
    private record Stamp(Object fileKey, FileTime modified, FileTime created, long size) {}
    private final Map<UUID, Stamp> validated = new HashMap<>();

    public RomLibrary(Path folder) throws IOException {
        this.folder = folder.toAbsolutePath().normalize();
        Files.createDirectories(this.folder);
        Path index = this.folder.resolve("library.index");
        if (!Files.exists(index)) return;
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(index)))) {
            if (in.readInt() != MAGIC || in.readInt() != 1) throw new IOException("Unsupported ROM library index");
            int count = in.readInt();
            if (count < 0 || count > 1_000_000) throw new IOException("Invalid ROM library count");
            for (int i = 0; i < count; i++) {
                UUID id = new UUID(in.readLong(), in.readLong());
                var entry = new Entry(id, in.readUTF(), in.readUTF(), in.readUTF());
                if (id.version() != 7 || id.variant() != 2 || !entry.hash().matches("[0-9a-f]{64}")
                        || entry.name().isBlank() || entry.name().length() > 128 || entry.description().length() > 4096
                        || entries.putIfAbsent(id, entry) != null || hashes.putIfAbsent(entry.hash(), id) != null)
                    throw new IOException("Invalid or duplicate ROM library entry");
            }
            if (in.read() != -1) throw new IOException("Trailing ROM library index data");
        }
    }

    public synchronized List<Entry> entries() { return List.copyOf(entries.values()); }
    public synchronized Optional<Entry> entry(UUID id) { return Optional.ofNullable(entries.get(id)); }

    public static byte[] readUpload(Path uploads, String filename) throws IOException {
        if (!filename.matches("[A-Za-z0-9_.-]{1,128}") || filename.equals(".") || filename.equals(".."))
            throw new IOException("Use an upload filename without a directory");
        Files.createDirectories(uploads); Path file = uploads.resolve(filename);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Put the ROM file in " + uploads);
        byte[] bytes;
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = in.readNBytes(CPUMemory.ROM_SIZE + 1); }
        if (bytes.length != CPUMemory.ROM_SIZE) throw new IOException("ROM must be exactly 256 KiB");
        return bytes;
    }

    public static void validateMetadata(String name, String description) throws IOException {
        if (name.isBlank() || name.length() > 128 || description.length() > 4096)
            throw new IOException("ROM name requires 1-128 characters; description allows up to 4096");
    }

    public synchronized Entry upload(byte[] image, String name, String description) throws IOException {
        Objects.requireNonNull(image); Objects.requireNonNull(name); Objects.requireNonNull(description);
        if (image.length != CPUMemory.ROM_SIZE) throw new IOException("ROM must be exactly 256 KiB");
        image = image.clone();
        validateMetadata(name, description);
        if (entries.size() >= 1_000_000) throw new IOException("ROM library entry limit reached");
        String hash = hash(image);
        if (hashes.containsKey(hash)) throw new DuplicateRom(entries.get(hashes.get(hash)));
        UUID id;
        do { id = uuid7(); } while (entries.containsKey(id) || Files.exists(imagePath(id)));
        var entry = new Entry(id, hash, name, description);
        Path target = imagePath(id);
        var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW);
        try (output) { output.write(image); }
        catch (IOException failure) {
            try { Files.deleteIfExists(target); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        var next = new LinkedHashMap<>(entries); next.put(id, entry);
        try { writeIndex(next); }
        catch (IOException failure) {
            try { Files.deleteIfExists(target); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        entries.put(id, entry); hashes.put(hash, id);
        return entry;
    }

    public synchronized byte[] read(UUID id) throws IOException {
        Entry entry = entries.get(id);
        if (entry == null) throw new IOException("Unknown ROM: " + id);
        validated.remove(id);
        Path file = imagePath(id);
        Stamp before = stamp(file);
        byte[] image;
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { image = in.readNBytes(CPUMemory.ROM_SIZE + 1); }
        if (image.length != CPUMemory.ROM_SIZE || !hash(image).equals(entry.hash())) {
            validated.remove(id);
            throw new IOException("ROM file has invalid size or contents: " + id);
        }
        Stamp after = stamp(file);
        if (before.equals(after)) validated.put(id, after);
        return image;
    }

    /** Revalidate contents when the file changes; unchanged immutable images reuse their verified stamp. */
    public synchronized boolean available(UUID id) {
        if (!entries.containsKey(id)) return false;
        try {
            Stamp current = stamp(imagePath(id));
            if (current.equals(validated.get(id))) return true;
            read(id); return true;
        } catch (IOException failure) { validated.remove(id); return false; }
    }

    /** Call only from the authorized administrative command path. */
    public synchronized void delete(UUID id) throws IOException {
        Entry old = entries.get(id);
        if (old == null) throw new IOException("Unknown ROM: " + id);
        Files.deleteIfExists(imagePath(id));
        var next = new LinkedHashMap<>(entries); next.remove(id); writeIndex(next);
        entries.remove(id); hashes.remove(old.hash());
        validated.remove(id);
    }

    private Path imagePath(UUID id) { return folder.resolve(id.toString() + ".rom"); }

    private static Stamp stamp(Path file) throws IOException {
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() != CPUMemory.ROM_SIZE) throw new IOException("ROM missing or wrong size: " + file.getFileName());
        return new Stamp(attributes.fileKey(), attributes.lastModifiedTime(), attributes.creationTime(), attributes.size());
    }

    private void writeIndex(Map<UUID, Entry> next) throws IOException {
        Path temporary = Files.createTempFile(folder, ".rom-index-", ".tmp");
        try {
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                out.writeInt(MAGIC); out.writeInt(1); out.writeInt(next.size());
                for (Entry entry : next.values()) {
                    out.writeLong(entry.id().getMostSignificantBits()); out.writeLong(entry.id().getLeastSignificantBits());
                    out.writeUTF(entry.hash()); out.writeUTF(entry.name()); out.writeUTF(entry.description());
                }
            }
            try { Files.move(temporary, folder.resolve("library.index"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, folder.resolve("library.index"), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String hash(byte[] image) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static UUID uuid7() {
        long most = ((System.currentTimeMillis() & 0xFFFFFFFFFFFFL) << 16) | 0x7000L | RANDOM.nextInt(4096);
        long least = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }
}

package dervogel101.de.i86.runtime;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Portable media contents; transport positions belong to machines, not decks. */
public final class PunchcardDeck {
    public static final int ROW_BYTES = 32, CARD_BYTES = 256, MAX_CARDS = 256;
    public static final int MAX_FILE_BYTES = 91 + MAX_CARDS * CARD_BYTES;
    public static final Charset CHARSET = Charset.forName("windows-1252");
    private final UUID id;
    private final byte[] name, description, data;
    private final boolean sealed, nameWritten, descriptionWritten;

    public PunchcardDeck(UUID id, byte[] name, byte[] data, boolean sealed, boolean nameWritten) {
        this(id, name, new byte[32], data, sealed, nameWritten, false);
    }
    public PunchcardDeck(UUID id, byte[] name, byte[] description, byte[] data, boolean sealed, boolean nameWritten, boolean descriptionWritten) {
        this.id = Objects.requireNonNull(id);
        if (name.length != 32 || description.length != 32 || data.length > 65536 || data.length % 256 != 0
                || !nameWritten && !Arrays.equals(name, new byte[32]) || !descriptionWritten && !Arrays.equals(description, new byte[32]))
            throw new IllegalArgumentException("Invalid punchcard deck");
        this.name = name.clone(); this.description = description.clone(); this.data = data.clone();
        this.sealed = sealed; this.nameWritten = nameWritten; this.descriptionWritten = descriptionWritten;
    }
    public static PunchcardDeck blank() { return new PunchcardDeck(UUID.randomUUID(), new byte[32], new byte[0], false, false); }
    public UUID id() { return id; }
    public byte[] name() { return name.clone(); }
    public byte[] description() { return description.clone(); }
    public byte[] data() { return data.clone(); }
    public int cards() { return data.length / 256; }
    public boolean sealed() { return sealed; }
    public boolean nameWritten() { return nameWritten; }
    public boolean descriptionWritten() { return descriptionWritten; }
    public boolean blankMedia() { return !sealed && !nameWritten && !descriptionWritten && data.length == 0; }
    public String label() {
        return text(name);
    }
    public String descriptionText() { return text(description); }
    private static String text(byte[] name) {
        int end = 0; while (end < name.length && name[end] != 0) end++;
        return new String(name, 0, end, CHARSET);
    }
    public PunchcardDeck seal() { return new PunchcardDeck(id, name, description, data, true, nameWritten, descriptionWritten); }
    public PunchcardDeck name(byte[] value) { return new PunchcardDeck(id, value, description, data, sealed, true, descriptionWritten); }
    public PunchcardDeck description(byte[] value) { return new PunchcardDeck(id, name, value, data, sealed, nameWritten, true); }
    public PunchcardDeck row(int card, int row, byte[] value) {
        if (sealed || card < 0 || card >= 256 || row < 0 || row >= 8 || value.length != 32) throw new IllegalArgumentException("Invalid row");
        byte[] next = Arrays.copyOf(data, Math.max(data.length, (card + 1) * 256));
        System.arraycopy(value, 0, next, card * 256 + row * 32, 32);
        return new PunchcardDeck(id, name, description, next, false, nameWritten, descriptionWritten);
    }
    public byte[] row(int card, int row) { return Arrays.copyOfRange(data, card * 256 + row * 32, card * 256 + (row + 1) * 32); }
    public String hash() {
        try {
            var hash = MessageDigest.getInstance("SHA-256"); hash.update(name);
            hash.update((byte)(cards() >>> 8)); hash.update((byte)cards()); hash.update(data);
            // Empty descriptions retain v1 hashes and existing index/checkpoint references.
            if (!Arrays.equals(description, new byte[32])) { hash.update((byte)2); hash.update(description); }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public void write(DataOutput out) throws IOException {
        out.writeInt(0x5044454b); out.writeInt(2); out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
        out.writeByte((sealed ? 1 : 0) | (nameWritten ? 2 : 0) | (descriptionWritten ? 4 : 0));
        out.write(name); out.write(description); out.writeShort(cards()); out.write(data);
    }
    public static PunchcardDeck read(DataInput in) throws IOException {
        if (in.readInt() != 0x5044454b) throw new IOException("Unsupported punchcard deck format");
        int version = in.readInt();
        if (version < 1 || version > 2) throw new IOException("Unsupported punchcard deck format");
        UUID id = new UUID(in.readLong(), in.readLong()); int flags = in.readUnsignedByte();
        byte[] name = new byte[32]; in.readFully(name);
        byte[] description = new byte[32]; if (version >= 2) in.readFully(description);
        int cards = in.readUnsignedShort();
        if (flags > (version == 1 ? 3 : 7) || cards > 256) throw new IOException("Invalid punchcard deck header");
        byte[] data = new byte[cards * 256]; in.readFully(data);
        try { return new PunchcardDeck(id, name, description, data, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid punchcard deck", invalid); }
    }
    public static PunchcardDeck load(Path path) throws IOException {
        if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("Punchcard deck exceeds 64 KiB");
        try (var in = new DataInputStream(Files.newInputStream(path))) {
            var deck = read(in); if (in.read() != -1) throw new IOException("Trailing punchcard deck data"); return deck;
        }
    }
    public void save(Path path) throws IOException {
        Path target = path.toAbsolutePath(); Path temporary = Files.createTempFile(target.getParent(), ".pdeck-", ".tmp");
        try {
            try (var out = new DataOutputStream(Files.newOutputStream(temporary))) { write(out); }
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}

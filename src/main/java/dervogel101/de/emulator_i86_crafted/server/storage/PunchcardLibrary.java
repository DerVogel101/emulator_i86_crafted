package dervogel101.de.emulator_i86_crafted.server.storage;

import dervogel101.de.i86.runtime.PunchcardDeck;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** World-owned media. A UUID never silently changes sealed contents. */
public final class PunchcardLibrary {
    private final Path folder;
    private final Map<String, UUID> hashes = new LinkedHashMap<>();
    private final Map<UUID,String> owners = new LinkedHashMap<>();
    private final Map<UUID,String> mutableHashes = new HashMap<>();
    public PunchcardLibrary(Path folder) throws IOException {
        this.folder = folder; Files.createDirectories(folder);
        Path index = folder.resolve("library.index");
        if (!Files.exists(index)) return;
        try (var in = new DataInputStream(Files.newInputStream(index))) {
            if (in.readInt() != 0x50444958) throw new IOException("Unsupported punchcard index");
            int version = in.readInt(); if (version < 1 || version > 2) throw new IOException("Unsupported punchcard index");
            int count = in.readInt(); if (count < 0 || count > 1000000) throw new IOException("Invalid punchcard index size");
            var ids = new HashSet<UUID>();
            for (int i = 0; i < count; i++) {
                String hash = in.readUTF(); UUID id = new UUID(in.readLong(), in.readLong());
                if (!hash.matches("[0-9a-f]{64}") || !ids.add(id) || hashes.putIfAbsent(hash, id) != null) throw new IOException("Duplicate punchcard index entry");
            }
            if (version >= 2) {
                count = in.readInt(); if (count < 0 || count > 1000000) throw new IOException("Invalid punchcard claims");
                for (int i=0;i<count;i++) {
                    UUID id = new UUID(in.readLong(),in.readLong()); String owner = in.readUTF();
                    if (owner.isEmpty() || owner.length()>256 || owners.putIfAbsent(id,owner)!=null) throw new IOException("Invalid punchcard owner");
                }
            }
            if (in.read() != -1) throw new IOException("Trailing punchcard index data");
        }
    }
    public synchronized PunchcardDeck read(UUID id) throws IOException {
        var deck = PunchcardDeck.load(path(id));
        if (!id.equals(deck.id()) || deck.sealed() && !id.equals(hashes.get(deck.hash()))) throw new IOException("Punchcard identity/hash mismatch: " + id);
        if (!deck.sealed()) {
            var expected = mutableHashes.putIfAbsent(id,deck.hash());
            if (expected != null && !expected.equals(deck.hash())) throw new IOException("Unfinished punchcard changed externally: " + id);
        }
        return deck;
    }
    public synchronized PunchcardDeck save(PunchcardDeck deck) throws IOException {
        Path target = path(deck.id());
        PunchcardDeck previous = null;
        if (Files.exists(target)) {
            previous = PunchcardDeck.load(target);
            if (!previous.sealed() && mutableHashes.containsKey(deck.id()) && !mutableHashes.get(deck.id()).equals(previous.hash())) throw new IOException("Unfinished punchcard changed externally: " + deck.id());
            if (!previous.id().equals(deck.id()) || previous.sealed() && (!previous.hash().equals(deck.hash()) || !deck.sealed()))
                throw new IOException("Conflicting punchcard UUID: " + deck.id());
        }
        if (deck.sealed()) {
            UUID existing = hashes.get(deck.hash());
            if (existing != null) {
                var canonical = read(existing);
                if (!canonical.hash().equals(deck.hash())) throw new IOException("Punchcard index hash mismatch");
                if (!existing.equals(deck.id()) && previous != null && !previous.sealed()) Files.delete(target);
                mutableHashes.remove(deck.id());
                if (owners.remove(deck.id()) != null) saveIndex();
                return canonical;
            }
        }
        if (deck.sealed() && hashes.size() >= 1000000) throw new IOException("Punchcard library limit reached");
        deck.save(target);
        if (deck.sealed()) mutableHashes.remove(deck.id()); else mutableHashes.put(deck.id(),deck.hash());
        if (deck.sealed()) {
            hashes.put(deck.hash(), deck.id());
            owners.remove(deck.id());
            try { saveIndex(); } catch (IOException failure) { hashes.remove(deck.hash()); throw failure; }
        }
        return deck;
    }
    public synchronized void claim(UUID id,String owner) throws IOException {
        String existing = owners.get(id);
        if (existing != null && !existing.equals(owner)) throw new IOException("Unfinished punchcard deck belongs to " + existing);
        if (existing == null) {
            if (owners.size() >= 1000000) throw new IOException("Punchcard owner limit reached");
            owners.put(id,owner);
            try { saveIndex(); } catch (IOException failure) { owners.remove(id); throw failure; }
        }
    }
    private Path path(UUID id) { return folder.resolve(id.toString() + ".pdeck"); }
    private void saveIndex() throws IOException {
        Path next = Files.createTempFile(folder, ".index-", ".tmp");
        try {
            try (var out = new DataOutputStream(Files.newOutputStream(next))) {
                out.writeInt(0x50444958); out.writeInt(2); out.writeInt(hashes.size());
                for (var entry : hashes.entrySet()) { out.writeUTF(entry.getKey()); out.writeLong(entry.getValue().getMostSignificantBits()); out.writeLong(entry.getValue().getLeastSignificantBits()); }
                out.writeInt(owners.size());
                for (var entry : owners.entrySet()) { out.writeLong(entry.getKey().getMostSignificantBits()); out.writeLong(entry.getKey().getLeastSignificantBits()); out.writeUTF(entry.getValue()); }
            }
            try { Files.move(next, folder.resolve("library.index"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(next, folder.resolve("library.index"), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(next); }
    }
}

package dervogel101.de.i86.runtime;

import dervogel101.de.CPU8086;
import dervogel101.de.emulator_i86_crafted.server.storage.RomLibrary;
import dervogel101.de.emulator_i86_crafted.server.storage.WorldRecords;
import dervogel101.de.emulator_i86_crafted.server.storage.WorldStore;
import dervogel101.de.state.CPUMemory;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.UUID;

final class StorageSelfTest {
    static void run() throws Exception {
        legacyKeyboardFormats();
        romVisibility();
        romVisibilityMigration();
        Path folder = Files.createTempDirectory("i86-rom-library");
        try {
            var library = new RomLibrary(folder);
            byte[] image = new byte[CPUMemory.ROM_SIZE]; image[0] = 42;
            Files.write(folder.resolve("staged.rom"), image);
            assert Arrays.equals(image, RomLibrary.readUpload(folder, "staged.rom"));
            for (String invalid : new String[]{"../staged.rom", "C:\\staged.rom", ".", "..", "x/y"}) expectIo(() -> RomLibrary.readUpload(folder, invalid));
            Files.write(folder.resolve("oversized.rom"), new byte[CPUMemory.ROM_SIZE + 1]);
            expectIo(() -> RomLibrary.readUpload(folder, "oversized.rom"));
            UUID uploader = UUID.randomUUID();
            var entry = library.upload(image, "First", "Original metadata", uploader, "OriginalPlayer");
            assert entry.uploaderId().equals(uploader) && entry.uploaderName().equals("OriginalPlayer");
            assert library.search("  FIRST  ").equals(java.util.List.of(entry));
            assert library.search("METADATA").equals(java.util.List.of(entry)) : "Descriptions must be searchable";
            assert library.search("\n \t").equals(library.entries()) : "Blank search returns all ROMs";
            assert library.search("OriginalPlayer").isEmpty() : "Only names and descriptions are searched";
            assert library.search(".*").isEmpty() : "Search is a literal substring, not a regular expression";
            assert library.search("No matching ROM").isEmpty();
            expectIo(() -> library.upload(image, "", ""));
            expectIo(() -> library.upload(image, "Name", "x".repeat(4097)));
            expectIo(() -> library.upload(image, "Name", "", uploader, " "));
            expectIo(() -> library.upload(image, "Name", "", uploader, "x".repeat(129)));
            assert entry.id().version() == 7 && entry.id().variant() == 2;
            image[0] = 0;
            assert library.read(entry.id())[0] == 42 : "Library must own immutable image bytes";
            byte[] same = library.read(entry.id());
            try { library.upload(same, "Second", "Must not overwrite", UUID.randomUUID(), "OtherPlayer"); throw new AssertionError("Duplicate accepted"); }
            catch (RomLibrary.DuplicateRom duplicate) { assert duplicate.existing.equals(entry); }
            var reopened = new RomLibrary(folder);
            assert reopened.entries().equals(java.util.List.of(entry));
            assert reopened.read(entry.id())[0] == 42;
            expectIo(() -> reopened.upload(new byte[1], "Wrong", ""));
            Files.write(folder.resolve(entry.id() + ".rom"), new byte[CPUMemory.ROM_SIZE]);
            expectIo(() -> reopened.read(entry.id()));
            assert !reopened.available(entry.id());
            Files.delete(folder.resolve(entry.id() + ".rom"));
            assert !reopened.available(entry.id());
            expectIo(() -> reopened.read(entry.id()));
            assert reopened.entry(entry.id()).isPresent() : "Missing file must retain recovery metadata";
            reopened.delete(entry.id());
            assert reopened.entries().isEmpty() && new RomLibrary(folder).entries().isEmpty();
            var imported = reopened.upload(same, "Imported", "");
            assert imported.uploaderId() == null && imported.uploaderName().equals("Server import");
            assert new RomLibrary(folder).entry(imported.id()).orElseThrow().equals(imported);
            byte[] another = same.clone(); another[0]++;
            var searchable = reopened.upload(another, "Other ROM", "x".repeat(200) + "Deep description match");
            assert reopened.search("DESCRIPTION MATCH").equals(java.util.List.of(searchable)) : "Search includes the full description beyond the preview";
            assert reopened.search("ROM").equals(java.util.List.of(searchable));
            assert reopened.search("").equals(java.util.List.of(imported, searchable)) : "Search preserves library order";
            reopened.delete(searchable.id());
            byte[] index = Files.readAllBytes(folder.resolve("library.index"));
            byte[] invalidUploader = index.clone();
            Arrays.fill(invalidUploader, invalidUploader.length - 1 - "Server import".length(), invalidUploader.length - 1, (byte) ' ');
            Files.write(folder.resolve("library.index"), invalidUploader);
            expectIo(() -> new RomLibrary(folder));
            byte[] oldIndex = index.clone(); oldIndex[7] = 1;
            Files.write(folder.resolve("library.index"), oldIndex);
            expectIo(() -> new RomLibrary(folder));
            assert Arrays.equals(oldIndex, Files.readAllBytes(folder.resolve("library.index"))) : "Unsupported indexes must not be modified";
            Files.write(folder.resolve("library.index"), new byte[]{1, 2});
            expectIo(() -> new RomLibrary(folder));
        } finally {
            try (var files = Files.list(folder)) { for (Path file : files.toList()) Files.delete(file); }
            Files.delete(folder);
        }

        var world = new WorldRecords();
        var first = world.allocate(); var second = world.allocate();
        first.controllers.add(new WorldRecords.Location("minecraft:overworld", 1, 64, 1));
        first.romId = java.util.UUID.randomUUID(); first.shardTier = 1;
        first.hardware = HardwareProfile.complete(); first.powered = true; first.paused = true;
        first.sharedId = "test";
        var shared = world.shared.powerOn(first.id, first.sharedId); shared.write16(0, 0xABCD);
        first.execution = new CPU8086(shared).saveState(); first.nvram[0] = 42;
        first.diskId = world.createDisk();
        byte[] disk = world.disk(first.diskId); disk[0] = 13; world.updateDisk(first.diskId, disk); disk[0] = 0;
        byte[] saved = world.save();
        var restored = WorldRecords.load(saved);
        var retained = restored.mainframe(first.id).orElseThrow();
        assert retained.paused && retained.powered && retained.nvram[0] == 42;
        assert restored.disk(first.diskId)[0] == 13;
        assert restored.shared.region("TEST").read16(0) == 0xABCD : "Reload must not clear powered shared memory";
        restored.shared.powerOff(first.id);
        assert restored.shared.region("test").read16(0) == 0;
        assert restored.allocate().id > second.id;
        assert Arrays.equals(retained.execution, first.execution);
        retained.controllers.add(new WorldRecords.Location("minecraft:the_end", 2, 64, 2));
        assert retained.conflicted();
        expectIo(() -> WorldRecords.load(Arrays.copyOf(saved, saved.length - 1)));
        expectIo(() -> WorldRecords.load(Arrays.copyOf(saved, saved.length + 1)));
        Path snapshots = Files.createTempDirectory("i86-world-snapshots");
        try {
            var store = new WorldStore(snapshots);
            assert store.load().mainframes().isEmpty();
            store.save(world);
            var reopened = store.load(); assert reopened.mainframes().size() == 2;
            second.name = "Retained"; store.save(world);
            assert Files.exists(snapshots.resolve("world.dat.previous"));
            byte[] before = Files.readAllBytes(snapshots.resolve("world.dat"));
            second.status = "x".repeat(70_000);
            expectIo(() -> store.save(world));
            assert Arrays.equals(before, Files.readAllBytes(snapshots.resolve("world.dat")));
            Files.write(snapshots.resolve("world.dat"), new byte[]{1, 2});
            expectIo(store::load);
        } finally {
            try (var files = Files.list(snapshots)) { for (Path file : files.toList()) Files.delete(file); }
            Files.delete(snapshots);
        }
        System.out.println("Storage checks passed: ROM uploader persistence/validation, deduplication/immutability, UUIDv7, retained state and shared membership");
    }

    private static void romVisibility() throws IOException {
        Path folder = Files.createTempDirectory("i86-rom-visibility");
        try {
            var library = new RomLibrary(folder);
            byte[] image = new byte[CPUMemory.ROM_SIZE];
            UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
            var first = library.upload(image, "Private", "Hidden description", owner, "Owner", false);
            var second = library.upload(image, "Private again", "", owner, "Owner", false);
            var third = library.upload(image, "Other private", "", other, "Other", false);
            var shared = library.upload(image, "Public", "Shared description", owner, "Owner", true);
            assert library.entries().size() == 4 && library.entries().stream().map(RomLibrary.Entry::id).distinct().count() == 4;
            assert library.search("", owner).equals(java.util.List.of(first, second, shared));
            assert library.search("", other).equals(java.util.List.of(third, shared));
            assert library.search("", UUID.randomUUID()).equals(java.util.List.of(shared));
            assert library.search("hidden", other).isEmpty();
            assert library.search(" HIDDEN ", owner).equals(java.util.List.of(first));
            assert !first.visibleTo(null) && shared.visibleTo(null);
            expectIo(() -> library.upload(image, "Ownerless", "", null, "Server import", false));
            var reopened = new RomLibrary(folder);
            assert reopened.entries().equals(library.entries());
            for (var entry : reopened.entries()) assert Arrays.equals(image, reopened.read(entry.id()));
            reopened.delete(first.id());
            var afterPrivateDeletion = new RomLibrary(folder);
            try {
                afterPrivateDeletion.upload(image, "Duplicate public", "");
                throw new AssertionError("Deleting private copy removed public duplicate protection");
            } catch (RomLibrary.DuplicateRom duplicate) { assert duplicate.existing.equals(shared); }
            afterPrivateDeletion.delete(shared.id());
            var afterPublicDeletion = new RomLibrary(folder);
            assert afterPublicDeletion.search("", UUID.randomUUID()).isEmpty();
            var replacement = afterPublicDeletion.upload(image, "New public", "");
            assert replacement.isPublic() && !replacement.id().equals(shared.id());
            assert Arrays.equals(image, afterPublicDeletion.read(second.id()));
            assert Arrays.equals(image, afterPublicDeletion.read(third.id()));

            writeRomIndex(folder, 3, java.util.List.of(new RomLibrary.Entry(second.id(), second.hash(), "Ownerless", "", null, "Server import", false)));
            expectUnchangedIndexFailure(folder);
            writeRomIndex(folder, 3, java.util.List.of(replacement,
                    new RomLibrary.Entry(second.id(), replacement.hash(), "Duplicate public", "", owner, "Owner", true)));
            expectUnchangedIndexFailure(folder);
        } finally { deleteRomTestFolder(folder); }
    }

    private static void romVisibilityMigration() throws IOException {
        Path folder = Files.createTempDirectory("i86-rom-migration");
        try {
            var library = new RomLibrary(folder);
            byte[] image = new byte[CPUMemory.ROM_SIZE];
            var first = library.upload(image, "Old player ROM", "Description", UUID.randomUUID(), "Original player");
            byte[] other = image.clone(); other[0] = 42;
            var second = library.upload(other, "Old server ROM", "");
            var entries = java.util.List.of(first, second);
            writeRomIndex(folder, 2, entries);
            byte[] legacy = Files.readAllBytes(folder.resolve("library.index"));
            byte[] trailing = Arrays.copyOf(legacy, legacy.length + 1);
            byte[] unsupported = legacy.clone(); unsupported[7] = 1;
            byte[] future = legacy.clone(); future[7] = 4;
            for (byte[] bad : new byte[][]{trailing, Arrays.copyOf(legacy, legacy.length - 1), unsupported, future}) {
                Files.write(folder.resolve("library.index"), bad);
                expectUnchangedIndexFailure(folder);
            }
            writeRomIndex(folder, 2, java.util.List.of(first, first));
            expectUnchangedIndexFailure(folder);
            Files.write(folder.resolve("library.index"), legacy);
            var migrated = new RomLibrary(folder);
            assert migrated.entries().equals(entries) : "Migration must preserve IDs, order, metadata and uploaders";
            assert migrated.entries().stream().allMatch(RomLibrary.Entry::isPublic);
            assert migrated.search("", UUID.randomUUID()).equals(entries);
            assert Arrays.equals(image, Files.readAllBytes(folder.resolve(first.id() + ".rom")));
            assert Arrays.equals(other, Files.readAllBytes(folder.resolve(second.id() + ".rom")));
            assert Arrays.equals(image, migrated.read(first.id()));
            try (var in = new java.io.DataInputStream(Files.newInputStream(folder.resolve("library.index")))) {
                assert in.readInt() == 0x49383652 && in.readInt() == 3;
            }
            assert new RomLibrary(folder).entries().equals(entries);
            try {
                migrated.upload(image, "Replacement metadata", "");
                throw new AssertionError("Migrated public ROM must still deduplicate");
            } catch (RomLibrary.DuplicateRom duplicate) { assert duplicate.existing.equals(first); }
        } finally { deleteRomTestFolder(folder); }
    }

    private static void writeRomIndex(Path folder, int version, java.util.List<RomLibrary.Entry> entries) throws IOException {
        try (var out = new java.io.DataOutputStream(Files.newOutputStream(folder.resolve("library.index")))) {
            out.writeInt(0x49383652); out.writeInt(version); out.writeInt(entries.size());
            for (var entry : entries) {
                out.writeLong(entry.id().getMostSignificantBits()); out.writeLong(entry.id().getLeastSignificantBits());
                out.writeUTF(entry.hash()); out.writeUTF(entry.name()); out.writeUTF(entry.description());
                out.writeBoolean(entry.uploaderId() != null);
                if (entry.uploaderId() != null) {
                    out.writeLong(entry.uploaderId().getMostSignificantBits()); out.writeLong(entry.uploaderId().getLeastSignificantBits());
                }
                out.writeUTF(entry.uploaderName());
                if (version == 3) out.writeBoolean(entry.isPublic());
            }
        }
    }

    private static void expectUnchangedIndexFailure(Path folder) throws IOException {
        byte[] before = Files.readAllBytes(folder.resolve("library.index"));
        expectIo(() -> new RomLibrary(folder));
        assert Arrays.equals(before, Files.readAllBytes(folder.resolve("library.index"))) : "Invalid index must not be rewritten";
    }

    private static void deleteRomTestFolder(Path folder) throws IOException {
        try (var files = Files.list(folder)) { for (Path file : files.toList()) Files.delete(file); }
        Files.delete(folder);
    }

    private static void legacyKeyboardFormats() throws IOException {
        for (int version : new int[]{1, 2}) {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var out = new java.io.DataOutputStream(bytes)) {
                out.writeInt(0x49383657); out.writeInt(version); out.writeLong(2); out.writeLong(123);
                out.writeInt(1); out.writeLong(1);
                for (String text : new String[]{"Legacy", "Powered off", "", ""}) out.writeUTF(text);
                for (int i = 0; i < 4; i++) out.writeBoolean(false);
                out.writeShort(0); out.writeShort(0); out.writeByte(0); out.writeLong(0);
                out.writeBoolean(false); out.writeBoolean(false);
                for (var hardware : Hardware.values()) if (hardware != Hardware.KEYBOARD && hardware != Hardware.PUNCHCARD)
                    out.writeByte(HardwareProfile.complete().count(hardware));
                out.writeInt(CPUMemory.NVRAM_SIZE); out.write(new byte[CPUMemory.NVRAM_SIZE]);
                out.writeInt(0); out.writeInt(0); out.writeInt(0); out.writeInt(0); out.writeInt(0);
                if (version == 2) {
                    out.writeInt(0); out.writeInt(1); out.writeLong(1);
                    out.write(new byte[128 + 128 * 128]);
                }
            }
            var world = WorldRecords.load(bytes.toByteArray());
            var record = world.mainframe(1).orElseThrow();
            assert record.name.equals("Legacy") && world.gameTicks == 123;
            assert record.hardware.keyboardCapacity() == 0 && record.hardware.signCapacity() == 128;
            assert WorldRecords.load(world.save()).mainframe(1).orElseThrow().hardware.keyboardCapacity() == 0;
        }
        var world = new WorldRecords(); var record = world.allocate();
        var counts = new java.util.EnumMap<Hardware, Integer>(HardwareProfile.complete().counts());
        counts.put(Hardware.GPU, 3); counts.put(Hardware.KEYBOARD, 1); record.hardware = new HardwareProfile(counts);
        var location = new WorldRecords.Location("minecraft:overworld", 1, 64, 2);
        var kind = dervogel101.de.emulator_i86_crafted.server.storage.PortBindings.Kind.KEYBOARD;
        world.ports.put(location, kind, new dervogel101.de.emulator_i86_crafted.server.storage.PortBindings.Settings(
                record.id, 7, "Keyboard", true, RedstoneIrqMode.DISABLED, 0));
        var restored = WorldRecords.load(world.save());
        assert restored.mainframe(record.id).orElseThrow().hardware.keyboardCapacity() == 8;
        assert restored.ports.at(location).orElseThrow().kind() == kind;
        assert restored.ports.at(location).orElseThrow().settings().slot() == 7;
    }
    private interface IoAction { void run() throws IOException; }
    private static void expectIo(IoAction action) {
        try { action.run(); throw new AssertionError("Expected invalid data to fail"); }
        catch (IOException expected) { }
    }
}

package dervogel101.de.i86.runtime;

import dervogel101.de.CPU8086;
import dervogel101.de.emulator_i86_crafted.server.storage.RomLibrary;
import dervogel101.de.emulator_i86_crafted.server.storage.WorldRecords;
import dervogel101.de.emulator_i86_crafted.server.storage.WorldStore;
import dervogel101.de.state.CPUMemory;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;

final class StorageSelfTest {
    static void run() throws Exception {
        Path folder = Files.createTempDirectory("i86-rom-library");
        try {
            var library = new RomLibrary(folder);
            byte[] image = new byte[CPUMemory.ROM_SIZE]; image[0] = 42;
            Files.write(folder.resolve("staged.rom"), image);
            assert Arrays.equals(image, RomLibrary.readUpload(folder, "staged.rom"));
            for (String invalid : new String[]{"../staged.rom", "C:\\staged.rom", ".", "..", "x/y"}) expectIo(() -> RomLibrary.readUpload(folder, invalid));
            Files.write(folder.resolve("oversized.rom"), new byte[CPUMemory.ROM_SIZE + 1]);
            expectIo(() -> RomLibrary.readUpload(folder, "oversized.rom"));
            var entry = library.upload(image, "First", "Original metadata");
            expectIo(() -> library.upload(image, "", ""));
            expectIo(() -> library.upload(image, "Name", "x".repeat(4097)));
            assert entry.id().version() == 7 && entry.id().variant() == 2;
            image[0] = 0;
            assert library.read(entry.id())[0] == 42 : "Library must own immutable image bytes";
            byte[] same = library.read(entry.id());
            try { library.upload(same, "Second", "Must not overwrite"); throw new AssertionError("Duplicate accepted"); }
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
        System.out.println("Storage checks passed: ROM metadata/deduplication/immutability, UUIDv7, retained state and shared membership");
    }

    private interface IoAction { void run() throws IOException; }
    private static void expectIo(IoAction action) {
        try { action.run(); throw new AssertionError("Expected invalid data to fail"); }
        catch (IOException expected) { }
    }
}

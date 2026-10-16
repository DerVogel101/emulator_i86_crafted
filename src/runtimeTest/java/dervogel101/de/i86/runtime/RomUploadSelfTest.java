package dervogel101.de.i86.runtime;

import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.state.CPUMemory;
import java.io.IOException;
import java.util.Arrays;

final class RomUploadSelfTest {
    static void run() throws Exception {
        var upload = new RomUpload();
        expectInvalid(() -> upload.image());
        expectInvalid(() -> upload.append(1, new byte[RomUpload.CHUNK_BYTES]));
        expectInvalid(() -> upload.append(-1, new byte[RomUpload.CHUNK_BYTES]));
        expectInvalid(() -> upload.append(0, new byte[RomUpload.CHUNK_BYTES - 1]));
        expectInvalid(() -> upload.append(0, new byte[RomUpload.CHUNK_BYTES + 1]));
        for (int index = 0; index < RomUpload.CHUNKS; index++) {
            byte[] chunk = new byte[RomUpload.CHUNK_BYTES]; Arrays.fill(chunk, (byte) (index + 1));
            assert upload.append(index, chunk) == (index == RomUpload.CHUNKS - 1);
            chunk[0] = 0;
            if (index == 0) expectInvalid(() -> upload.append(0, new byte[RomUpload.CHUNK_BYTES]));
        }
        byte[] image = upload.image(); assert image.length == CPUMemory.ROM_SIZE;
        for (int index = 0; index < RomUpload.CHUNKS; index++) {
            assert image[index * RomUpload.CHUNK_BYTES] == index + 1;
            assert image[(index + 1) * RomUpload.CHUNK_BYTES - 1] == index + 1;
        }
        image[0] = 0; assert upload.image()[0] == 1;
        expectInvalid(() -> upload.append(RomUpload.CHUNKS, new byte[RomUpload.CHUNK_BYTES]));
        expectIo(() -> RomLibrary.validateMetadata("  ", ""));
        expectIo(() -> RomLibrary.validateMetadata("x".repeat(129), ""));
        expectIo(() -> RomLibrary.validateMetadata("ROM", "x".repeat(4097)));
        RomLibrary.validateMetadata("€ ROM", "Multiline\nDescription");
        System.out.println("ROM upload checks passed: ordered exact-size chunks, duplicate/out-of-order rejection, metadata limits");
    }
    private static void expectInvalid(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid or incomplete upload accepted"); }
        catch (IllegalArgumentException | IllegalStateException expected) { }
    }
    private interface IoAction { void run() throws IOException; }
    private static void expectIo(IoAction action) throws IOException {
        try { action.run(); throw new AssertionError("Invalid ROM file or metadata accepted"); }
        catch (IOException expected) { }
    }
}

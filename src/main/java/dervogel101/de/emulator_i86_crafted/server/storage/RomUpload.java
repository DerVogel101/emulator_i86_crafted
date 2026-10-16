package dervogel101.de.emulator_i86_crafted.server.storage;

import dervogel101.de.state.CPUMemory;

/** One bounded, ordered upload. No world file is created until all bytes arrive. */
public final class RomUpload {
    public static final int CHUNK_BYTES = 16 * 1024;
    public static final int CHUNKS = CPUMemory.ROM_SIZE / CHUNK_BYTES;
    private final byte[] image = new byte[CPUMemory.ROM_SIZE];
    private int next;
    public boolean append(int index, byte[] bytes) {
        if (index != next || index >= CHUNKS || bytes.length != CHUNK_BYTES)
            throw new IllegalArgumentException("ROM upload must contain sixteen ordered 16 KiB chunks");
        System.arraycopy(bytes, 0, image, next * CHUNK_BYTES, CHUNK_BYTES);
        return ++next == CHUNKS;
    }
    public byte[] image() {
        if (next != CHUNKS) throw new IllegalStateException("Incomplete ROM upload");
        return image.clone();
    }
}

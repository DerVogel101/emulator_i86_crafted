package dervogel101.de.i86.runtime;

import java.util.function.IntConsumer;

/** Bus connections refer to host-owned transports; CPU snapshots do not own media. */
public final class Punchcards {
    public static final int BASE = 0x8200, END = 0x89ff, SLOTS = 16;
    private final PunchcardDevice[] devices = new PunchcardDevice[32];
    private boolean enabled;
    public synchronized void enabled(boolean value) { enabled = value; }
    public synchronized void connect(boolean writer, int slot, PunchcardDevice device) {
        if (slot < 0 || slot >= 16 || device != null && device.writer != writer) throw new IllegalArgumentException("Punchcard slot");
        devices[(writer ? 16 : 0) + slot] = device;
    }
    public synchronized PunchcardDevice device(boolean writer, int slot) { return devices[(writer ? 16 : 0) + slot]; }
    public synchronized int read(int offset) { var device = devices[offset >>> 6]; return enabled && device != null ? device.read(offset & 63) : 0; }
    public synchronized byte[] snapshot(int index) {
        if (index < 0 || index >= devices.length) throw new IllegalArgumentException("Punchcard slot");
        byte[] ports = new byte[64]; var device = devices[index];
        if (enabled && device != null) synchronized (device) { for (int i=0;i<64;i++) ports[i]=(byte)device.read(i); }
        return ports;
    }
    public synchronized void write(int offset, int value) { var device = devices[offset >>> 6]; if (enabled && device != null) device.write(offset & 63, value); }
    public synchronized void beforeInstruction(IntConsumer irq) {
        if (!enabled) return;
        for (int i = 0; i < devices.length; i++) if (devices[i] != null) { devices[i].beforeInstruction(); flush(i, irq); }
    }
    public synchronized void beforeTick(IntConsumer irq) {
        if (!enabled) return;
        for (int i = 0; i < devices.length; i++) if (devices[i] != null) { devices[i].tick(); flush(i, irq); }
    }
    private void flush(int i, IntConsumer irq) { for (int n = devices[i].takeIrqs(); n > 0; n--) irq.accept((i < 16 ? 0x0500 : 0x0600) | (i & 15)); }
}

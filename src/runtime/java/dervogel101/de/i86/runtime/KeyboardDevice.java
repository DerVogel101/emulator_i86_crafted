package dervogel101.de.i86.runtime;

import java.util.ArrayDeque;
import java.util.function.IntConsumer;

/** Bounded host admission and CPU publication share one lock, including the visible head. */
public final class KeyboardDevice {
    public static final int BASE = 0x8170, SLOTS = 8, CAPACITY = 8;
    public static final int ACK_PENDING = 1, READY = 2;
    private final Slot[] slots = new Slot[SLOTS];
    private boolean enabled;

    public KeyboardDevice() { for (int i = 0; i < SLOTS; i++) slots[i] = new Slot(); }
    public synchronized void enabled(boolean value) {
        enabled = value;
        if (!value) for (var slot : slots) slot.clear();
    }
    public synchronized void connect(int index, boolean value) {
        var slot = slot(index);
        if (slot.connected != value) slot.clear();
        slot.connected = value;
    }
    public synchronized boolean offer(int index, int value) {
        var slot = slot(index);
        if (value <= 0 || value > 255) throw new IllegalArgumentException("Invalid keyboard byte");
        if (!enabled || !slot.connected || slot.queue.size() == CAPACITY) return false;
        slot.queue.addLast(value);
        return true;
    }
    public synchronized int read(int offset) {
        var slot = slot(offset >>> 1);
        if (!enabled || !slot.connected) return 0;
        return (offset & 1) == 0 ? slot.visible ? slot.queue.getFirst() : 0
                : (slot.visible ? READY : 0) | (slot.pending ? ACK_PENDING : 0);
    }
    public synchronized void write(int offset, int value) {
        var slot = slot(offset >>> 1);
        if (enabled && slot.connected && (offset & 1) != 0 && (value & 1) != 0 && slot.visible)
            slot.requested = true;
    }
    public synchronized void beforeInstruction(IntConsumer irq) {
        if (!enabled) return;
        for (int i = 0; i < SLOTS; i++) {
            var slot = slots[i];
            if (slot.requested) { slot.pending = slot.visible; slot.requested = false; }
            publish(i, slot, irq);
        }
    }
    public synchronized void beforeTick(IntConsumer irq) {
        if (!enabled) return;
        for (int i = 0; i < SLOTS; i++) {
            var slot = slots[i];
            if (slot.pending) {
                slot.queue.removeFirst(); slot.visible = slot.pending = slot.requested = false;
            }
            publish(i, slot, irq);
        }
    }
    private void publish(int index, Slot slot, IntConsumer irq) {
        if (slot.connected && !slot.visible && !slot.queue.isEmpty()) {
            slot.visible = true; irq.accept(0x0400 | index);
        }
    }
    private Slot slot(int index) {
        if (index < 0 || index >= SLOTS) throw new IllegalArgumentException("Keyboard slot: " + index);
        return slots[index];
    }
    private static final class Slot {
        final ArrayDeque<Integer> queue = new ArrayDeque<>(CAPACITY);
        boolean connected, visible, pending, requested;
        void clear() { queue.clear(); connected = visible = pending = requested = false; }
    }
}

package dervogel101.de.i86.runtime;

import java.io.*;
import java.util.*;
import java.util.function.Function;

/** One physical transport. All CPU and world access uses the same monitor. */
public final class PunchcardDevice {
    public static final int READ=1, WRITE=2, READ_NAME=4, WRITE_NAME=8, NEXT=16, EJECT=32, ACK=64, CLEAR_ERROR=128;
    public static final int CONNECTED=1, PRESENT=2, BUSY=4, READY=8, EOC=16, END=32, OUTPUT=64, ERROR=128;
    public static final int INSERTED=1, ROW_DONE=2, NAME_DONE=4, CARD_CHANGED=8, EJECTED=16, REMOVED=32, FAILED=64;
    public final boolean writer;
    private PunchcardDeck active, output;
    private byte[] buffer = new byte[32], captured = new byte[32];
    private int card, row, requested, pending, events, error, irqs, activity, sounds;
    private boolean ready;
    private int metadata, capturedMetadata;
    private long revision;

    public PunchcardDevice(boolean writer) { this.writer = writer; }
    public synchronized PunchcardDeck active() { return active; }
    public synchronized PunchcardDeck output() { return output; }
    public synchronized long revision() { return revision; }
    public synchronized boolean insert(PunchcardDeck deck) {
        if (active != null || writer && !deck.blankMedia() || !writer && !deck.sealed()) return false;
        active = deck; card = row = pending = requested = error = 0;
        if (!ready) Arrays.fill(buffer, (byte)0); event(INSERTED); return true;
    }
    public synchronized PunchcardDeck collect() {
        if (output != null) { var result = output; output = null; event(REMOVED); return result; }
        if (active == null) return null;
        var result = writer ? active.seal() : active;
        active = null; pending = requested = card = row = 0; ready = false; event(REMOVED); return result;
    }
    public synchronized void canonicalOutput(PunchcardDeck deck) { output = Objects.requireNonNull(deck); revision++; }
    public synchronized void restoreOutput(PunchcardDeck deck) {
        if (output != null || !deck.sealed()) throw new IllegalStateException("Invalid output restoration");
        output = deck; revision++;
    }
    public synchronized void ejectManually() {
        if (active == null) return;
        requested = 0; pending = EJECT; ready = false; revision++;
        if (output == null) tick();
    }
    public synchronized int read(int offset) {
        if (offset < 32) return buffer[offset] & 255;
        int count = active == null ? 0 : active.cards();
        int remaining = active == null ? 0 : writer ? 256-count : Math.max(0, count-card-(row == 8 ? 1 : 0));
        return switch (offset) {
            case 0x20 -> pending | requested;
            case 0x21 -> CONNECTED | (active != null ? PRESENT : 0) | (pending != 0 || requested != 0 ? BUSY : 0)
                    | (ready ? READY : 0) | (active != null && row == 8 ? EOC : 0)
                    | (active != null && (writer ? card == 255 && row == 8 : count == 0 || card == count-1 && row == 8) ? END : 0)
                    | (output != null ? OUTPUT : 0) | (error != 0 ? ERROR : 0);
            case 0x22 -> events; case 0x23 -> error;
            case 0x24 -> count & 255; case 0x25 -> count >>> 8;
            case 0x26 -> remaining & 255; case 0x27 -> remaining >>> 8;
            case 0x28 -> card & 255; case 0x29 -> card >>> 8; case 0x2a -> row;
            case 0x2b -> active == null ? 0 : (active.sealed() ? 1 : 0) | (active.nameWritten() ? 2 : 0) | (active.descriptionWritten() ? 4 : 0);
            case 0x2c -> metadata;
            default -> 0;
        };
    }
    public synchronized void write(int offset, int value) {
        value &= 255;
        if (offset == 0x2c) { if (value > 1) fail(1); else { metadata = value; revision++; } return; }
        if (offset < 32) { if (!ready) buffer[offset] = (byte)value; return; }
        if (offset == 0x22) { events &= ~value; revision++; return; }
        if (offset != 0x20 || value == 0) return;
        if (Integer.bitCount(value) != 1) { fail(1); return; }
        if (requested != 0 || pending != 0) { fail(2); return; }
        requested = value; captured = buffer.clone(); capturedMetadata = metadata; revision++;
    }
    public synchronized void beforeInstruction() {
        int command = requested; requested = 0;
        if (command == 0) return;
        if (command == ACK) { ready = false; revision++; return; }
        if (command == CLEAR_ERROR) { error = 0; revision++; return; }
        if (ready) { fail(2); return; }
        if (active == null) { fail(3); return; }
        if (command == READ && writer || (command == WRITE || command == WRITE_NAME) && !writer) { fail(1); return; }
        if ((command == WRITE || command == WRITE_NAME) && active.sealed()) { fail(6); return; }
        if (command == WRITE_NAME && ((capturedMetadata == 0 ? active.nameWritten() : active.descriptionWritten()) || active.cards() != 0)) { fail(7); return; }
        if ((command == READ || command == WRITE) && row == 8) { fail(4); return; }
        if (command == READ && card >= active.cards()) { fail(5); return; }
        if (command == NEXT && (row != 8 || (writer ? card == 255 : card+1 >= active.cards()))) { fail(row != 8 ? 4 : 5); return; }
        pending = command; revision++;
    }
    public synchronized void tick() {
        if (pending == 0) return;
        if (pending == EJECT && output != null) return;
        int command = pending; pending = 0;
        switch (command) {
            case READ -> { buffer = active.row(card, row++); event(ROW_DONE); activity |= 1; sounds |= 1; }
            case WRITE -> { active = active.row(card, row++, captured); event(ROW_DONE); activity |= 1; sounds |= 1; }
            case READ_NAME -> { buffer = capturedMetadata == 0 ? active.name() : active.description(); event(NAME_DONE); activity |= 1; sounds |= 1; }
            case WRITE_NAME -> { active = capturedMetadata == 0 ? active.name(captured) : active.description(captured); event(NAME_DONE); activity |= 1; sounds |= 1; }
            case NEXT -> { card++; row = 0; event(CARD_CHANGED); activity |= 4; sounds |= 2; }
            case EJECT -> { output = writer ? active.seal() : active; active = null; card = row = 0; event(EJECTED); activity |= 4; sounds |= 2; }
            default -> throw new IllegalStateException("Invalid pending punchcard command");
        }
        ready = true; revision++;
    }
    public synchronized int takeIrqs() { int n = irqs; irqs = 0; return n; }
    public synchronized int takeActivity() { int n = activity | (active != null ? 2 : 0); activity = 0; return n; }
    public synchronized int takeSounds() { int n = sounds; sounds = 0; return n; }
    public synchronized void mediaError() { fail(8); }
    private void fail(int code) { error = code; if ((events & FAILED) == 0) event(FAILED); revision++; }
    private void event(int value) { events |= value; irqs = Math.min(256,irqs+1); revision++; }

    /** Media is persisted by the host before these UUID references are published. */
    public synchronized void writeState(DataOutput out) throws IOException {
        out.writeInt(3); out.writeBoolean(writer);
        for (var deck : new PunchcardDeck[]{active, output}) {
            out.writeBoolean(deck != null);
            if (deck != null) { out.writeLong(deck.id().getMostSignificantBits()); out.writeLong(deck.id().getLeastSignificantBits()); out.writeUTF(deck.hash()); }
        }
        out.write(buffer); out.write(captured); out.writeShort(card); out.writeByte(row);
        out.writeByte(requested); out.writeByte(pending); out.writeByte(events); out.writeByte(error); out.writeBoolean(ready); out.writeInt(irqs);
        out.writeByte(metadata); out.writeByte(capturedMetadata);
    }
    public static PunchcardDevice readState(DataInput in, Function<UUID, PunchcardDeck> decks) throws IOException {
        int version = in.readInt(); if (version < 1 || version > 3) throw new IOException("Unsupported punchcard transport state");
        var device = new PunchcardDevice(in.readBoolean());
        try {
            device.active = readDeck(in, decks,version); device.output = readDeck(in, decks,version);
            in.readFully(device.buffer); in.readFully(device.captured); device.card = in.readUnsignedShort(); device.row = in.readUnsignedByte();
            device.requested = in.readUnsignedByte(); device.pending = in.readUnsignedByte(); device.events = in.readUnsignedByte(); device.error = in.readUnsignedByte();
            device.ready = in.readBoolean(); device.irqs = in.readInt();
            if (version >= 3) { device.metadata = in.readUnsignedByte(); device.capturedMetadata = in.readUnsignedByte(); }
            if (device.metadata > 1 || device.capturedMetadata > 1) throw new IOException("Invalid punchcard metadata selector");
            if (device.card > 255 || device.row > 8 || Integer.bitCount(device.requested) > 1 || Integer.bitCount(device.pending) > 1
                    || device.pending >= ACK || device.events > 127 || device.error > 8 || device.irqs < 0 || device.irqs > 65536
                    || device.active == null && (device.pending != 0 || device.card != 0 || device.row != 0)
                    || device.active != null && device.writer == device.active.sealed()
                    || device.requested != 0 && device.pending != 0 || device.ready && device.pending != 0
                    || device.active != null && device.writer && device.active.cards() != device.card + (device.row > 0 ? 1 : 0)
                    || device.active != null && !device.writer && (device.active.cards() == 0 ? device.card != 0 || device.row != 0 : device.card >= device.active.cards())
                    || device.output != null && !device.output.sealed()) throw new IOException("Invalid punchcard transport state");
            return device;
        } catch (IllegalArgumentException invalid) { throw new IOException("Missing or invalid punchcard media", invalid); }
    }
    private static PunchcardDeck readDeck(DataInput in, Function<UUID, PunchcardDeck> decks,int version) throws IOException {
        if (!in.readBoolean()) return null;
        UUID id = new UUID(in.readLong(), in.readLong()); String hash = version >= 2 ? in.readUTF() : null;
        var result = decks.apply(id);
        if (result != null && hash != null && !result.hash().equals(hash)) throw new IOException("Punchcard changed since saved transport checkpoint");
        if (result == null) throw new IOException("Missing punchcard media"); return result;
    }
}

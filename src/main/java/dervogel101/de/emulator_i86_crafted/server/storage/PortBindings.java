package dervogel101.de.emulator_i86_crafted.server.storage;

import dervogel101.de.i86.runtime.RedstoneIrqMode;
import java.io.*;
import java.util.*;

/** Saved assignments include unloaded ports, so chunk loading cannot hide conflicts. */
public final class PortBindings {
    public enum Kind { REDSTONE, SIGN }
    public record Settings(long mainframe, int slot, String name, boolean input, RedstoneIrqMode irq, int signSides) {
        public Settings {
            Objects.requireNonNull(name); Objects.requireNonNull(irq);
            if (mainframe < 0 || slot < 0 || slot > 127 || name.length() > 128 || signSides < 0 || signSides > 3)
                throw new IllegalArgumentException("Invalid port settings");
        }
        public static Settings unbound() { return new Settings(0, 0, "", true, RedstoneIrqMode.DISABLED, 0); }
    }
    public record Port(Kind kind, Settings settings) {
        public Port { Objects.requireNonNull(kind); Objects.requireNonNull(settings); }
    }
    private record Slot(long mainframe, Kind kind, int index, boolean input) {}
    private final Map<WorldRecords.Location, Port> ports = new LinkedHashMap<>();
    private final Map<Slot, Set<WorldRecords.Location>> slots = new HashMap<>();

    public void put(WorldRecords.Location location, Kind kind, Settings settings) {
        Port next = new Port(kind, settings);
        if (next.equals(ports.get(location))) return;
        remove(location);
        ports.put(location, next);
        if (settings.mainframe() != 0) slots.computeIfAbsent(slot(next), ignored -> new LinkedHashSet<>()).add(location);
    }
    public void remove(WorldRecords.Location location) {
        Port old = ports.remove(location);
        if (old == null || old.settings().mainframe() == 0) return;
        Slot slot = slot(old);
        var locations = slots.get(slot);
        locations.remove(location);
        if (locations.isEmpty()) slots.remove(slot);
    }
    public Optional<Port> at(WorldRecords.Location location) { return Optional.ofNullable(ports.get(location)); }
    public Map<WorldRecords.Location, Port> entries() { return Map.copyOf(ports); }
    public List<WorldRecords.Location> conflicts(WorldRecords.Location location) {
        Port port = ports.get(location);
        if (port == null || port.settings().mainframe() == 0) return List.of();
        var locations = slots.get(slot(port));
        return locations.size() < 2 ? List.of() : List.copyOf(locations);
    }
    private static Slot slot(Port port) { return new Slot(port.settings().mainframe(), port.kind(), port.settings().slot(),
                port.kind() == Kind.REDSTONE && port.settings().input()); }

    public void write(DataOutput out) throws IOException {
        out.writeInt(ports.size());
        for (var entry : ports.entrySet()) {
            var p = entry.getKey(); var port = entry.getValue(); var s = port.settings();
            out.writeUTF(p.dimension()); out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.z());
            out.writeByte(port.kind().ordinal()); out.writeLong(s.mainframe()); out.writeByte(s.slot());
            out.writeUTF(s.name()); out.writeBoolean(s.input()); out.writeByte(s.irq().ordinal()); out.writeByte(s.signSides());
        }
    }
    public void read(DataInput in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 100_000) throw new IOException("Invalid saved port count");
        for (int i = 0; i < count; i++) {
            try {
                var location = new WorldRecords.Location(in.readUTF(), in.readInt(), in.readInt(), in.readInt());
                int kind = in.readUnsignedByte(); long id = in.readLong(); int slot = in.readUnsignedByte();
                String name = in.readUTF(); boolean input = in.readBoolean(); int irq = in.readUnsignedByte(); int sides = in.readUnsignedByte();
                if (kind >= Kind.values().length || irq >= RedstoneIrqMode.values().length || ports.containsKey(location))
                    throw new IOException("Invalid or duplicate saved port");
                put(location, Kind.values()[kind], new Settings(id, slot, name, input, RedstoneIrqMode.values()[irq], sides));
            } catch (IllegalArgumentException invalid) { throw new IOException("Invalid saved port", invalid); }
        }
    }
}

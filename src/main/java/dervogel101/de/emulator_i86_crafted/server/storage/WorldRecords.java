package dervogel101.de.emulator_i86_crafted.server.storage;

import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.i86.runtime.HardwareProfile;
import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.IoStorageDevice;
import dervogel101.de.state.SharedMemoryGroups;
import dervogel101.de.state.SharedMemoryRegion;
import java.io.*;
import java.util.*;

/** Retained world records; mutable machine fields are owned by the server thread. */
public final class WorldRecords {
    public record Location(String dimension, int x, int y, int z) {
        public Location {
            if (dimension.length() > 256 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
                throw new IllegalArgumentException("Invalid dimension ID");
        }
        @Override public String toString() { return x + ", " + y + ", " + z + " in " + dimension; }
    }

    public static final class Mainframe {
        public final long id;
        public String name = "Mainframe";
        public String status = "Powered off";
        public String sharedId = "";
        public String structureKey = "";
        public boolean powered, paused, faulted, sharedReselection;
        public int faultCs, faultIp;
        public int shardTier;
        public long instructions;
        public UUID romId, diskId;
        public HardwareProfile hardware = new HardwareProfile(Map.of());
        public byte[] nvram = new byte[CPUMemory.NVRAM_SIZE];
        public byte[] execution = new byte[0];
        public byte[] recovery = new byte[0];
        public final byte[] redstoneOutputs = new byte[128];
        public final byte[][] signOutputs = new byte[128][128];
        public final long[] signVersions = new long[128];
        public void acceptOutputs(dervogel101.de.state.CPUIO.OutputChanges changes) {
            changes.redstone().forEach((slot, value) -> redstoneOutputs[slot] = (byte) (value & 15));
            changes.signs().forEach((slot, sign) -> {
                System.arraycopy(sign.bytes(), 0, signOutputs[slot], 0, 128); signVersions[slot]++;
            });
        }
        public final Set<Location> controllers = new LinkedHashSet<>();
        private Mainframe(long id) { this.id = id; }
        public boolean conflicted() { return controllers.size() > 1; }
        public String displayName() { return name + " [" + id + "]"; }
    }

    private long nextId = 1;
    public long gameTicks;
    private final Map<Long, Mainframe> mainframes = new LinkedHashMap<>();
    private final Map<UUID, byte[]> disks = new LinkedHashMap<>();
    public final SharedMemoryGroups shared = new SharedMemoryGroups();
    public final PortBindings ports = new PortBindings();

    public Mainframe allocate() {
        long id = nextId;
        nextId = Math.addExact(nextId, 1);
        var mainframe = new Mainframe(id); mainframes.put(id, mainframe); return mainframe;
    }
    public Optional<Mainframe> mainframe(long id) { return Optional.ofNullable(mainframes.get(id)); }
    public List<Mainframe> mainframes() { return List.copyOf(mainframes.values()); }
    public Set<UUID> diskIds() { return Set.copyOf(disks.keySet()); }
    public boolean hasDisk(UUID id) { return disks.containsKey(id); }
    public void importDisk(UUID id, byte[] bytes) {
        if (bytes.length != IoStorageDevice.SIZE) throw new IllegalArgumentException("Disk must be exactly 128 KiB");
        disks.putIfAbsent(Objects.requireNonNull(id), bytes.clone());
    }
    public UUID createDisk() {
        UUID id;
        do { id = UUID.randomUUID(); } while (disks.containsKey(id));
        disks.put(id, new byte[IoStorageDevice.SIZE]); return id;
    }
    public byte[] disk(UUID id) {
        byte[] bytes = disks.get(id);
        if (bytes == null) throw new IllegalArgumentException("Unknown disk: " + id);
        return bytes.clone();
    }
    public void updateDisk(UUID id, byte[] bytes) {
        if (!disks.containsKey(id) || bytes.length != IoStorageDevice.SIZE) throw new IllegalArgumentException("Unknown disk or wrong disk size");
        disks.put(id, bytes.clone());
    }

    public byte[] save() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(0x49383657); out.writeInt(3); out.writeLong(nextId); out.writeLong(gameTicks); out.writeInt(mainframes.size());
            for (Mainframe record : mainframes.values()) {
                out.writeLong(record.id); out.writeUTF(record.name); out.writeUTF(record.status); out.writeUTF(record.sharedId); out.writeUTF(record.structureKey);
                out.writeBoolean(record.powered); out.writeBoolean(record.paused); out.writeBoolean(record.faulted); out.writeBoolean(record.sharedReselection);
                out.writeShort(record.faultCs); out.writeShort(record.faultIp);
                out.writeByte(record.shardTier); out.writeLong(record.instructions);
                writeUuid(out, record.romId); writeUuid(out, record.diskId);
                for (Hardware part : Hardware.values()) out.writeByte(record.hardware.count(part));
                writeBytes(out, record.nvram); writeBytes(out, record.execution); writeBytes(out, record.recovery);
                out.writeInt(record.controllers.size());
                for (Location location : record.controllers) {
                    out.writeUTF(location.dimension()); out.writeInt(location.x()); out.writeInt(location.y()); out.writeInt(location.z());
                }
            }
            var regions = shared.snapshot(); out.writeInt(regions.size());
            for (var entry : regions.entrySet()) { out.writeUTF(entry.getKey()); out.write(entry.getValue()); }
            out.writeInt(disks.size());
            for (var entry : disks.entrySet()) { writeUuid(out, entry.getKey()); out.write(entry.getValue()); }
            ports.write(out);
            out.writeInt(mainframes.size());
            for (Mainframe record : mainframes.values()) {
                out.writeLong(record.id); out.write(record.redstoneOutputs);
                for (byte[] sign : record.signOutputs) out.write(sign);
            }
        }
        return bytes.toByteArray();
    }

    public static WorldRecords load(byte[] bytes) throws IOException {
        var world = new WorldRecords();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 0x49383657) throw new IOException("Unsupported mainframe world format");
            int version = in.readInt();
            if (version < 1 || version > 3) throw new IOException("Unsupported mainframe world format");
            world.nextId = in.readLong();
            world.gameTicks = in.readLong();
            if (world.nextId < 1 || world.gameTicks < 0) throw new IOException("Invalid next mainframe ID or world tick");
            int count = count(in);
            var positions = new HashSet<Location>();
            for (int i = 0; i < count; i++) {
                long id = in.readLong();
                if (id < 1 || id >= world.nextId || world.mainframes.containsKey(id)) throw new IOException("Invalid or duplicate mainframe ID");
                var record = new Mainframe(id);
                record.name = in.readUTF(); record.status = in.readUTF(); record.sharedId = in.readUTF(); record.structureKey = in.readUTF();
                record.powered = in.readBoolean(); record.paused = in.readBoolean(); record.faulted = in.readBoolean(); record.sharedReselection = in.readBoolean();
                record.faultCs = in.readUnsignedShort(); record.faultIp = in.readUnsignedShort();
                record.shardTier = in.readUnsignedByte(); record.instructions = in.readLong();
                record.romId = readUuid(in); record.diskId = readUuid(in);
                var hardware = new EnumMap<Hardware, Integer>(Hardware.class);
                for (Hardware part : Hardware.values())
                    if (version >= 3 || part != Hardware.KEYBOARD) hardware.put(part, in.readUnsignedByte());
                record.hardware = new HardwareProfile(hardware);
                record.nvram = readBytes(in, CPUMemory.NVRAM_SIZE, CPUMemory.NVRAM_SIZE);
                record.execution = readBytes(in, 0, 2 * 1024 * 1024); record.recovery = readBytes(in, 0, 1024 * 1024);
                int locations = count(in);
                for (int j = 0; j < locations; j++) {
                    var location = new Location(in.readUTF(), in.readInt(), in.readInt(), in.readInt());
                    if (!positions.add(location)) throw new IOException("Duplicate controller position");
                    record.controllers.add(location);
                }
                if (record.name.length() > 128 || record.sharedId.length() > 32 || record.structureKey.length() > 8192 || record.shardTier > 3 || record.instructions < 0
                        || record.faulted && record.powered && !record.paused
                        || record.powered && (record.execution.length == 0 || record.romId == null || record.shardTier == 0
                        || record.hardware.validationError().isPresent() || record.sharedReselection || record.controllers.size() != 1))
                    throw new IOException("Invalid mainframe record: " + id);
                if (!record.sharedId.isEmpty()) record.sharedId = SharedMemoryGroups.normalize(record.sharedId);
                world.mainframes.put(id, record);
            }
            count = count(in);
            var regions = new LinkedHashMap<String, byte[]>();
            for (int i = 0; i < count; i++) {
                String id = SharedMemoryGroups.normalize(in.readUTF());
                byte[] region = new byte[SharedMemoryRegion.SIZE]; in.readFully(region);
                if (regions.putIfAbsent(id, region) != null) throw new IOException("Duplicate shared region");
            }
            world.shared.restore(regions);
            count = count(in);
            for (int i = 0; i < count; i++) {
                UUID id = readUuid(in); byte[] disk = new byte[IoStorageDevice.SIZE]; in.readFully(disk);
                if (id == null || world.disks.putIfAbsent(id, disk) != null) throw new IOException("Invalid or duplicate disk ID");
            }
            if (version >= 2) {
                world.ports.read(in);
                int caches = count(in);
                if (caches != world.mainframes.size()) throw new IOException("Incomplete port output cache");
                var cached = new HashSet<Long>();
                for (int i = 0; i < caches; i++) {
                    long id = in.readLong(); var record = world.mainframes.get(id);
                    if (record == null || !cached.add(id)) throw new IOException("Invalid port output cache identity");
                    in.readFully(record.redstoneOutputs);
                    for (byte value : record.redstoneOutputs) if ((value & 255) > 15) throw new IOException("Invalid redstone output");
                    for (byte[] sign : record.signOutputs) in.readFully(sign);
                }
            }
            if (in.read() != -1) throw new IOException("Trailing mainframe world data");
            var activeDisks = new HashSet<UUID>();
            for (Mainframe record : world.mainframes.values()) {
                if (record.powered && record.hardware.has(Hardware.SHARED_MEMORY)) {
                    if (!regions.containsKey(record.sharedId)) throw new IOException("Missing live shared region");
                    world.shared.powerOn(record.id, record.sharedId);
                }
                if (record.powered && record.hardware.has(Hardware.DISK) && record.diskId != null
                        && (!world.disks.containsKey(record.diskId) || !activeDisks.add(record.diskId)))
                    throw new IOException("Missing or duplicate active disk");
            }
            return world;
        } catch (IllegalArgumentException failure) { throw new IOException("Invalid mainframe world data", failure); }
    }

    private static int count(DataInput in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 1_000_000) throw new IOException("Invalid world collection length");
        return count;
    }
    private static void writeBytes(DataOutput out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] readBytes(DataInput in, int minimum, int maximum) throws IOException {
        int size = in.readInt();
        if (size < minimum || size > maximum) throw new IOException("Invalid state length");
        byte[] bytes = new byte[size]; in.readFully(bytes); return bytes;
    }
    private static void writeUuid(DataOutput out, UUID id) throws IOException {
        out.writeBoolean(id != null);
        if (id != null) { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    }
    private static UUID readUuid(DataInput in) throws IOException { return in.readBoolean() ? new UUID(in.readLong(), in.readLong()) : null; }
}

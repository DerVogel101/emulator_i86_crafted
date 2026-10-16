package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import dervogel101.de.emulator_i86_crafted.server.storage.WorldRecords;
import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.state.SharedMemoryGroups;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;
import java.nio.ByteBuffer;
import java.util.UUID;

public final class ArrayBlockEntity extends BlockEntity {
    private UUID diskId;
    private byte[] carriedDisk;
    private String memoryId = "";
    public ArrayBlockEntity(BlockPos position, BlockState state) { super(ModBlockEntities.ARRAY, position, state); }
    public boolean isDisk() { return ((MachineBlock) getBlockState().getBlock()).hardware() == Hardware.DISK; }
    public UUID diskId(WorldRecords records) {
        if (diskId == null) {
            diskId = records.createDisk();
            if (carriedDisk != null) records.updateDisk(diskId, carriedDisk);
            setChanged();
        }
        else if (!records.hasDisk(diskId) && carriedDisk != null) records.importDisk(diskId, carriedDisk);
        return diskId;
    }
    public String memoryId() { return memoryId; }
    public void memoryId(String id, UUID player) {
        if (!(level instanceof ServerLevel server)) throw new IllegalStateException("Server required");
        var machines = ServerMachines.get(server.getServer());
        if (!machines.canConfigurePart(server, worldPosition, player)) throw new IllegalStateException("Power off the mainframe and obtain debugger control before changing its memory ID");
        memoryId = SharedMemoryGroups.normalize(id); setChanged();
        machines.sharedSelected(server, worldPosition, memoryId);
    }
    public void describe(Player player) {
        player.sendSystemMessage(Component.literal(isDisk() ? "Disk ID: " + (diskId == null ? "unassigned" : diskId)
                : "Memory ID: " + (memoryId.isEmpty() ? "not selected" : memoryId)));
    }
    private byte[] diskData() {
        if (level instanceof ServerLevel server && diskId != null) {
            byte[] current = ServerMachines.get(server.getServer()).snapshotDisk(diskId);
            if (current != null) return current;
        }
        return carriedDisk;
    }
    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input); diskId = input.read("disk_id", UUIDUtil.CODEC).orElse(null);
        carriedDisk = input.read("disk_contents", ModComponents.DISK_CODEC).map(ModComponents::copyBytes).orElse(null);
        memoryId = input.getStringOr("memory_id", "");
    }
    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output); output.storeNullable("disk_id", UUIDUtil.CODEC, diskId); output.putString("memory_id", memoryId);
        byte[] bytes = diskData(); if (bytes != null) output.store("disk_contents", ModComponents.DISK_CODEC, ByteBuffer.wrap(bytes));
    }
    @Override protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components); diskId = components.get(ModComponents.DISK_ID);
        memoryId = components.getOrDefault(ModComponents.MEMORY_ID, "");
        var contents = components.get(ModComponents.DISK_CONTENTS); if (contents != null) carriedDisk = ModComponents.copyBytes(contents);
    }
    @Override protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (isDisk()) {
            if (level instanceof ServerLevel server) diskId(ServerMachines.get(server.getServer()).records);
            if (diskId != null) components.set(ModComponents.DISK_ID, diskId);
            byte[] bytes = diskData(); if (bytes != null) components.set(ModComponents.DISK_CONTENTS, ByteBuffer.wrap(bytes).asReadOnlyBuffer());
        } else components.set(ModComponents.MEMORY_ID, memoryId);
    }
    @Override public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output); output.discard("disk_id"); output.discard("disk_contents"); output.discard("memory_id");
    }
}

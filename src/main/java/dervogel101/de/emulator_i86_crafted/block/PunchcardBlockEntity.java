package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.CpuHostBridge;
import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.PunchcardLibrary;
import dervogel101.de.i86.runtime.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;
import java.io.*;
import java.util.*;

public final class PunchcardBlockEntity extends PortBlockEntity implements WorldlyContainer {
    public PunchcardDevice device;
    private byte[] saved;
    private CpuHostBridge bridge;
    private int connectedSlot;
    private long persisted = -1;
    private ItemStack pendingInput = ItemStack.EMPTY;
    private ItemStack activeStack = ItemStack.EMPTY, outputStack = ItemStack.EMPTY;
    private PunchcardDeck persistedActive, persistedOutput;
    public PunchcardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PUNCHCARD,pos,state);
        device = new PunchcardDevice(((PunchcardBlock)state.getBlock()).kind() == dervogel101.de.emulator_i86_crafted.server.storage.PortBindings.Kind.PUNCHCARD_WRITER);
    }
    private PunchcardLibrary library() { return ServerMachines.get(((ServerLevel)level).getServer()).punchcards; }
    private String ownerKey() { return ((ServerLevel)level).dimension().identifier()+":"+worldPosition.asLong(); }
    public void connect(CpuHostBridge next) {
        if (bridge == next && connectedSlot == settings().slot()) return;
        if (bridge != null) bridge.submitPunchcardConnection(device.writer,connectedSlot,null);
        bridge = next; connectedSlot = settings().slot();
        if (next != null && saved == null) next.submitPunchcardConnection(device.writer,connectedSlot,device);
    }
    public void hostTick(CpuHostBridge next) {
        restore(); connect(saved == null ? next : null);
        if (saved != null) return;
        try { persist(); }
        catch (IOException failure) { status = failure.getMessage(); device.mediaError(); connect(null); }
        if (level instanceof ServerLevel server) {
            int lamps = device.takeActivity();
            var state = getBlockState();
            var visual = state.setValue(PunchcardBlock.LOADED,device.active() != null).setValue(PunchcardBlock.OUTPUT,device.output() != null)
                    .setValue(MachineBlock.ACTIVE,(lamps & 5) != 0 || (device.read(33) & PunchcardDevice.BUSY) != 0);
            if (!visual.equals(state)) server.setBlock(worldPosition,visual,net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            var machines = ServerMachines.get(server.getServer());
            machines.indicators.set(server,worldPosition,(device.read(33) & PunchcardDevice.ERROR) != 0 ? 3 : next == null ? 0 : 1,lamps);
            var machine = machines.machine(settings().mainframe());
            if (next != null && machine != null) machine.activity(Hardware.PUNCHCARD,lamps);
        }
        if (next != null && device.output() != null && level.getBlockEntity(worldPosition.below()) instanceof HopperBlockEntity hopper) {
            var stack = getItem(1);
            var remaining = HopperBlockEntity.addItem(this,hopper,stack.copy(),Direction.UP);
            if (remaining.isEmpty()) { removeItem(1,1); hopper.setChanged(); }
        }
    }
    private void restore() {
        if (saved == null) return;
        try {
            try (var input = new DataInputStream(new ByteArrayInputStream(saved))) {
                var restored = PunchcardDevice.readState(input,id -> { try { return library().read(id); } catch (IOException failure) { throw new IllegalArgumentException(failure); } });
                if (restored.writer != device.writer || input.read() != -1) throw new IOException("Invalid saved punchcard transport");
                if (restored.writer && restored.active() != null) library().claim(restored.active().id(),ownerKey());
                device = restored; saved = null;
            }
        } catch (IOException failure) { status = failure.getMessage(); device.mediaError(); }
    }
    private void syncStacks() {
        if (saved != null) return;
        if (device.active() == null && !activeStack.isEmpty()) {
            if (device.output() != null && outputStack.isEmpty()) outputStack = activeStack;
            activeStack = ItemStack.EMPTY;
        }
    }
    public void persist() throws IOException {
        synchronized (device) {
            syncStacks();
            if (device.revision() == persisted) return;
            if (device.active() != null && device.active() != persistedActive) {
                library().save(device.active()); if (device.writer) library().claim(device.active().id(),ownerKey());
            }
            if (device.output() != null && device.output() != persistedOutput) {
                var previous = device.output(); var canonical = library().save(previous);
                if (!previous.id().equals(canonical.id())) device.canonicalOutput(canonical);
            }
            persistedActive = device.active(); persistedOutput = device.output();
            persisted = device.revision(); setChanged();
        }
    }
    public boolean insert(ItemStack stack) {
        restore();
        synchronized (device) {
            syncStacks(); if (saved != null || !(stack.getItem() instanceof PunchcardDeckItem)) return false;
            if (device.active() != null || !pendingInput.isEmpty() || !device.writer && !stack.has(ModComponents.PUNCHCARD_ID)) return false;
            try {
                var id = stack.get(ModComponents.PUNCHCARD_ID);
                var deck = id == null ? PunchcardDeck.blank() : library().read(id);
                if (device.writer && id != null) return false;
                library().save(deck);
                if (device.writer) library().claim(deck.id(),ownerKey());
                if (!device.insert(deck)) return false;
                activeStack = stack.copyWithCount(1);
                setChanged(); return true;
            } catch (IOException failure) { device.mediaError(); status = failure.getMessage(); return false; }
        }
    }
    public ItemStack pickup() {
        restore(); if (saved != null) return ItemStack.EMPTY;
        synchronized (device) {
            syncStacks();
            var deck = device.output() != null ? device.output() : device.active();
            if (deck == null) { var rejected = pendingInput; pendingInput = ItemStack.EMPTY; setChanged(); return rejected; }
            try {
                var sealed = library().save(device.writer ? deck.seal() : deck);
                var result = PunchcardDeckItem.stack(sealed, device.output() != null ? outputStack : activeStack);
                if (device.output() != null) outputStack = ItemStack.EMPTY; else activeStack = ItemStack.EMPTY;
                device.collect(); setChanged(); return result;
            } catch (IOException failure) { device.mediaError(); status = failure.getMessage(); return ItemStack.EMPTY; }
        }
    }
    public void eject() {
        restore(); if (saved != null) throw new IllegalStateException(status);
        device.ejectManually();
        try { persist(); } catch (IOException failure) { device.mediaError(); throw new IllegalStateException(failure.getMessage(),failure); }
    }
    public void dropContents() {
        if (!(level instanceof ServerLevel)) return;
        connect(null);
        for (int i=0;i<2;i++) { var stack = pickup(); if (!stack.isEmpty()) Containers.dropItemStack(level,worldPosition.getX()+.5,worldPosition.getY()+.5,worldPosition.getZ()+.5,stack); }
    }
    @Override public void preRemoveSideEffects(BlockPos pos,BlockState state) { dropContents(); }
    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input); saved = input.read("punchcard_transport",com.mojang.serialization.Codec.BYTE_BUFFER).map(ModComponents::copyBytes).orElse(null);
        pendingInput = input.read("punchcard_input",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        activeStack = input.read("punchcard_active_item",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        outputStack = input.read("punchcard_output_item",ItemStack.CODEC).orElse(ItemStack.EMPTY);
    }
    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        synchronized (device) {
            try {
                if (saved == null) persist();
                if (!activeStack.isEmpty()) output.store("punchcard_active_item",ItemStack.CODEC,activeStack);
                if (!outputStack.isEmpty()) output.store("punchcard_output_item",ItemStack.CODEC,outputStack);
                if (!pendingInput.isEmpty()) output.store("punchcard_input",ItemStack.CODEC,pendingInput);
                if (saved != null) { output.putByteArray("punchcard_transport",saved); return; }
                var bytes = new ByteArrayOutputStream(); device.writeState(new DataOutputStream(bytes));
                output.putByteArray("punchcard_transport",bytes.toByteArray());
            } catch (IOException failure) { throw new UncheckedIOException("Cannot save punchcard transport",failure); }
        }
    }
    @Override public int getContainerSize() { return 2; }
    @Override public int getMaxStackSize() { return 1; }
    @Override public boolean isEmpty() { return pendingInput.isEmpty() && device.active() == null && device.output() == null; }
    @Override public ItemStack getItem(int slot) { synchronized (device) { syncStacks(); var deck = slot == 0 ? device.active() : device.output(); return deck == null ? slot == 0 ? pendingInput : ItemStack.EMPTY : PunchcardDeckItem.stack(deck, slot == 0 ? activeStack : outputStack); } }
    @Override public ItemStack removeItem(int slot,int count) { return slot == 1 && count > 0 && device.output() != null ? pickup() : ItemStack.EMPTY; }
    @Override public ItemStack removeItemNoUpdate(int slot) { return removeItem(slot,1); }
    @Override public void setItem(int slot,ItemStack stack) {
        if (stack.isEmpty()) return;
        if (slot == 0 && !insert(stack)) { pendingInput = stack.copyWithCount(1); setChanged(); }
        if (slot == 1 && device.output() == null) {
            try { device.restoreOutput(library().read(stack.get(ModComponents.PUNCHCARD_ID))); outputStack = stack.copyWithCount(1); setChanged(); }
            catch (IOException failure) { pendingInput = stack.copyWithCount(1); setChanged(); device.mediaError(); }
        }
    }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this,player); }
    @Override public void clearContent() { dropContents(); }
    @Override public boolean canPlaceItem(int slot,ItemStack stack) {
        if (slot != 0 || saved != null || !pendingInput.isEmpty() || device.active() != null || !(stack.getItem() instanceof PunchcardDeckItem)) return false;
        if (device.writer) return !stack.has(ModComponents.PUNCHCARD_ID);
        var id = stack.get(ModComponents.PUNCHCARD_ID); if (id == null) return false;
        try { return library().read(id).sealed(); } catch (IOException failure) { return false; }
    }
    @Override public int[] getSlotsForFace(Direction side) { return side == Direction.DOWN ? new int[]{1} : new int[]{0}; }
    @Override public boolean canPlaceItemThroughFace(int slot,ItemStack stack,Direction side) { return side != Direction.DOWN && canPlaceItem(slot,stack); }
    @Override public boolean canTakeItemThroughFace(int slot,ItemStack stack,Direction side) { return side == Direction.DOWN && slot == 1; }
}

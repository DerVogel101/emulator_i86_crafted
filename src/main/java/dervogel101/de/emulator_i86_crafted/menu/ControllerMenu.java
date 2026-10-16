package dervogel101.de.emulator_i86_crafted.menu;

import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.block.ControllerBlockEntity;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

public final class ControllerMenu extends AbstractContainerMenu {
    private final Container inventory;
    private final ControllerBlockEntity controller;
    private final Player viewer;
    private final DataSlot editable = new DataSlot() {
        private int value;
        @Override public int get() { return controller == null ? value : canEdit() ? 1 : 0; }
        @Override public void set(int value) { this.value = value; }
    };
    public ControllerMenu(int sync, Inventory player, BlockPos position) { this(sync, player, new SimpleContainer(9), null); }
    public ControllerMenu(int sync, Inventory player, ControllerBlockEntity controller) { this(sync, player, controller.inventory, controller); }
    private ControllerMenu(int sync, Inventory player, Container inventory, ControllerBlockEntity controller) {
        super(ModMenus.CONTROLLER, sync); this.inventory = inventory; this.controller = controller; this.viewer = player.player;
        for (int index = 0; index < 2; index++) {
            final int target = index;
            addSlot(new Slot(inventory, index, index == 0 ? 49 : 157, 59) {
                @Override public boolean mayPlace(ItemStack stack) { return canEdit() && accepts(target, stack); }
                @Override public boolean mayPickup(Player actor) { return canEdit(); }
            });
        }
        addStandardInventorySlots(player, 31, 118);
        addDataSlot(editable);
    }
    public boolean editable() { return editable.get() != 0; }
    private boolean canEdit() {
        return !viewer.isSpectator() && (controller == null ? editable.get() != 0 : controller.getLevel() instanceof ServerLevel level
                && ServerMachines.get(level.getServer()).canEdit(controller.id(), viewer.getUUID()));
    }
    public static boolean accepts(int slot, ItemStack stack) {
        return slot == 0 ? stack.is(ModContent.ITEMS.get("rom_chip")) : slot == 1 && ModContent.SPEED_SHARDS.containsKey(stack.getItem());
    }
    @Override public boolean stillValid(Player player) { return inventory.stillValid(player); }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        var slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        var stack = slot.getItem(); var original = stack.copy();
        if (index < 2) { if (!moveItemStackTo(stack, 2, slots.size(), true)) return ItemStack.EMPTY; }
        else {
            int target = accepts(0, stack) ? 0 : accepts(1, stack) ? 1 : -1;
            if (target < 0 || !moveItemStackTo(stack, target, target + 1, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, stack); return original;
    }
}

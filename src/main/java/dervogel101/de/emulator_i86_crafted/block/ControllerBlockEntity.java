package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;
import java.util.UUID;

public final class ControllerBlockEntity extends BlockEntity {
    private long id;
    public final SimpleContainer inventory = new SimpleContainer(9) {
        @Override public void setChanged() {
            ControllerBlockEntity.this.setChanged();
            if (level instanceof ServerLevel server) ServerMachines.get(server.getServer()).inventoryChanged(ControllerBlockEntity.this);
        }
        @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(ControllerBlockEntity.this, player); }
        @Override public boolean canPlaceItem(int slot, ItemStack stack) { return dervogel101.de.emulator_i86_crafted.menu.ControllerMenu.accepts(slot, stack); }
    };

    public ControllerBlockEntity(BlockPos position, BlockState state) { super(ModBlockEntities.CONTROLLER, position, state); }
    public long id() { return id; }
    public void assignId(long id) { if (this.id != 0) throw new IllegalStateException("Controller already has an ID"); this.id = id; setChanged(); }
    public UUID romId() { return inventory.getItem(0).is(ModContent.ITEMS.get("rom_chip")) ? inventory.getItem(0).get(ModComponents.ROM_ID) : null; }
    public int shardTier() {
        for (int tier = 1; tier <= 3; tier++) if (inventory.getItem(1).is(ModContent.ITEMS.get("speed_shard_" + tier))) return tier;
        return 0;
    }
    public void open(ServerPlayer player) {
        if (!(level instanceof ServerLevel server)) return;
        var machines = ServerMachines.get(server.getServer()); machines.attach(this);
        String name = machines.records.mainframe(id).map(record -> record.displayName() + " — " + record.status).orElse("Mainframe [" + id + "] — missing retained data");
        player.openMenu(new net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider<BlockPos>() {
            @Override public BlockPos getScreenOpeningData(ServerPlayer viewer) { return worldPosition; }
            @Override public Component getDisplayName() { return Component.literal(name); }
            @Override public AbstractContainerMenu createMenu(int sync, net.minecraft.world.entity.player.Inventory inventory, Player actor) {
                return new dervogel101.de.emulator_i86_crafted.menu.ControllerMenu(sync, inventory, ControllerBlockEntity.this);
            }
        });
    }
    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input); id = input.getLongOr("mainframe_id", 0);
        inventory.getItems().clear();
        ContainerHelper.loadAllItems(input, inventory.getItems());
        if (inventory.isEmpty()) inventory.fromItemList(input.listOrEmpty("inventory", ItemStack.OPTIONAL_CODEC));
        repairLegacyShardSlot();
    }
    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output); output.putLong("mainframe_id", id);
        ContainerHelper.saveAllItems(output, inventory.getItems());
    }
    @Override protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components); id = components.getOrDefault(ModComponents.MAINFRAME_ID, id);
        var contents = components.get(DataComponents.CONTAINER); if (contents != null) contents.copyInto(inventory.items);
        repairLegacyShardSlot();
    }
    private void repairLegacyShardSlot() {
        var misplaced = inventory.getItem(0);
        if (ModContent.SPEED_SHARDS.containsKey(misplaced.getItem()) && inventory.getItem(1).isEmpty()) {
            inventory.getItems().set(0, ItemStack.EMPTY);
            inventory.getItems().set(1, misplaced);
        }
    }

    @Override protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (id != 0) components.set(ModComponents.MAINFRAME_ID, id);
        components.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(inventory.items));
    }
    @Override public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output); output.discard("mainframe_id"); output.discard("inventory");
        output.discard("Items");
    }
    public ItemStack recoveryItem() {
        var item = new ItemStack(ModContent.ITEMS.get("mainframe_controller"));
        var name = components().get(DataComponents.CUSTOM_NAME); if (name != null) item.set(DataComponents.CUSTOM_NAME, name);
        item.set(ModComponents.MAINFRAME_ID, id); item.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(inventory.items)); return item;
    }
}

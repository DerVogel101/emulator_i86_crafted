package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.emulator_i86_crafted.ModComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;

public final class RomChipItem extends Item {
    public RomChipItem(Properties properties) { super(properties); }
    @Override public void inventoryTick(ItemStack stack, net.minecraft.server.level.ServerLevel level,
                                        net.minecraft.world.entity.Entity entity, net.minecraft.world.entity.EquipmentSlot slot) {
        super.inventoryTick(stack, level, entity, slot);
        var id = stack.get(ModComponents.ROM_ID);
        if (id != null && entity.tickCount % 20 == 0) {
            boolean invalid = !dervogel101.de.emulator_i86_crafted.server.ServerMachines.get(level.getServer()).roms.available(id);
            if (stack.getOrDefault(ModComponents.ROM_INVALID, false) != invalid) stack.set(ModComponents.ROM_INVALID, invalid);
        }
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        var id = stack.get(ModComponents.ROM_ID);
        tooltip.accept(Component.literal(id == null ? "Blank ROM chip" : "ROM: " + id));
        if (Boolean.TRUE.equals(stack.get(ModComponents.ROM_INVALID))) tooltip.accept(Component.literal("Invalid or missing ROM"));
    }
}

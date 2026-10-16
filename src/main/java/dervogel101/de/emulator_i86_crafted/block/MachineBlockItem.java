package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.emulator_i86_crafted.ModComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;
import java.util.function.Consumer;

public final class MachineBlockItem extends BlockItem {
    public MachineBlockItem(Block block, Properties properties) { super(block, properties); }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        var id = stack.get(ModComponents.MAINFRAME_ID); if (id != null) tooltip.accept(Component.literal("Mainframe ID: " + id));
        var disk = stack.get(ModComponents.DISK_ID); if (disk != null) tooltip.accept(Component.literal("Disk ID: " + disk));
        var memory = stack.get(ModComponents.MEMORY_ID); if (memory != null && !memory.isEmpty()) tooltip.accept(Component.literal("Memory ID: " + memory));
    }
}

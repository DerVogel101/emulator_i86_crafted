package dervogel101.de.emulator_i86_crafted.server;

import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.server.storage.RomLibrary;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import java.util.List;

public final class RomChips {
    private RomChips() {}
    public static ItemStack create(RomLibrary.Entry entry, boolean invalid) {
        var chip = new ItemStack(ModContent.ITEMS.get("rom_chip"));
        chip.set(ModComponents.ROM_ID, entry.id()); chip.set(ModComponents.ROM_INVALID, invalid);
        chip.set(DataComponents.ITEM_NAME, Component.literal(entry.name()));
        chip.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(entry.description()))));
        return chip;
    }
}

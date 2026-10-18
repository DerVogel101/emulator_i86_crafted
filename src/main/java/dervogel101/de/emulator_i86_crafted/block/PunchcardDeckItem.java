package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.i86.runtime.PunchcardDeck;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.TooltipDisplay;
import java.util.function.Consumer;

public final class PunchcardDeckItem extends Item {
    public PunchcardDeckItem(Properties properties) { super(properties); }
    public static ItemStack stack(PunchcardDeck deck) {
        return stack(deck, ItemStack.EMPTY);
    }
    public static ItemStack stack(PunchcardDeck deck, ItemStack template) {
        var stack = template.isEmpty() ? new ItemStack(ModContent.ITEMS.get("punchcard_deck")) : template.copyWithCount(1);
        stack.set(ModComponents.PUNCHCARD_ID, deck.id()); stack.set(ModComponents.PUNCHCARD_COUNT, deck.cards());
        stack.set(ModComponents.PUNCHCARD_NAME, deck.label());
        stack.set(ModComponents.PUNCHCARD_DESCRIPTION, deck.descriptionText());
        if (!deck.label().isEmpty()) stack.set(DataComponents.ITEM_NAME, Component.literal(deck.label())); stack.set(DataComponents.MAX_STACK_SIZE, 1); return stack;
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        if (!stack.has(ModComponents.PUNCHCARD_ID)) { tooltip.accept(Component.literal("Blank punchcard deck · up to 256 cards")); tooltip.accept(Component.literal("0 cards · 0 bytes")); return; }
        String name = stack.getOrDefault(ModComponents.PUNCHCARD_NAME, "");
        int count = stack.getOrDefault(ModComponents.PUNCHCARD_COUNT, 0);
        if (!name.isEmpty()) tooltip.accept(Component.literal(name));
        String description = stack.getOrDefault(ModComponents.PUNCHCARD_DESCRIPTION, "");
        if (!description.isEmpty()) tooltip.accept(Component.literal(description));
        tooltip.accept(Component.literal(count + " cards · " + count*256 + " bytes"));
        if (flag.isAdvanced()) tooltip.accept(Component.literal(stack.get(ModComponents.PUNCHCARD_ID).toString()));
    }
}

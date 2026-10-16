package dervogel101.de.emulator_i86_crafted.client.screen;

import dervogel101.de.emulator_i86_crafted.menu.ControllerMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class ControllerScreen extends AbstractContainerScreen<ControllerMenu> {
    public ControllerScreen(ControllerMenu menu, Inventory player, Component title) { super(menu, player, title, 244, 224); }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0x99050B10);
        PanelStyle.frame(g, leftPos, topPos, 244, 224, PanelStyle.CYAN);
        g.text(font, "I86 / INSTALLATION BAY", leftPos + 16, topPos + 10, PanelStyle.CYAN);
        g.text(font, font.plainSubstrByWidth(title.getString(), 211), leftPos + 16, topPos + 23, PanelStyle.TEXT);
        g.text(font, "ROM CHIP", leftPos + 33, topPos + 45, PanelStyle.MUTED);
        g.text(font, "SPEED SHARD", leftPos + 135, topPos + 45, PanelStyle.MUTED);
        g.text(font, menu.editable() ? "Unlocked / CPU powered off" : "Locked / power off to install", leftPos + 16, topPos + 88, menu.editable() ? PanelStyle.CYAN : 0xFFE7BA63);
        for (var slot : menu.slots) PanelStyle.slot(g, leftPos + slot.x, topPos + slot.y);
        g.text(font, "INVENTORY", leftPos + 31, topPos + 106, PanelStyle.MUTED);
    }
    @Override protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {}
}

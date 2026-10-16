package dervogel101.de.emulator_i86_crafted.client;

import dervogel101.de.emulator_i86_crafted.ModMenus;
import dervogel101.de.emulator_i86_crafted.network.PanelPackets;
import dervogel101.de.emulator_i86_crafted.client.screen.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;

final class ClientPanels {
    private ClientPanels() {}
    static void initialize() {
        MenuScreens.register(ModMenus.CONTROLLER, ControllerScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(PanelPackets.Open.TYPE, (packet, context) -> context.client().gui.setScreen(new ControlPanelScreen(packet.panel())));
        ClientPlayNetworking.registerGlobalReceiver(PanelPackets.Update.TYPE, (packet, context) -> {
            if (context.client().gui.screen() instanceof ControlPanelScreen screen) screen.update(packet.panel());
        });
    }
}

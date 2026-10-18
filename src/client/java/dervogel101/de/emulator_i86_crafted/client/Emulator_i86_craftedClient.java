package dervogel101.de.emulator_i86_crafted.client;

import net.fabricmc.api.ClientModInitializer;

public class Emulator_i86_craftedClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPanels.initialize();
        ClientShardTooltips.initialize();
        ClientRomUpload.initialize();
        ClientDebugger.initialize();
        ClientIndicators.initialize();
        ClientMachineSounds.initialize();
        ClientKeyboard.initialize();
    }
}

package dervogel101.de.emulator_i86_crafted;

import net.fabricmc.api.ModInitializer;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import dervogel101.de.emulator_i86_crafted.server.MachineCommands;

public class Emulator_i86_crafted implements ModInitializer {
    public static final String MOD_ID = "emulator_i86_crafted";

    @Override
    public void onInitialize() {
        ModComponents.initialize();
        ModContent.initialize();
        ModBlockEntities.initialize();
        dervogel101.de.emulator_i86_crafted.network.RomPackets.initialize();
        dervogel101.de.emulator_i86_crafted.network.DebuggerPackets.initialize();
        dervogel101.de.emulator_i86_crafted.network.PanelPackets.initialize();
        dervogel101.de.emulator_i86_crafted.network.ShardPackets.initialize();
        dervogel101.de.emulator_i86_crafted.network.IndicatorPackets.initialize();
        dervogel101.de.emulator_i86_crafted.network.KeyboardPackets.initialize();
        ModMenus.initialize();
        ServerMachines.initialize();
        MachineCommands.initialize();
        org.slf4j.LoggerFactory.getLogger(MOD_ID).info("Registered {} machine blocks and {} items", ModContent.BLOCKS.size(), ModContent.ITEMS.size());
    }
}

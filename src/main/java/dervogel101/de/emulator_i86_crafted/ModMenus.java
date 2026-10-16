package dervogel101.de.emulator_i86_crafted;

import dervogel101.de.emulator_i86_crafted.menu.ControllerMenu;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class ModMenus {
    public static final ExtendedMenuType<ControllerMenu, BlockPos> CONTROLLER = new ExtendedMenuType<>(ControllerMenu::new, BlockPos.STREAM_CODEC);
    private ModMenus() {}
    public static void initialize() { Registry.register(BuiltInRegistries.MENU, ModContent.id("controller"), CONTROLLER); }
}

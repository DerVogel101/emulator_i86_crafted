package dervogel101.de.emulator_i86_crafted;

import dervogel101.de.emulator_i86_crafted.block.ControllerBlockEntity;
import dervogel101.de.emulator_i86_crafted.block.ArrayBlockEntity;
import dervogel101.de.emulator_i86_crafted.block.PortBlockEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.Set;

public final class ModBlockEntities {
    public static BlockEntityType<ControllerBlockEntity> CONTROLLER;
    public static BlockEntityType<ArrayBlockEntity> ARRAY;
    public static BlockEntityType<PortBlockEntity> PORT;
    private ModBlockEntities() {}
    public static void initialize() {
        CONTROLLER = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ModContent.id("mainframe_controller"),
                new BlockEntityType<>(ControllerBlockEntity::new, Set.of(ModContent.BLOCKS.get("mainframe_controller"))));
        ARRAY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ModContent.id("array"), new BlockEntityType<>(ArrayBlockEntity::new,
                Set.of(ModContent.BLOCKS.get("harddisk_array"), ModContent.BLOCKS.get("ender_tunnel_array"))));
        PORT = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ModContent.id("external_port"),
                new BlockEntityType<>(PortBlockEntity::new, Set.of(ModContent.BLOCKS.get("redstone_port"), ModContent.BLOCKS.get("sign_port"), ModContent.BLOCKS.get("keyboard"))));
    }
}

package dervogel101.de.emulator_i86_crafted;

import dervogel101.de.emulator_i86_crafted.block.*;
import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.i86.runtime.ShardSpeed;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ModContent {
    public static final Map<String, MachineBlock> BLOCKS = new LinkedHashMap<>();
    public static final Map<String, Item> ITEMS = new LinkedHashMap<>();
    public static final Map<Item, ShardSpeed> SPEED_SHARDS = new LinkedHashMap<>();

    private ModContent() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Emulator_i86_crafted.MOD_ID, path);
    }

    public static void initialize() {
        for (var hardware : Hardware.values()) registerBlock(hardware.id, hardware);
        registerBlock("super_structure", null);
        registerBlock("redstone_port", null);
        registerBlock("sign_port", null);
        registerBlock("keyboard", null);
        registerBlock("rom_burner", null);
        registerBlock("punchcard_reader", null);
        registerBlock("punchcard_writer", null);
        registerItem("punchcard_deck", 4);
        registerItem("rom_chip", 1);
        for (var material : new String[]{"silicon_wafer_material", "pcb_material", "memory_chip_material",
                "blank_processing_material", "fpu_material", "antenna_array_material"}) registerItem(material, 64);
        SPEED_SHARDS.put(registerItem("speed_shard_1", 1), ShardSpeed.TIER_1);
        SPEED_SHARDS.put(registerItem("speed_shard_2", 1), ShardSpeed.TIER_2);
        SPEED_SHARDS.put(registerItem("speed_shard_3", 1), ShardSpeed.TIER_3);
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("mainframes"), FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.emulator_i86_crafted.mainframes"))
                .icon(() -> new ItemStack(ITEMS.get("rom_chip")))
                .displayItems((parameters, output) -> ITEMS.values().forEach(output::accept)).build());
    }

    private static void registerBlock(String path, Hardware hardware) {
        var key = ResourceKey.create(Registries.BLOCK, id(path));
        var properties = BlockBehaviour.Properties.of().setId(key).strength(3.5F, 6.0F)
                .sound(SoundType.METAL)
                .isRedstoneConductor((state, level, pos) -> false)
                .pushReaction(PushReaction.IMMOVEABLE)
                .lightLevel(state -> state.getValue(MachineBlock.POWERED) ? 3 : 0);
        MachineBlock machine = hardware == Hardware.CONTROLLER ? new ControllerBlock(properties)
                : hardware == Hardware.DISK || hardware == Hardware.SHARED_MEMORY ? new ArrayBlock(properties, hardware) : path.equals("redstone_port") ? new PortBlock(properties, dervogel101.de.emulator_i86_crafted.server.storage.PortBindings.Kind.REDSTONE)
                : path.equals("sign_port") ? new PortBlock(properties, dervogel101.de.emulator_i86_crafted.server.storage.PortBindings.Kind.SIGN)
                : path.equals("keyboard") ? new PortBlock(properties, dervogel101.de.emulator_i86_crafted.server.storage.PortBindings.Kind.KEYBOARD)
                : path.equals("rom_burner") ? new RomBurnerBlock(properties)
                : hardware == null && path.startsWith("punchcard_") ? new PunchcardBlock(properties, path.equals("punchcard_writer"))
                : new MachineBlock(properties, hardware);
        var block = Registry.register(BuiltInRegistries.BLOCK, key, machine);
        BLOCKS.put(path, block);
        var itemKey = ResourceKey.create(Registries.ITEM, id(path));
        var itemProperties = new Item.Properties().setId(itemKey).useBlockDescriptionPrefix();
        if (hardware == Hardware.CONTROLLER || hardware == Hardware.DISK) itemProperties.stacksTo(1);
        var item = Registry.register(BuiltInRegistries.ITEM, itemKey, new MachineBlockItem(block, itemProperties));
        ITEMS.put(path, item);
    }

    private static Item registerItem(String path, int stackSize) {
        var key = ResourceKey.create(Registries.ITEM, id(path));
        var properties = new Item.Properties().setId(key).stacksTo(stackSize);
        var item = Registry.register(BuiltInRegistries.ITEM, key, path.equals("rom_chip") ? new RomChipItem(properties) : path.equals("punchcard_deck") ? new PunchcardDeckItem(properties) : new Item(properties));
        ITEMS.put(path, item);
        return item;
    }
}

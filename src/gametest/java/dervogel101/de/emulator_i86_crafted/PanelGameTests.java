package dervogel101.de.emulator_i86_crafted;

import dervogel101.de.emulator_i86_crafted.network.*;
import dervogel101.de.emulator_i86_crafted.menu.ControllerMenu;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.*;
import java.util.*;
import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.emulator_i86_crafted.block.MachineBlock;
import net.minecraft.world.level.block.*;

public final class PanelGameTests {
    @GameTest public void shardSettingsCodecPreservesServerValuesAndRejectsInvalidBudgets(GameTestHelper test) {
        var model = new ShardPackets.Settings(List.of(
                new dervogel101.de.i86.runtime.ShardSpeed(1739, 7),
                new dervogel101.de.i86.runtime.ShardSpeed(6811, 13),
                new dervogel101.de.i86.runtime.ShardSpeed(12347, 19)));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), test.getLevel().registryAccess());
        try {
            ShardPackets.Settings.CODEC.encode(buffer, model);
            test.assertValueEqual(ShardPackets.Settings.CODEC.decode(buffer), model, "All configured tier values survive synchronization");
            test.assertValueEqual(buffer.readableBytes(), 0, "Settings use exactly three step/batch pairs");
            buffer.clear(); buffer.writeVarInt(3); buffer.writeVarInt(4);
            boolean rejected = false;
            try { ShardPackets.Settings.CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
            test.assertTrue(rejected, "Invalid server budgets cannot enter tooltip state");
            rejected = false;
            try { new ShardPackets.Settings(model.tiers().subList(0, 2)); } catch (IllegalArgumentException expected) { rejected = true; }
            test.assertTrue(rejected, "Settings require all three tiers");
        } finally { buffer.release(); }
        test.succeed();
    }

    @GameTest(maxTicks = 100) public void terminalRefreshesLatestWithoutReplacingItsSession(GameTestHelper test) {
        var wall = new MachineGameTests.Wall(test, 22528);
        try {
            var position = wall.part(Hardware.TERMINAL);
            wall.near(position);
            wall.record().execution = terminalState("Original terminal output\n");
            dervogel101.de.emulator_i86_crafted.server.MachineDialogs.open(wall.player, position);
            var first = terminalPanel(wall.player.getUUID());
            test.assertTrue(first.body().contains("Original terminal output"), "Initial terminal snapshot");
            wall.record().execution = terminalState("Older output\n".repeat(500) + "New live output\n");
            test.runAfterDelay(10, () -> {
                try {
                    var current = terminalPanel(wall.player.getUUID());
                    test.assertTrue(current != null && current.token().equals(first.token())
                            && current.body().contains("New live output"), "Latest page refreshes without changing token");
                    test.assertTrue(current.actions().stream().anyMatch(action -> action.action().equals("older")),
                            "Growing logs expose older pages without manual refresh");
                    var request = new net.minecraft.nbt.CompoundTag();
                    request.putString("token", current.token().toString()); request.putString("action", "older");
                    dervogel101.de.emulator_i86_crafted.server.MachineDialogs.submit(wall.player, request);
                    var history = terminalPanel(wall.player.getUUID());
                    wall.record().execution = terminalState("Future terminal output\n".repeat(400));
                    test.runAfterDelay(10, () -> {
                        try {
                            test.assertValueEqual(terminalPanel(wall.player.getUUID()), history, "History remains stable while reading");
                            dervogel101.de.emulator_i86_crafted.server.MachineDialogs.close(wall.player.getUUID(), history.token());
                            test.runAfterDelay(10, () -> {
                                try {
                                    test.assertTrue(terminalPanel(wall.player.getUUID()) == null, "Closed terminal is not refreshed or reopened");
                                    test.succeed();
                                } finally { wall.close(); }
                            });
                        } catch (RuntimeException | Error failure) { wall.close(); throw failure; }
                    });
                } catch (RuntimeException | Error failure) { wall.close(); throw failure; }
            });
        } catch (RuntimeException | Error failure) { wall.close(); throw failure; }
    }

    private static byte[] terminalState(String text) {
        try {
            var cpu = new dervogel101.de.CPU8086();
            cpu.hostBridge().reportHostError(text);
            return cpu.saveState();
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static ControlPanel terminalPanel(UUID player) {
        try {
            var field = dervogel101.de.emulator_i86_crafted.server.MachineDialogs.class.getDeclaredField("OPEN");
            field.setAccessible(true);
            var session = ((Map<?, ?>) field.get(null)).get(player);
            if (session == null) return null;
            var method = session.getClass().getDeclaredMethod("panel"); method.setAccessible(true);
            return (ControlPanel) method.invoke(session);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    @GameTest public void legacyAndPortableControllerInventoriesRetainSlotRoles(GameTestHelper test) {
        var state = ModContent.BLOCKS.get("mainframe_controller").defaultBlockState();
        var position = test.absolutePos(net.minecraft.core.BlockPos.ZERO);
        var registries = test.getLevel().registryAccess();
        for (int installed = 1; installed < 4; installed++) {
            var controller = new dervogel101.de.emulator_i86_crafted.block.ControllerBlockEntity(position, state);
            controller.assignId(42);
            if ((installed & 1) != 0) {
                var chip = new ItemStack(ModContent.ITEMS.get("rom_chip"));
                chip.set(ModComponents.ROM_ID, UUID.randomUUID());
                controller.inventory.setItem(0, chip);
            }
            if ((installed & 2) != 0) controller.inventory.setItem(1, new ItemStack(ModContent.ITEMS.get("speed_shard_2")));
            var legacy = controller.saveWithFullMetadata(registries);
            legacy.remove("Items");
            var old = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, registries);
            controller.inventory.storeAsItemList(old.list("inventory", ItemStack.OPTIONAL_CODEC));
            legacy.put("inventory", old.buildResult().get("inventory"));
            var restored = (dervogel101.de.emulator_i86_crafted.block.ControllerBlockEntity)
                    net.minecraft.world.level.block.entity.BlockEntity.loadStatic(position, state, legacy, registries);
            var portable = new dervogel101.de.emulator_i86_crafted.block.ControllerBlockEntity(position, state);
            portable.applyComponentsFromItemStack(restored.recoveryItem());
            test.assertValueEqual(portable.id(), 42L, "Portable controller keeps its identity");
            for (int slot = 0; slot < controller.inventory.getContainerSize(); slot++) {
                test.assertTrue(ItemStack.matches(restored.inventory.getItem(slot), controller.inventory.getItem(slot)),
                        "Legacy inventory restores installation slot " + slot);
                test.assertTrue(ItemStack.matches(portable.inventory.getItem(slot), controller.inventory.getItem(slot)),
                        "Portable inventory preserves installation slot " + slot);
            }
        }
        var misplaced = new dervogel101.de.emulator_i86_crafted.block.ControllerBlockEntity(position, state);
        misplaced.inventory.setItem(0, new ItemStack(ModContent.ITEMS.get("speed_shard_1")));
        var repaired = new dervogel101.de.emulator_i86_crafted.block.ControllerBlockEntity(position, state);
        repaired.applyComponentsFromItemStack(misplaced.recoveryItem());
        test.assertTrue(repaired.inventory.getItem(0).isEmpty() && repaired.shardTier() == 1,
                "Previously mis-slotted portable shards are repaired without losing the item");
        test.succeed();
    }

    @GameTest public void controllerInventoryPreservesEmptyRomSlotOnReload(GameTestHelper test) {
        var state = ModContent.BLOCKS.get("mainframe_controller").defaultBlockState();
        var position = test.absolutePos(net.minecraft.core.BlockPos.ZERO);
        var registries = test.getLevel().registryAccess();
        var romId = UUID.randomUUID();
        for (int installed = 0; installed < 4; installed++) {
            var controller = new dervogel101.de.emulator_i86_crafted.block.ControllerBlockEntity(position, state);
            if ((installed & 1) != 0) {
                var chip = new ItemStack(ModContent.ITEMS.get("rom_chip"));
                chip.set(ModComponents.ROM_ID, romId);
                controller.inventory.setItem(0, chip);
            }
            if ((installed & 2) != 0) controller.inventory.setItem(1, new ItemStack(ModContent.ITEMS.get("speed_shard_3")));
            var restored = (dervogel101.de.emulator_i86_crafted.block.ControllerBlockEntity)
                    net.minecraft.world.level.block.entity.BlockEntity.loadStatic(position, state,
                            controller.saveWithFullMetadata(registries), registries);
            for (int slot = 0; slot < controller.inventory.getContainerSize(); slot++)
                test.assertTrue(ItemStack.matches(restored.inventory.getItem(slot), controller.inventory.getItem(slot)),
                        "Saved inventory must preserve slot " + slot + " for installation " + installed);
            test.assertValueEqual(restored.shardTier(), (installed & 2) == 0 ? 0 : 3, "Shard remains in its installation slot");
            test.assertTrue(Objects.equals(restored.romId(), (installed & 1) == 0 ? null : romId), "ROM identity survives reload");
        }
        test.succeed();
    }

    @GameTest
    public void customPanelCodecsBoundInputAndPreserveActions(GameTestHelper test) {
        var token = UUID.randomUUID();
        var model = new ControlPanel(ControlPanel.Kind.PORT, token, "sign_port", "Sign Port", "Server-owned routing",
                List.of(ControlPanel.text("name", "Friendly name", "Display", 128), ControlPanel.option("sides", "Click IRQs", "3", List.of("0", "1", "2", "3"))),
                List.of(new ControlPanel.Action("Apply", "apply", Map.of())));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), test.getLevel().registryAccess());
        try {
            PanelPackets.Open.CODEC.encode(buffer, new PanelPackets.Open(model));
            test.assertValueEqual(PanelPackets.Open.CODEC.decode(buffer).panel(), model, "Custom screen fields/options/actions survive codec");
            buffer.clear();
            PanelPackets.Update.CODEC.encode(buffer, new PanelPackets.Update(model));
            test.assertValueEqual(PanelPackets.Update.CODEC.decode(buffer).panel(), model, "Live updates preserve bounded model and token");
            buffer.clear();
            var request = new PanelPackets.Submit(ControlPanel.Kind.PORT, token, "apply", Map.of("name", "€ Display", "sides", "3"));
            PanelPackets.Submit.CODEC.encode(buffer, request);
            var decoded = PanelPackets.Submit.CODEC.decode(buffer);
            test.assertValueEqual(decoded, request, "Unicode custom-screen edits survive codec");
            test.assertValueEqual(decoded.tag().getStringOr("token", ""), token.toString(), "Session token stays authoritative");
            boolean rejected = false;
            try { new PanelPackets.Submit(ControlPanel.Kind.PORT, token, "apply", Map.of("token", "forged")); }
            catch (IllegalArgumentException expected) { rejected = true; }
            test.assertTrue(rejected, "User fields cannot replace the session token");
            rejected = false;
            try { ControlPanel.text("name", "Name", "x".repeat(129), 128); }
            catch (IllegalArgumentException expected) { rejected = true; }
            test.assertTrue(rejected, "Field values are bounded");
            buffer.clear(); buffer.writeEnum(ControlPanel.Kind.PORT); buffer.writeUUID(token);
            buffer.writeUtf("sign_port"); buffer.writeUtf("Port"); buffer.writeUtf(""); buffer.writeVarInt(17);
            rejected = false;
            try { PanelPackets.Open.CODEC.decode(buffer); } catch (RuntimeException expected) { rejected = true; }
            test.assertTrue(rejected, "Oversized field count fails before allocation");
        } finally { buffer.release(); }
        test.succeed();
    }

    @GameTest
    public void installationBayRoutesOnlyRomAndShardAndRechecksControl(GameTestHelper test) {
        try (var wall = new MachineGameTests.Wall(test, 18432)) {
            var menu = new ControllerMenu(1, wall.player.getInventory(), wall.controller);
            test.assertValueEqual(menu.slots.size(), 38, "Only two installation slots plus player inventory");
            menu.getSlot(2).setByPlayer(new ItemStack(Items.STONE));
            test.assertTrue(menu.quickMoveStack(wall.player, 2).isEmpty() && menu.getSlot(0).getItem().isEmpty(), "Wrong items never enter the bay");
            menu.getSlot(2).setByPlayer(new ItemStack(ModContent.ITEMS.get("rom_chip")));
            test.assertFalse(menu.quickMoveStack(wall.player, 2).isEmpty(), "Shift-click routes chip into ROM slot");
            menu.getSlot(3).setByPlayer(new ItemStack(ModContent.ITEMS.get("speed_shard_2")));
            test.assertFalse(menu.quickMoveStack(wall.player, 3).isEmpty(), "Shift-click routes speed shard into shard slot");
            test.assertTrue(menu.getSlot(0).getItem().is(ModContent.ITEMS.get("rom_chip")) && menu.getSlot(1).getItem().is(ModContent.ITEMS.get("speed_shard_2")), "Slot roles are distinct");
            var other = UUID.randomUUID(); wall.machines.leases.open(wall.record().id, other);
            test.assertFalse(menu.getSlot(0).mayPickup(wall.player) || menu.getSlot(1).mayPlace(new ItemStack(ModContent.ITEMS.get("speed_shard_1"))), "A new debugger lease locks an already-open bay");
            test.assertTrue(menu.quickMoveStack(wall.player, 0).isEmpty(), "Shift-click cannot bypass lease");
            wall.machines.leases.close(wall.record().id, other);
            wall.record().powered = true;
            test.assertFalse(menu.editable() || menu.getSlot(0).mayPickup(wall.player), "Powered CPU locks installation");
            wall.record().powered = false;
        }
        for (var entry : ModContent.BLOCKS.entrySet())
            test.assertValueEqual(ModContent.ITEMS.get(entry.getKey()).getDescriptionId(), "block.emulator_i86_crafted." + entry.getKey(), "Block items use their translated block names");
        test.succeed();
    }
    @GameTest
    public void invalidControllerExplainsBlocksFacingAndComponentCounts(GameTestHelper test) {
        try (var wall = new MachineGameTests.Wall(test, 20480)) {
            var level = test.getLevel();
            var power = wall.part(Hardware.POWER); var wrong = wall.part(Hardware.NMI);
            var extra = wall.origin.offset(6, 2, 0); var invalid = wall.origin.offset(5, 2, 0);
            level.setBlock(power, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(wall.part(Hardware.GPIO), ModContent.BLOCKS.get("super_structure").defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(wall.part(Hardware.GPU), ModContent.BLOCKS.get("super_structure").defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(extra, ModContent.BLOCKS.get("atomic_clock").defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(wrong, level.getBlockState(wrong).setValue(MachineBlock.FACING, net.minecraft.core.Direction.SOUTH), Block.UPDATE_ALL);
            level.setBlock(invalid, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            var text = wall.machines.structureDiagnostics(wall.controller);
            test.assertTrue(text.contains("Structure: INVALID") && text.contains("Missing GPIO Controller") && text.contains("Missing Power Supply"), "Controller lists every missing required component");
            test.assertTrue(text.contains("Too many Atomic Clock: found 2, maximum 1"), "Controller shows actual and maximum counts");
            test.assertTrue(text.contains("(" + extra.getX() + ", " + extra.getY() + ", " + extra.getZ() + ")"), "Excess component coordinates are shown");
            test.assertTrue(text.contains("Wrong facing: expected NORTH, found SOUTH") && text.contains("(" + wrong.getX() + ", " + wrong.getY() + ", " + wrong.getZ() + ")"), "Wrong facing identifies the block");
            test.assertTrue(text.contains("Missing wall block at (" + power.getX() + ", " + power.getY() + ", " + power.getZ() + ")"), "Air holes show exact coordinates");
            test.assertTrue(text.contains("Invalid wall block: " + Blocks.STONE.getName().getString()) && text.contains("(" + invalid.getX() + ", " + invalid.getY() + ", " + invalid.getZ() + ")"), "Unsupported block type and coordinates are shown");
            test.assertFalse(text.contains("Missing GPU"), "Absent optional components are allowed");
            test.assertValueEqual(wall.machines.mainframeForPart(level, wall.origin).orElseThrow().id, wall.record().id, "Unassembled controller retains identity and screen actions");
            dervogel101.de.emulator_i86_crafted.server.MachineDialogs.open(wall.player, wall.origin);
        }
        test.succeed();
    }


}

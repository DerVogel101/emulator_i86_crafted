package dervogel101.de.emulator_i86_crafted.client;

import com.mojang.blaze3d.platform.InputConstants;
import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.block.*;
import dervogel101.de.emulator_i86_crafted.client.screen.KeyboardScreen;
import dervogel101.de.emulator_i86_crafted.network.KeyboardPackets;
import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.i86.runtime.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;
import java.util.UUID;

public final class KeyboardClientGameTest implements FabricClientGameTest {
    private record Fixture(long id, BlockPos keyboard) {}
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer(); var connection = world.getConnection();
            var fixture = server.computeOnServer(KeyboardClientGameTest::build);
            server.runCommand("tp @a 10.5 96 -1 0 24");
            connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(12);
            context.runOnClient(client -> client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(fixture.keyboard), Direction.NORTH, fixture.keyboard, false)));
            context.waitFor(client -> client.gui.screen() instanceof KeyboardScreen);
            UUID token = context.computeOnClient(client -> ((KeyboardScreen)client.gui.screen()).token());
            boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
            context.runOnClient(client -> {
                long window = client.getWindow().handle();
                client.keyboardHandler.keyPress(window, InputConstants.PRESS, new KeyEvent(InputConstants.KEY_F1, 0, 0));
                client.keyboardHandler.keyPress(window, InputConstants.PRESS, new KeyEvent(InputConstants.KEY_RETURN, 0, InputConstants.MOD_SHIFT));
                client.keyboardHandler.keyPress(window, InputConstants.PRESS, new KeyEvent(InputConstants.KEY_A, 0, 0));
                client.keyboardHandler.keyPress(window, InputConstants.REPEAT, new KeyEvent(InputConstants.KEY_A, 0, 0));
                for (int i = 0; i < 6; i++) client.keyboardHandler.keyPress(window, InputConstants.PRESS, new KeyEvent(InputConstants.KEY_B, 0, 0));
                client.keyboardHandler.keyPress(window, InputConstants.PRESS, new KeyEvent(InputConstants.KEY_B, 0, 0));
                if (client.gui.hud.isHidden() != hidden) throw new AssertionError("F1 escaped keyboard capture");
                if (client.options.keyLeft.isDown()) throw new AssertionError("Gameplay keybinding escaped keyboard capture");
            });
            context.waitTicks(4);
            server.runOnServer(host -> {
                var machine = ServerMachines.get(host).machine(fixture.id);
                if (machine.bridge().submitKeyboardInput(0, 'X')) throw new AssertionError("FIFO admitted a ninth byte");
                machine.onWorker(cpu -> {
                    cpu.beforeInstruction();
                    if (cpu.io().read8(0x8170) != 'a') throw new AssertionError("Captured text did not reach the keyboard slot");
                    return null;
                });
            });
            context.runOnClient(client -> ClientPlayNetworking.send(new KeyboardPackets.Key(UUID.randomUUID(), 'X')));
            System.out.println("I86_KEYBOARD_SCREEN=" + context.takeScreenshot(TestScreenshotOptions.of("i86-keyboard-capture").withSize(1280, 720)));
            context.runOnClient(client -> client.keyboardHandler.keyPress(client.getWindow().handle(), InputConstants.PRESS,
                    new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0)));
            context.runOnClient(client -> {
                client.keyboardHandler.keyPress(client.getWindow().handle(), InputConstants.REPEAT, new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
                client.keyboardHandler.keyPress(client.getWindow().handle(), InputConstants.RELEASE, new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0));
            });
            context.waitFor(client -> client.gui.screen() == null);
            context.waitTicks(3);
            server.runOnServer(host -> ServerMachines.get(host).machine(fixture.id).onWorker(cpu -> {
                if (cpu.io().read8(0x8170) != 'a') throw new AssertionError("Escape discarded queued input"); return null;
            }));
            context.runOnClient(client -> client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(fixture.keyboard), Direction.NORTH, fixture.keyboard, false)));
            context.waitFor(client -> client.gui.screen() instanceof KeyboardScreen);
            context.runOnClient(client -> ClientPlayNetworking.send(new KeyboardPackets.Key(token, 0)));
            context.waitTicks(3);
            context.runOnClient(client -> { if (!(client.gui.screen() instanceof KeyboardScreen)) throw new AssertionError("Stale token ended the new capture"); });
            server.runCommand("tp @a 40 96 1");
            context.waitFor(client -> client.gui.screen() == null);
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private static Fixture build(MinecraftServer server) throws Exception {
        var level = server.overworld(); var owner = ServerMachines.get(server); var origin = new BlockPos(1, 96, 1);
        for (int i = 0; i < 21; i++) {
            String id = i < Hardware.values().length ? Hardware.values()[i].id : "super_structure";
            level.setBlock(origin.offset(i % 7, i / 7, 0), ModContent.BLOCKS.get(id).defaultBlockState()
                    .setValue(MachineBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
        }
        var controller = (ControllerBlockEntity)level.getBlockEntity(origin); owner.attach(controller);
        var player = server.getPlayerList().getPlayers().getFirst();
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE); player.getAbilities().flying = true; player.onUpdateAbilities();
        int shared = Hardware.SHARED_MEMORY.ordinal();
        ((ArrayBlockEntity)level.getBlockEntity(origin.offset(shared % 7, shared / 7, 0))).memoryId("keyboard_client", player.getUUID());
        byte[] image = new byte[256 * 1024]; int reset = image.length - 16;
        image[reset] = 15; image[reset + 1] = 1; image[reset + 2] = (byte)0xeb; image[reset + 3] = (byte)0xfc;
        UUID rom;
        try { rom = owner.roms.upload(image, "Keyboard client check", "Yield without ACK").id(); }
        catch (RomLibrary.DuplicateRom duplicate) { rom = duplicate.existing.id(); }
        controller.inventory.setItem(0, RomChips.create(owner.roms.entry(rom).orElseThrow(), false));
        controller.inventory.setItem(1, new ItemStack(ModContent.ITEMS.get("speed_shard_1")));
        var keyboard = new BlockPos(10, 96, 1);
        level.setBlock(keyboard, ModContent.BLOCKS.get("keyboard").defaultBlockState(), Block.UPDATE_ALL);
        owner.ports.configure((PortBlockEntity)level.getBlockEntity(keyboard),
                new PortBindings.Settings(controller.id(), 0, "Client keyboard", true, RedstoneIrqMode.DISABLED, 0), player.getUUID());
        int power = Hardware.POWER.ordinal();
        level.setBlock(origin.offset(power % 7, power / 7, 0).south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        return new Fixture(controller.id(), keyboard);
    }
}

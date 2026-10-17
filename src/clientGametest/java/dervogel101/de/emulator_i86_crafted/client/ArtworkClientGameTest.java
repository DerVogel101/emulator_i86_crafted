package dervogel101.de.emulator_i86_crafted.client;

import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.block.*;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import dervogel101.de.emulator_i86_crafted.server.storage.PortBindings;
import dervogel101.de.i86.runtime.HardwareProfile;
import dervogel101.de.i86.runtime.RedstoneIrqMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;

import java.util.*;

/** Isolated model-state gallery; native world tests separately verify state producers. */
public final class ArtworkClientGameTest implements FabricClientGameTest {
    private static final List<String> IDS = List.copyOf(ModContent.BLOCKS.keySet());
    private static final List<Direction> MOUNTS = List.of(Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
    private static BlockPos position(int index) { return new BlockPos(1 + index % 6 * 2, 101 - index / 6 * 2, 1); }

    @Override public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("i86.client.artwork")) return;
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        boolean pause = context.computeOnClient(client -> client.options.pauseOnLostFocus);
        int fov = context.computeOnClient(client -> client.options.fov().get());
        int scale = context.computeOnClient(client -> client.options.guiScale().get());
        context.runOnClient(client -> {
            client.options.pauseOnLostFocus = false;
            client.options.fov().set(55);
            client.options.guiScale().set(2);
            client.resizeGui();
            if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
        });
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            var connection = world.getConnection();
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runOnServer(host -> {
                var level = host.overworld();
                for (int x = -6; x < 20; x++) for (int z = -10; z < 8; z++)
                    level.setBlock(new BlockPos(x, 95, z), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int i = 0; i < IDS.size(); i++) {
                    var pos = position(i);
                    var state = ModContent.BLOCKS.get(IDS.get(i)).defaultBlockState();
                    if (IDS.get(i).equals("sign_port")) state = state.setValue(PortBlock.PORT_FACING, Direction.NORTH);
                    level.setBlock(pos, state, Block.UPDATE_ALL);
                    if (level.getBlockEntity(pos) instanceof PortBlockEntity port)
                        ServerMachines.get(host).ports.unload(port);
                }
                var player = host.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            });
            server.runCommand("tp @a 0 100 -8 -35 8");
            connection.waitForClientboundPackets();
            connection.waitForChunksRender();
            context.waitTicks(15);
            capture(context, "i86-artwork-off");
            server.runOnServer(host -> power(host, true));
            connection.waitForClientboundPackets();
            connection.waitForChunksRender();
            context.waitTicks(8);
            context.runOnClient(client -> {
                for (int i = 0; i < IDS.size(); i++)
                    if (!client.level.getBlockState(position(i)).getValue(MachineBlock.POWERED))
                        throw new AssertionError("Gallery power state was not synchronized: " + IDS.get(i));
            });
            capture(context, "i86-artwork-on");
            context.runOnClient(client -> {
                try {
                    var field = ClientIndicators.class.getDeclaredField("LAMPS"); field.setAccessible(true);
                    var lamps = (java.util.Map<?, ?>) field.get(null);
                    var hdd = (dervogel101.de.i86.runtime.IndicatorState.Lamps) lamps.get(position(IDS.indexOf("harddisk_array")));
                    if (hdd == null || hdd.brightness(1, System.nanoTime()) != 1 || hdd.brightness(2, System.nanoTime()) != 1)
                        throw new AssertionError("Networked HDD activity did not remain lit between ticks");
                var point = ClientIndicators.class.getDeclaredMethod("point", float.class, float.class, Direction.class,
                        boolean.class, BlockPos.class, double.class, double.class, double.class);
                point.setAccessible(true);
                for (var facing : MOUNTS) {
                    var a = (org.joml.Vector3f) point.invoke(null, 7.2f, 2.1f, facing, true, BlockPos.ZERO, 0d, 0d, 0d);
                    var b = (org.joml.Vector3f) point.invoke(null, 7.2f, 2.9f, facing, true, BlockPos.ZERO, 0d, 0d, 0d);
                    var c = (org.joml.Vector3f) point.invoke(null, 6.4f, 2.9f, facing, true, BlockPos.ZERO, 0d, 0d, 0d);
                    float plane = switch (facing.getAxis()) { case X -> a.x; case Y -> a.y; case Z -> a.z; };
                    float expected = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 15.96f / 16 : .04f / 16;
                    var normal = new org.joml.Vector3f(b).sub(a).cross(new org.joml.Vector3f(c).sub(b)).normalize();
                    if (Math.abs(plane - expected) > .00001f || Math.abs(a.distance(b) - .8f / 16) > .00001f
                            || Math.abs(b.distance(c) - .8f / 16) > .00001f
                            || normal.dot(facing.getStepX(), facing.getStepY(), facing.getStepZ()) < .999f)
                        throw new AssertionError("Sign indicator must face outward on its attachment face: " + facing);
                }
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            context.waitTicks(10);
            capture(context, "i86-artwork-pulse");
            server.runCommand("time set midnight");
            context.waitTicks(10);
            capture(context, "i86-artwork-night");
            server.runCommand("time set noon");
            var portPos = position(IDS.indexOf("redstone_port"));
            server.runCommand("tp @a " + (portPos.getX() - 0.5) + " " + (portPos.getY() - 1) + " -2 -20 2");
            connection.waitForClientboundPackets();
            connection.waitForChunksRender();
            context.waitTicks(6);
            capture(context, "i86-artwork-input");
            server.runOnServer(host -> {
                var state = host.overworld().getBlockState(portPos);
                host.overworld().setBlock(portPos, state.setValue(PortBlock.INPUT, false), Block.UPDATE_CLIENTS);
            });
            connection.waitForClientboundPackets();
            connection.waitForChunksRender();
            context.waitTicks(6);
            capture(context, "i86-artwork-output");
            for (String id : List.of("harddisk_array", "sign_port", "redstone_port")) {
                var close = position(IDS.indexOf(id));
                server.runCommand("tp @a " + (close.getX() + .5) + " " + (close.getY() - 1) + " " + (close.getZ() - 2.5) + " 0 3");
                connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(4);
                capture(context, "i86-led-" + id);
                if (id.equals("sign_port")) {
                    server.runCommand("tp @a " + (close.getX() + .5) + " " + (close.getY() - 1) + " " + (close.getZ() + 2.5) + " 180 3");
                    connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(4);
                    capture(context, "i86-sign-casing");
                }
            }
            server.runOnServer(host -> {
                var entries = new java.util.ArrayList<dervogel101.de.emulator_i86_crafted.network.IndicatorPackets.Entry>();
                for (int i = 0; i < IDS.size(); i++) entries.add(new dervogel101.de.emulator_i86_crafted.network.IndicatorPackets.Entry(
                        position(i), IDS.get(i).equals("super_structure") ? 0 : dervogel101.de.i86.runtime.IndicatorState.pack(3, 0)));
                for (var player : host.getPlayerList().getPlayers()) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                        new dervogel101.de.emulator_i86_crafted.network.IndicatorPackets.Update(host.overworld().dimension().identifier().toString(), entries));
            });
            connection.waitForClientboundPackets(); context.waitTicks(4); capture(context, "i86-led-failure");
            server.runCommand("tp @a 0 100 8 -145 8");
            connection.waitForClientboundPackets();
            connection.waitForChunksRender();
            context.waitTicks(8);
            capture(context, "i86-artwork-rear");
            long signId = server.computeOnServer(ArtworkClientGameTest::buildCorrections);
            server.runCommand("gamemode spectator @a");
            server.runCommand("tp @a 3 94.65 15.5 180 -4");
            connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(6);
            capture(context, "i86-casing-ground");
            server.runCommand("tp @a 2.7 96 9.5 -5 0");
            connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(6);
            capture(context, "i86-casing-seams");
            server.runCommand("tp @a 20.7 96 8 -9 8");
            connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(6);
            capture(context, "i86-controller-terminal");
            server.runCommand("tp @a 29 99 -14 -5 6");
            connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(6);
            capture(context, "i86-sign-mounts");
            for (var facing : List.of(Direction.UP, Direction.DOWN, Direction.NORTH, Direction.EAST)) {
                var pos = mountedPosition(facing);
                double eyeY = facing == Direction.UP ? pos.getY() + 1 : facing == Direction.DOWN ? pos.getY() - 2.5 : pos.getY();
                server.runCommand("tp @a " + (pos.getX() - 2) + " " + eyeY + " -2.5 -32 " + (facing == Direction.UP ? 25 : facing == Direction.DOWN ? -8 : 8));
                connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(4);
                server.runOnServer(host -> {
                    var owner = ServerMachines.get(host);
                    var record = owner.records.mainframe(signId).orElseThrow();
                    for (int slot = 0; slot < MOUNTS.size(); slot++) record.signVersions[slot]++;
                    owner.ports.tick(List.of());
                    for (var player : host.getPlayerList().getPlayers()) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new dervogel101.de.emulator_i86_crafted.network.IndicatorPackets.Update(host.overworld().dimension().identifier().toString(),
                                    List.of(new dervogel101.de.emulator_i86_crafted.network.IndicatorPackets.Entry(pos,
                                            dervogel101.de.i86.runtime.IndicatorState.pack(1, 7)))));
                });
                connection.waitForClientboundPackets();
                context.waitTicks(1);
                capture(context, "i86-sign-" + facing.getName());
                context.runOnClient(client -> {
                    if (!(client.level.getBlockEntity(pos.relative(facing)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)
                            || !sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).getFirst().getString().contains(facing.name()))
                        throw new AssertionError("Mounted sign text was not synchronized: " + facing);
                });
            }
            server.runOnServer(host -> {
                var player = host.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                int slot = 9;
                for (var item : ModContent.ITEMS.values()) player.getInventory().setItem(slot++, new ItemStack(item));
                var programmed = new ItemStack(ModContent.ITEMS.get("rom_chip"));
                programmed.set(ModComponents.ROM_ID, UUID.randomUUID());
                player.getInventory().setItem(slot, programmed);
                player.inventoryMenu.broadcastChanges();
            });
            connection.waitForClientboundPackets();
            context.runOnClient(client -> {
                if (client.gui.hud.isHidden()) client.gui.hud.toggle();
                client.gui.setScreen(new InventoryScreen(client.player));
            });
            context.waitTicks(5);
            capture(context, "i86-artwork-items");
            System.out.println("I86_ARTWORK_CLIENT_OK");
        } finally {
            context.runOnClient(client -> {
                client.options.pauseOnLostFocus = pause;
                client.options.fov().set(fov);
                client.options.guiScale().set(scale);
                client.resizeGui();
                if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle();
            });
        }
    }

    private static BlockPos mountedPosition(Direction facing) {
        return new BlockPos(20 + MOUNTS.indexOf(facing) * 4, facing == Direction.DOWN ? 99 : 97, 1);
    }

    private static long buildCorrections(MinecraftServer host) {
        var level = host.overworld();
        for (int x = -2; x < 45; x++) for (int z = -3; z < 19; z++)
            level.setBlock(new BlockPos(x, 95, z), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        for (int i = 0; i < IDS.size(); i++)
            level.setBlock(new BlockPos(i % 6, 96 + i / 6, 12), ModContent.BLOCKS.get(IDS.get(i)).defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(new BlockPos(20, 96, 12), ModContent.BLOCKS.get("mainframe_controller").defaultBlockState()
                .setValue(MachineBlock.POWERED, true).setValue(MachineBlock.ACTIVE, true), Block.UPDATE_ALL);
        level.setBlock(new BlockPos(22, 96, 12), ModContent.BLOCKS.get("access_terminal").defaultBlockState()
                .setValue(MachineBlock.POWERED, true), Block.UPDATE_ALL);
        var owner = ServerMachines.get(host);
        var record = owner.records.allocate();
        record.hardware = HardwareProfile.complete();
        record.controllers.add(ServerMachines.location(level, new BlockPos(50000, 96, 50000)));
        record.powered = true;
        for (int slot = 0; slot < MOUNTS.size(); slot++) {
            var facing = MOUNTS.get(slot);
            var pos = mountedPosition(facing);
            level.setBlock(pos, ModContent.BLOCKS.get("sign_port").defaultBlockState().setValue(PortBlock.PORT_FACING, facing), Block.UPDATE_ALL);
            var port = (PortBlockEntity) level.getBlockEntity(pos);
            port.settings(new PortBindings.Settings(record.id, slot, facing.name(), true, RedstoneIrqMode.DISABLED, 3));
            var signState = facing == Direction.UP ? Blocks.OAK_SIGN.defaultBlockState()
                    : facing == Direction.DOWN ? Blocks.OAK_HANGING_SIGN.defaultBlockState()
                    : Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing);
            level.setBlock(pos.relative(facing), signState, Block.UPDATE_ALL);
            var text = ("i86 LINK " + facing.name()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            record.signOutputs[slot][0] = 0x10;
            System.arraycopy(text, 0, record.signOutputs[slot], 1, text.length);
        }
        owner.ports.tick(List.of());
        return record.id;
    }

    private static void power(MinecraftServer server, boolean powered) {
        var level = server.overworld();
        var lamps = new java.util.ArrayList<dervogel101.de.emulator_i86_crafted.network.IndicatorPackets.Entry>();
        for (int i = 0; i < IDS.size(); i++) {
            var pos = position(i);
            var state = level.getBlockState(pos);
            var next = state.setValue(MachineBlock.POWERED, powered);
            if (IDS.get(i).equals("mainframe_controller") || IDS.get(i).endsWith("_port") || IDS.get(i).equals("power_supply"))
                next = next.setValue(MachineBlock.ACTIVE, powered);
            level.setBlock(pos, next, Block.UPDATE_CLIENTS);
            lamps.add(new dervogel101.de.emulator_i86_crafted.network.IndicatorPackets.Entry(pos,
                    !powered || IDS.get(i).equals("super_structure") ? 0 : dervogel101.de.i86.runtime.IndicatorState.pack(1,
                    IDS.get(i).equals("mainframe_controller") ? 6 : 7)));
        }
        for (var player : server.getPlayerList().getPlayers()) net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dervogel101.de.emulator_i86_crafted.network.IndicatorPackets.Update(level.dimension().identifier().toString(), lamps));
    }

    private static void capture(ClientGameTestContext context, String name) {
        System.out.println("I86_ARTWORK_SCREEN=" + context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 720)));
    }
}

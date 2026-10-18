package dervogel101.de.emulator_i86_crafted.client;

import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.block.*;
import dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen;
import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.i86.runtime.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

public final class PunchcardClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("i86.client.reopen")) return;
        try (var world=context.worldBuilder().create()) {
            var server=world.getServer(); var connection=world.getConnection(); var pos=new BlockPos(0,100,0);
            server.runCommand("tp @a 0.5 100 -2 0 15"); server.runCommand("time set noon"); server.runCommand("weather clear");
            server.runOnServer(host -> {
                var level=host.overworld();
                for (int x=-5;x<14;x++) for (int z=-5;z<6;z++) level.setBlockAndUpdate(new BlockPos(x,99,z),Blocks.SMOOTH_STONE.defaultBlockState());
                level.setBlockAndUpdate(pos,ModContent.BLOCKS.get("punchcard_writer").defaultBlockState());
                var player=host.getPlayerList().getPlayers().getFirst();
                player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ModContent.ITEMS.get("punchcard_deck"),4)); player.containerMenu.broadcastChanges();
            });
            connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(4);
            click(context,pos); context.waitTicks(4);
            server.runOnServer(host -> {
                var writer=(PunchcardBlockEntity)host.overworld().getBlockEntity(pos);
                if (writer.device.active()==null || host.getPlayerList().getPlayers().getFirst().getMainHandItem().getCount()!=3) throw new AssertionError("Hand insertion did not consume one blank");
                writer.device.write(0,42); complete(writer.device,PunchcardDevice.WRITE); ack(writer.device); complete(writer.device,PunchcardDevice.EJECT); writer.hostTick(null);
                var player=host.getPlayerList().getPlayers().getFirst(); player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY); player.containerMenu.broadcastChanges();
            });
            connection.waitForClientboundPackets(); context.waitTicks(3);
            context.runOnClient(client -> {
                var state=client.level.getBlockState(pos);
                if (state.getValue(PunchcardBlock.LOADED) || !state.getValue(PunchcardBlock.OUTPUT)) throw new AssertionError("Output deck model was not synchronized");
            });
            context.runOnClient(client -> { if (!client.player.getMainHandItem().isEmpty()) throw new AssertionError("Pickup fixture still holds an item"); });
            click(context,pos); context.waitTicks(3);
            server.runOnServer(host -> {
                var writer=(PunchcardBlockEntity)host.overworld().getBlockEntity(pos);
                if (writer.device.output()!=null) throw new AssertionError("Empty-hand pickup did not collect output");
            });
            context.getInput().holdKey(options -> options.keyShift); context.waitTicks(3); connection.waitForServerboundPackets();
            context.runOnClient(client -> { if (!client.player.isShiftKeyDown()) throw new AssertionError("Shift input was not active"); });
            click(context,pos);
            context.waitFor(client -> client.gui.screen() instanceof ControlPanelScreen panel && panel.model().title().equals("Punchcard Writer"));
            context.runOnClient(client -> {
                var panel=(ControlPanelScreen)client.gui.screen();
                if (panel.model().fields().size()!=3 || panel.model().actions().stream().noneMatch(action -> action.label().equals("Eject"))) throw new AssertionError("Punchcard settings panel incomplete");
            });
            System.out.println("I86_PUNCHCARD_PANEL="+context.takeScreenshot(TestScreenshotOptions.of("i86-punchcard-panel").withSize(1280,720)));
            context.getInput().releaseKey(options -> options.keyShift);
            context.runOnClient(client -> client.gui.screen().onClose());
            server.runOnServer(host -> {
                var level=host.overworld();
                for (int i=0;i<4;i++) {
                    var at=new BlockPos(i*3,100,0); var id=i==3 ? "punchcard_controller" : i==2 ? "punchcard_reader" : "punchcard_writer";
                    level.setBlockAndUpdate(at,ModContent.BLOCKS.get(id).defaultBlockState().setValue(MachineBlock.FACING,Direction.NORTH));
                    if (level.getBlockEntity(at) instanceof PunchcardBlockEntity device) {
                        if (device.device.writer) {
                            device.insert(new ItemStack(ModContent.ITEMS.get("punchcard_deck")));
                            if (i==0) { complete(device.device,PunchcardDevice.WRITE); ack(device.device); complete(device.device,PunchcardDevice.EJECT); ack(device.device); device.insert(new ItemStack(ModContent.ITEMS.get("punchcard_deck"))); }
                        } else {
                            try { var deck=ServerMachines.get(host).punchcards.save(PunchcardDeck.blank().row(0,0,new byte[32]).seal()); device.insert(PunchcardDeckItem.stack(deck)); }
                            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                        }
                        device.hostTick(null); ServerMachines.get(host).ports.unload(device);
                    }
                }
            });
            server.runCommand("tp @a 4.5 101 -7 0 12"); connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(6);
            System.out.println("I86_PUNCHCARD_MODELS="+context.takeScreenshot(TestScreenshotOptions.of("i86-punchcard-models").withSize(1280,720)));
            System.out.println("I86_PUNCHCARD_CLIENT_OK");
        }
    }
    private static void click(ClientGameTestContext context,BlockPos pos) { context.runOnClient(client -> client.gameMode.useItemOn(client.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.NORTH,pos,false))); }
    private static void complete(PunchcardDevice d,int command) { d.write(32,command); d.beforeInstruction(); d.tick(); }
    private static void ack(PunchcardDevice d) { d.write(32,PunchcardDevice.ACK); d.beforeInstruction(); }
}

package dervogel101.de.emulator_i86_crafted;

import dervogel101.de.emulator_i86_crafted.block.MachineBlock;
import dervogel101.de.emulator_i86_crafted.network.RomPackets;
import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class BurnerGameTests {
    private static byte[] image() {
        byte[] image = new byte[256 * 1024];
        byte[] marker = UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(marker, 0, image, 0, marker.length); return image;
    }
    private static ServerPlayer player(GameTestHelper test, BlockPos position) {
        var player = test.makeMockServerPlayerInLevel(); var absolute = test.absolutePos(position);
        player.setPos(absolute.getX() + 0.5, absolute.getY() + 0.5, absolute.getZ() + 0.5); return player;
    }
    private static void send(RomBurner burner, ServerPlayer player, UUID token, byte[] bytes) {
        for (int index = 0; index < RomUpload.CHUNKS; index++) burner.chunk(player, new RomPackets.Chunk(token, index,
                Arrays.copyOfRange(bytes, index * RomUpload.CHUNK_BYTES, (index + 1) * RomUpload.CHUNK_BYTES)));
    }
    private static void remove(ServerMachines machines, ServerPlayer player) {
        machines.burner.disconnect(player.getUUID()); player.level().getServer().getPlayerList().remove(player);
    }
    @GameTest public void uploadSessionsRequirePlayerContextAndCompleteOrderedData(GameTestHelper test) throws Exception {
        var machines = ServerMachines.get(test.getLevel().getServer()); var burner = machines.burner;
        var position = new BlockPos(2, 2, 2); test.setBlock(position, ModContent.BLOCKS.get("rom_burner"));
        var player = player(test, position); var other = player(test, position); UUID uploaded = null;
        int before = machines.roms.entries().size(); byte[] bytes = image();
        try {
            burner.open(player, test.absolutePos(position));
            UUID token = burner.prepareUpload(player, "First metadata", "Original description");
            burner.chunk(other, new RomPackets.Chunk(token, 0, Arrays.copyOf(bytes, RomUpload.CHUNK_BYTES)));
            test.assertValueEqual(machines.roms.entries().size(), before, "Another player cannot supply upload chunks");
            for (int index = 0; index < RomUpload.CHUNKS; index++) {
                burner.chunk(player, new RomPackets.Chunk(token, index, Arrays.copyOfRange(bytes,
                        index * RomUpload.CHUNK_BYTES, (index + 1) * RomUpload.CHUNK_BYTES)));
                test.assertValueEqual(machines.roms.entries().size(), before + (index == RomUpload.CHUNKS - 1 ? 1 : 0), "Partial uploads must never enter the library");
            }
            var entry = machines.roms.entries().getLast(); uploaded = entry.id();
            test.assertTrue(Arrays.equals(machines.roms.read(uploaded), bytes), "Server must save the exact uploaded ROM");
            token = burner.prepareUpload(player, "Replacement metadata", "Must not win"); send(burner, player, token, bytes);
            test.assertValueEqual(machines.roms.entries().size(), before + 1, "Duplicate upload must reuse the existing file");
            test.assertValueEqual(machines.roms.entry(uploaded).orElseThrow().name(), "First metadata", "First ROM name wins");
            test.assertValueEqual(machines.roms.entry(uploaded).orElseThrow().description(), "Original description", "First ROM description wins");
            token = burner.prepareUpload(player, "Bad sequence", "");
            burner.chunk(player, new RomPackets.Chunk(token, 1, new byte[RomUpload.CHUNK_BYTES])); send(burner, player, token, image());
            test.assertValueEqual(machines.roms.entries().size(), before + 1, "Out-of-order transfer must be discarded");
            token = burner.prepareUpload(player, "Disconnected", "");
            burner.chunk(player, new RomPackets.Chunk(token, 0, new byte[RomUpload.CHUNK_BYTES]));
            burner.disconnect(player.getUUID()); send(burner, player, token, image());
            test.assertValueEqual(machines.roms.entries().size(), before + 1, "Disconnect must discard a partial upload");
            burner.open(player, test.absolutePos(position)); token = burner.prepareUpload(player, "Too far", "");
            player.setPos(player.getX() + 20, player.getY(), player.getZ()); send(burner, player, token, image());
            test.assertValueEqual(machines.roms.entries().size(), before + 1, "Uploads require remaining near the burner");
            var absolute = test.absolutePos(position); player.setPos(absolute.getX() + 0.5, absolute.getY() + 0.5, absolute.getZ() + 0.5);
            burner.open(player, absolute); token = burner.prepareUpload(player, "Removed burner", "");
            test.setBlock(position, Blocks.AIR); send(burner, player, token, image());
            test.assertValueEqual(machines.roms.entries().size(), before + 1, "Removing the burner must invalidate its upload context");
        } finally {
            remove(machines, player); remove(machines, other);
            if (uploaded != null) machines.roms.delete(uploaded);
        }
        test.succeed();
    }
    @GameTest public void burningConsumesOnlyBlankChipsAndPreservesPermanentReferences(GameTestHelper test) throws Exception {
        var machines = ServerMachines.get(test.getLevel().getServer());
        var first = machines.roms.upload(image(), "Native chip", "First image");
        var second = machines.roms.upload(image(), "Other image", "Other description");
        var player = player(test, new BlockPos(2, 2, 2));
        try {
            player.getInventory().clearContent(); player.getInventory().setItem(0, new ItemStack(ModContent.ITEMS.get("rom_chip")));
            machines.burner.burn(player, first.id());
            var original = player.getInventory().getItem(0);
            test.assertValueEqual(original.get(ModComponents.ROM_ID), first.id(), "Burned chip must reference the saved world ROM");
            test.assertValueEqual(original.get(DataComponents.ITEM_NAME).getString(), first.name(), "Chip displays library metadata");
            boolean rejected = false;
            try { machines.burner.burn(player, second.id()); } catch (IllegalStateException expected) { rejected = true; }
            test.assertTrue(rejected, "An already-burned chip cannot be rewritten");
            test.assertValueEqual(original.get(ModComponents.ROM_ID), first.id(), "Failed reburn must preserve the first reference");
            player.getInventory().setItem(1, new ItemStack(ModContent.ITEMS.get("rom_chip"))); machines.burner.burn(player, second.id());
            test.assertValueEqual(player.getInventory().getItem(1).get(ModComponents.ROM_ID), second.id(), "A second image needs a second blank chip");
            machines.roms.delete(first.id()); player.tickCount = 20;
            original.getItem().inventoryTick(original, test.getLevel(), player, EquipmentSlot.MAINHAND);
            test.assertTrue(original.getOrDefault(ModComponents.ROM_INVALID, false), "Missing ROM reference must be marked invalid");
            test.assertValueEqual(original.get(ModComponents.ROM_ID), first.id(), "Invalid chip must retain its permanent reference");
        } finally {
            remove(machines, player);
            if (machines.roms.entry(first.id()).isPresent()) machines.roms.delete(first.id());
            machines.roms.delete(second.id());
        }
        test.succeed();
    }
    @GameTest public void uploadPacketCodecBoundsChunksBeforeAllocation(GameTestHelper test) {
        var raw = Unpooled.buffer(); var buffer = new RegistryFriendlyByteBuf(raw, test.getLevel().registryAccess());
        try {
            UUID token = UUID.randomUUID(); byte[] bytes = new byte[RomUpload.CHUNK_BYTES]; bytes[0] = 42;
            var packet = new RomPackets.Chunk(token, 3, bytes); bytes[0] = 0;
            RomPackets.Chunk.CODEC.encode(buffer, packet); var decoded = RomPackets.Chunk.CODEC.decode(buffer);
            test.assertValueEqual(decoded.token(), token, "Upload token round trip"); test.assertValueEqual(decoded.index(), 3, "Chunk index round trip");
            test.assertValueEqual(decoded.bytes()[0], (byte) 42, "Packet must own its bounded bytes");
            buffer.clear(); buffer.writeUUID(token); buffer.writeVarInt(0); buffer.writeByteArray(new byte[RomUpload.CHUNK_BYTES + 1]);
            boolean rejected = false;
            try { RomPackets.Chunk.CODEC.decode(buffer); } catch (RuntimeException expected) { rejected = true; }
            test.assertTrue(rejected, "Network decoder must reject oversized chunks");
        } finally { raw.release(); }
        test.succeed();
    }
    @GameTest public void inactiveBurnerClearsItsSavedPoweredState(GameTestHelper test) {
        var position = new BlockPos(2, 2, 2); var block = ModContent.BLOCKS.get("rom_burner");
        test.setBlock(position, block.defaultBlockState().setValue(MachineBlock.POWERED, true));
        test.getLevel().scheduleTick(test.absolutePos(position), block, 1);
        test.runAfterDelay(2, () -> {
            test.assertBlockProperty(position, MachineBlock.POWERED, false); test.succeed();
        });
    }
}

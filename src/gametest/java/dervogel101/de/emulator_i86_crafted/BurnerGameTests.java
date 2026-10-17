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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private static Object sessionField(RomBurner burner, UUID player, String name) throws ReflectiveOperationException {
        var sessions = RomBurner.class.getDeclaredField("sessions"); sessions.setAccessible(true);
        var session = ((Map<?, ?>) sessions.get(burner)).get(player);
        var field = session.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(session);
    }
    private static void libraryAction(RomBurner burner, ServerPlayer player, String action, String search) throws ReflectiveOperationException {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("token", sessionField(burner, player.getUUID(), "menuNonce").toString());
        tag.putString("action", action); tag.putString("search", search); burner.submit(player, tag);
    }
    private static void done(RomBurner burner, ServerPlayer player, String name, String description) throws ReflectiveOperationException {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("token", sessionField(burner, player.getUUID(), "menuNonce").toString());
        tag.putString("action", "done"); tag.putString("name", name); tag.putString("description", description);
        burner.submit(player, tag);
    }
    @GameTest public void creationSavesOnlyOnDoneWithLatestMetadataAndCancelDiscardsDraft(GameTestHelper test) throws Exception {
        var machines = ServerMachines.get(test.getLevel().getServer()); var clock = new AtomicLong();
        var burner = new RomBurner(machines, clock::get);
        var position = new BlockPos(2, 2, 2); test.setBlock(position, ModContent.BLOCKS.get("rom_burner"));
        var player = player(test, position); UUID saved = null; int before = machines.roms.entries().size();
        try {
            burner.open(player, test.absolutePos(position)); libraryAction(burner, player, "create", "");
            done(burner, player, "No file", "");
            test.assertValueEqual(machines.roms.entries().size(), before, "Done without a file creates nothing");
            UUID token = burner.prepareUpload(player, "Before upload", "Draft description");
            send(burner, player, token, image());
            test.assertValueEqual(machines.roms.entries().size(), before, "A completed upload stays an unsaved draft");
            done(burner, player, "", "Still editing");
            test.assertValueEqual(machines.roms.entries().size(), before, "Invalid final metadata cannot save a draft");
            test.assertTrue(sessionField(burner, player.getUUID(), "draft") != null, "Validation failure preserves uploaded bytes");
            done(burner, player, "Final name €", "Final description\nSecond line");
            var entry = machines.roms.entries().getLast(); saved = entry.id();
            test.assertValueEqual(entry.name(), "Final name €", "Done uses the latest name after upload");
            test.assertValueEqual(entry.description(), "Final description\nSecond line", "Done uses the latest multiline description");
            libraryAction(burner, player, "create", ""); clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
            token = burner.prepareUpload(player, "Cancelled draft", "Never save this"); send(burner, player, token, image());
            libraryAction(burner, player, "cancel_create", "");
            test.assertTrue(sessionField(burner, player.getUUID(), "draft") == null, "Cancel discards uploaded draft bytes");
            test.assertValueEqual(machines.roms.entries().size(), before + 1, "Cancel never creates a library entry");
            libraryAction(burner, player, "create", ""); clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
            token = burner.prepareUpload(player, "Closed draft", "Never save this either"); send(burner, player, token, image());
            UUID menu = (UUID) sessionField(burner, player.getUUID(), "menuNonce"); burner.close(player.getUUID(), menu);
            test.assertValueEqual(machines.roms.entries().size(), before + 1, "Closing a draft never saves it");
        } finally {
            burner.disconnect(player.getUUID()); remove(machines, player);
            if (saved != null) machines.roms.delete(saved);
        }
        test.succeed();
    }
    @GameTest public void librarySearchRetainsItsFilterAndResetsPagination(GameTestHelper test) throws Exception {
        var machines = ServerMachines.get(test.getLevel().getServer()); var burner = machines.burner;
        var position = new BlockPos(2, 2, 2); test.setBlock(position, ModContent.BLOCKS.get("rom_burner"));
        var player = player(test, position); var created = new ArrayList<UUID>(); String key = UUID.randomUUID().toString();
        try {
            for (int i = 0; i < 7; i++) created.add(machines.roms.upload(image(), key + " loader " + i, "").id());
            var descriptionMatch = machines.roms.upload(image(), "Description-only ROM", key + " description"); created.add(descriptionMatch.id());
            burner.open(player, test.absolutePos(position)); libraryAction(burner, player, "library", "");
            libraryAction(burner, player, "search", "  " + key + "  ");
            test.assertValueEqual(sessionField(burner, player.getUUID(), "search"), key, "Search is trimmed and stored per player");
            test.assertValueEqual(machines.roms.search(key).size(), 8, "Name and description matches share the result list");
            libraryAction(burner, player, "next", key);
            test.assertValueEqual(sessionField(burner, player.getUUID(), "page"), 1, "Filtered results have a second page");
            libraryAction(burner, player, "back", key); libraryAction(burner, player, "library", key);
            test.assertValueEqual(sessionField(burner, player.getUUID(), "page"), 1, "Returning to the library retains the filtered page");
            test.assertValueEqual(sessionField(burner, player.getUUID(), "search"), key, "Navigation preserves the applied search");
            libraryAction(burner, player, "search", key + " DESCRIPTION");
            test.assertValueEqual(sessionField(burner, player.getUUID(), "page"), 0, "Changing search resets the page");
            test.assertValueEqual(machines.roms.search((String) sessionField(burner, player.getUUID(), "search")),
                    List.of(descriptionMatch), "Full descriptions match without requiring the name to match");
            libraryAction(burner, player, "search", key + " missing"); libraryAction(burner, player, "next", "");
            test.assertValueEqual(sessionField(burner, player.getUUID(), "page"), 0, "Empty results clamp to the first page");
            libraryAction(burner, player, "search", "");
            test.assertValueEqual(sessionField(burner, player.getUUID(), "search"), "", "Clearing search restores the full library");
            libraryAction(burner, player, "search", "x".repeat(129));
            test.assertValueEqual(sessionField(burner, player.getUUID(), "search"), "", "Oversized queries are rejected before changing the filter");
        } finally {
            remove(machines, player);
            for (UUID id : created) machines.roms.delete(id);
        }
        test.succeed();
    }
    @GameTest public void uploadSessionsRequirePlayerContextAndCompleteOrderedData(GameTestHelper test) throws Exception {
        var machines = ServerMachines.get(test.getLevel().getServer()); var clock = new AtomicLong();
        var burner = new RomBurner(machines, clock::get);
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
                test.assertValueEqual(machines.roms.entries().size(), before, "Upload bytes must never enter the library before Done");
            }
            done(burner, player, "First metadata", "Original description");
            var entry = machines.roms.entries().getLast(); uploaded = entry.id();
            test.assertTrue(Arrays.equals(machines.roms.read(uploaded), bytes), "Server must save the exact uploaded ROM");
            test.assertValueEqual(entry.uploaderId(), player.getUUID(), "Uploader UUID comes from the server player");
            test.assertValueEqual(entry.uploaderName(), player.getScoreboardName(), "Uploader name comes from the server player");
            burner.open(other, test.absolutePos(position));
            token = burner.prepareUpload(other, "Replacement metadata", "Must not win"); send(burner, other, token, bytes);
            done(burner, other, "Replacement metadata", "Must not win");
            test.assertValueEqual(machines.roms.entries().size(), before + 1, "Duplicate upload must reuse the existing file");
            test.assertValueEqual(machines.roms.entry(uploaded).orElseThrow().name(), "First metadata", "First ROM name wins");
            test.assertValueEqual(machines.roms.entry(uploaded).orElseThrow().description(), "Original description", "First ROM description wins");
            test.assertValueEqual(machines.roms.entry(uploaded).orElseThrow(), entry, "Duplicate from another player preserves the original uploader");
            expectCooldown(test, burner, other, 30);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
            token = burner.prepareUpload(player, "Bad sequence", "");
            burner.chunk(player, new RomPackets.Chunk(token, 1, new byte[RomUpload.CHUNK_BYTES])); send(burner, player, token, image());
            test.assertValueEqual(machines.roms.entries().size(), before + 1, "Out-of-order transfer must be discarded");
            expectCooldown(test, burner, player, 30);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
            token = burner.prepareUpload(player, "Disconnected", "");
            burner.chunk(player, new RomPackets.Chunk(token, 0, new byte[RomUpload.CHUNK_BYTES]));
            burner.disconnect(player.getUUID()); send(burner, player, token, image());
            test.assertValueEqual(machines.roms.entries().size(), before + 1, "Disconnect must discard a partial upload");
            burner.open(player, test.absolutePos(position)); expectCooldown(test, burner, player, 30);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
            token = burner.prepareUpload(player, "Too far", "");
            player.setPos(player.getX() + 20, player.getY(), player.getZ()); send(burner, player, token, image());
            test.assertValueEqual(machines.roms.entries().size(), before + 1, "Uploads require remaining near the burner");
            var absolute = test.absolutePos(position); player.setPos(absolute.getX() + 0.5, absolute.getY() + 0.5, absolute.getZ() + 0.5);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
            burner.open(player, absolute); token = burner.prepareUpload(player, "Removed burner", "");
            test.setBlock(position, Blocks.AIR); send(burner, player, token, image());
            test.assertValueEqual(machines.roms.entries().size(), before + 1, "Removing the burner must invalidate its upload context");
        } finally {
            burner.disconnect(player.getUUID()); burner.disconnect(other.getUUID());
            remove(machines, player); remove(machines, other);
            if (uploaded != null) machines.roms.delete(uploaded);
        }
        test.succeed();
    }
    private static void expectCooldown(GameTestHelper test, RomBurner burner, ServerPlayer player, int seconds) throws Exception {
        boolean rejected = false;
        try { burner.prepareUpload(player, "Cooldown check", ""); }
        catch (IllegalStateException expected) {
            test.assertValueEqual(expected.getMessage(), "You can upload another ROM in " + seconds + " seconds.", "Cooldown reports rounded-up real seconds");
            rejected = true;
        }
        test.assertTrue(rejected, "Upload must be rejected before cooldown expires");
    }
    @GameTest public void uploadCooldownUsesRealTimeAndSurvivesSessionChanges(GameTestHelper test) throws Exception {
        var machines = ServerMachines.get(test.getLevel().getServer());
        var clock = new AtomicLong(-TimeUnit.SECONDS.toNanos(1)); var burner = new RomBurner(machines, clock::get);
        var position = new BlockPos(2, 2, 2); var second = new BlockPos(3, 2, 2);
        test.setBlock(position, ModContent.BLOCKS.get("rom_burner")); test.setBlock(second, ModContent.BLOCKS.get("rom_burner"));
        var player = player(test, position); var other = player(test, position);
        try {
            burner.open(player, test.absolutePos(position));
            boolean invalid = false;
            try { burner.prepareUpload(player, "", ""); } catch (java.io.IOException expected) { invalid = true; }
            test.assertTrue(invalid, "Invalid metadata is rejected before consuming a slot");
            UUID token = burner.prepareUpload(player, "Cancelled", "");
            burner.cancel(player, token, "Cancelled"); expectCooldown(test, burner, player, 30);
            burner.open(player, test.absolutePos(second)); expectCooldown(test, burner, player, 30);
            burner.disconnect(player.getUUID()); burner.open(player, test.absolutePos(position));
            expectCooldown(test, burner, player, 30);
            burner.open(other, test.absolutePos(position));
            UUID otherToken = burner.prepareUpload(other, "Independent player", "");
            burner.cancel(other, otherToken, "Cancelled");
            clock.addAndGet(TimeUnit.SECONDS.toNanos(29)); expectCooldown(test, burner, player, 1);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1) - 1); expectCooldown(test, burner, player, 1);
            clock.incrementAndGet(); token = burner.prepareUpload(player, "At thirty seconds", "");
            burner.cancel(player, token, "Cancelled"); expectCooldown(test, burner, player, 30);
        } finally {
            burner.disconnect(player.getUUID()); burner.disconnect(other.getUUID());
            remove(machines, player); remove(machines, other);
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

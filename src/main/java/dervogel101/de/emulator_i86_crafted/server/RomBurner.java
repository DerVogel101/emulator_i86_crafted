package dervogel101.de.emulator_i86_crafted.server;

import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.network.RomPackets;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import dervogel101.de.emulator_i86_crafted.network.ControlPanel;
import dervogel101.de.emulator_i86_crafted.network.PanelPackets;
import net.minecraft.server.level.*;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.io.IOException;
import java.util.*;

/** Player-owned menus/uploads; chips are burned from the player's inventory on the server thread. */
public final class RomBurner {
    private static final int PAGE_SIZE = 6;
    private static final class Session {
        final ServerLevel level;
        final BlockPos position;
        UUID menuNonce, selected, uploadToken;
        RomUpload upload;
        int page;
        long activeUntil;
        String name = "ROM image", description = "";
        Session(ServerLevel level, BlockPos position) { this.level = level; this.position = position.immutable(); }
    }
    private final ServerMachines owner;
    private final Map<UUID, Session> sessions = new HashMap<>();
    public RomBurner(ServerMachines owner) { this.owner = owner; }
    public void disconnect(UUID player) {
        var old = sessions.remove(player);
        if (old != null) refresh(old.level, old.position);
    }
    public void open(ServerPlayer player, BlockPos position) {
        var session = new Session(player.level(), position);
        if (!usable(player, session)) return;
        var old = sessions.put(player.getUUID(), session);
        if (old != null) refresh(old.level, old.position);
        main(player, session, "");
    }
    private static boolean usable(ServerPlayer player, Session session) {
        return !player.isRemoved() && !player.isSpectator() && player.level() == session.level
                && player.distanceToSqr(Vec3.atCenterOf(session.position)) <= 64 && session.level.hasChunkAt(session.position)
                && session.level.getBlockState(session.position).is(ModContent.BLOCKS.get("rom_burner"))
                && session.level.mayInteract(player, session.position);
    }
    public void submit(ServerPlayer player, Tag payload) {
        if (!(payload instanceof CompoundTag tag)) return;
        var session = sessions.get(player.getUUID());
        if (session == null || session.menuNonce == null || !session.menuNonce.toString().equals(tag.getStringOr("token", ""))) return;
        session.menuNonce = null;
        if (!usable(player, session)) { unavailable(player); return; }
        try {
            switch (tag.getStringOr("action", "")) {
                case "close" -> { disconnect(player.getUUID());  }
                case "library" -> library(player, session);
                case "previous" -> { session.page--; library(player, session); }
                case "next" -> { session.page++; library(player, session); }
                case "back" -> main(player, session, "");
                case "select" -> {
                    String id = tag.getStringOr("rom", "");
                    if (id.length() != 36) throw new IllegalArgumentException("Invalid ROM ID");
                    var entry = owner.roms.entry(UUID.fromString(id)).orElseThrow(() -> new IllegalArgumentException("ROM no longer exists"));
                    session.selected = entry.id(); main(player, session, "");
                }
                case "burn" -> { burn(player, session.selected); busy(session); main(player, session, "ROM chip burned. Its reference is permanent."); }
                case "upload" -> {
                    if (!ServerPlayNetworking.canSend(player, RomPackets.ChooseFile.TYPE)) throw new IllegalStateException("Client ROM upload channel is unavailable");
                    UUID token = prepareUpload(player, tag.getStringOr("name", ""), tag.getStringOr("description", ""));
                    ServerPlayNetworking.send(player, new RomPackets.ChooseFile(token));
                }
                default -> main(player, session, "Unknown burner action");
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException invalid) {
            finishUpload(session);
            main(player, session, invalid.getMessage());
        }
    }
    public UUID prepareUpload(ServerPlayer player, String name, String description) throws IOException {
        var session = sessions.get(player.getUUID());
        if (session == null || !usable(player, session)) throw new IllegalStateException("Open a nearby ROM burner before uploading");
        RomLibrary.validateMetadata(name, description);
        session.name = name; session.description = description; session.menuNonce = null;
        session.uploadToken = UUID.randomUUID(); session.upload = new RomUpload();
        refresh(session.level, session.position);
        return session.uploadToken;
    }
    public void chunk(ServerPlayer player, RomPackets.Chunk packet) {
        var session = sessions.get(player.getUUID());
        if (session == null || session.upload == null || !packet.token().equals(session.uploadToken)) return;
        if (!usable(player, session)) { unavailable(player); return; }
        try {
            if (!session.upload.append(packet.index(), packet.bytes())) return;
            byte[] image = session.upload.image(); finishUpload(session);
            var entry = owner.roms.upload(image, session.name, session.description);
            session.selected = entry.id(); main(player, session, "Uploaded " + entry.name());
        } catch (RomLibrary.DuplicateRom duplicate) {
            finishUpload(session); session.selected = duplicate.existing.id();
            main(player, session, "Duplicate upload rejected. Selected the existing ROM; its original name and description are retained.");
        } catch (IOException | IllegalArgumentException invalid) {
            finishUpload(session); main(player, session, invalid.getMessage());
        }
    }
    public void cancel(ServerPlayer player, UUID token, String message) {
        var session = sessions.get(player.getUUID());
        if (session == null || !token.equals(session.uploadToken)) return;
        finishUpload(session);
        if (usable(player, session)) main(player, session, message); else unavailable(player);
    }
    public void burn(ServerPlayer player, UUID id) throws IOException {
        if (id == null) throw new IllegalArgumentException("Select a ROM from the library first");
        var entry = owner.roms.entry(id).orElseThrow(() -> new IllegalArgumentException("Unknown ROM ID"));
        if (!owner.roms.available(id)) throw new IOException("The selected ROM file is missing or invalid");
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var blank = inventory.getItem(slot);
            if (!blank.is(ModContent.ITEMS.get("rom_chip")) || blank.has(ModComponents.ROM_ID)) continue;
            var burned = RomChips.create(entry, false);
            if (blank.getCount() == 1) inventory.setItem(slot, burned);
            else {
                blank.shrink(1);
                if (!inventory.add(burned)) player.drop(burned, false, Prediction.SERVER_ONLY);
            }
            inventory.setChanged(); player.containerMenu.broadcastChanges(); return;
        }
        throw new IllegalStateException("A blank ROM chip is required in your inventory. Burned chips cannot be rewritten.");
    }
    private void main(ServerPlayer player, Session session, String notice) {
        notice = Objects.toString(notice, "ROM operation failed");
        if (!usable(player, session)) { unavailable(player); return; }
        session.menuNonce = UUID.randomUUID();
        var selected = session.selected == null ? null : owner.roms.entry(session.selected).orElse(null);
        String text = notice.isEmpty() ? "" : notice + "\n\n";
        text += selected == null ? "No ROM selected." : selected.name() + "\n" + selected.id() + "\n" + selected.description()
                + (owner.roms.available(selected.id()) ? "" : "\nMissing or invalid ROM file.");
        text += "\n\nBurn uses one blank ROM chip from your inventory. Existing chips keep their reference permanently.";
        var inputs = List.of(ControlPanel.text("name", "Upload name", session.name, 128),
                ControlPanel.text("description", "Upload description", session.description, 4096));
        var buttons = new ArrayList<ControlPanel.Action>();
        buttons.add(button(session, "Browse ROM library", "library", null));
        buttons.add(button(session, "Choose local file and upload", "upload", null));
        if (selected != null) buttons.add(button(session, "Burn blank chip", "burn", null));
        show(player, session, "ROM burner", text, inputs, buttons);
    }
    private void library(ServerPlayer player, Session session) {
        session.menuNonce = UUID.randomUUID();
        var entries = owner.roms.entries(); int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        session.page = Math.max(0, Math.min(session.page, pages - 1));
        var buttons = new ArrayList<ControlPanel.Action>();
        var text = new StringBuilder("Page " + (session.page + 1) + " / " + pages + " — " + entries.size() + " saved ROMs");
        int start = session.page * PAGE_SIZE;
        for (int i = start; i < Math.min(entries.size(), start + PAGE_SIZE); i++) {
            var entry = entries.get(i); int number = i + 1;
            text.append("\n\n").append(number).append(". ").append(entry.name()).append("\n").append(entry.id());
            String description = entry.description(); text.append("\n").append(description.substring(0, Math.min(160, description.length())));
            if (!owner.roms.available(entry.id())) text.append("\nMissing or invalid file");
            buttons.add(button(session, "Select " + number + ": " + entry.name(), "select", entry.id()));
        }
        if (session.page > 0) buttons.add(button(session, "Previous page", "previous", null));
        if (session.page + 1 < pages) buttons.add(button(session, "Next page", "next", null));
        buttons.add(button(session, "Back to burner", "back", null));
        show(player, session, "ROM library", text.toString(), List.of(), buttons);
    }
    private static ControlPanel.Action button(Session session, String label, String action, UUID rom) {
        return new ControlPanel.Action(label, action, rom == null ? Map.of() : Map.of("rom", rom.toString()));
    }
    private static void show(ServerPlayer player, Session session, String title, String text, List<ControlPanel.Field> inputs, List<ControlPanel.Action> buttons) {
        PanelPackets.open(player, new ControlPanel(ControlPanel.Kind.BURNER, session.menuNonce, "rom_burner", title, text, inputs, buttons));
    }
    public void close(UUID player, UUID token) {
        var session = sessions.get(player);
        if (session != null && token.equals(session.menuNonce)) disconnect(player);
    }
    private void busy(Session session) {
        session.activeUntil = owner.records.gameTicks + 20; refresh(session.level, session.position);
    }
    private void finishUpload(Session session) {
        session.upload = null; session.uploadToken = null; busy(session);
    }
    public void refresh(ServerLevel level, BlockPos position) {
        if (!level.hasChunkAt(position)) return;
        var state = level.getBlockState(position);
        if (!state.is(ModContent.BLOCKS.get("rom_burner"))) return;
        boolean active = sessions.values().stream().anyMatch(session -> session.level == level && session.position.equals(position)
                && (session.upload != null || session.activeUntil > owner.records.gameTicks));
        if (state.getValue(dervogel101.de.emulator_i86_crafted.block.MachineBlock.POWERED) != active)
            level.setBlock(position, state.setValue(dervogel101.de.emulator_i86_crafted.block.MachineBlock.POWERED, active), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        if (active) level.scheduleTick(position, state.getBlock(), 20);
    }
    private void unavailable(ServerPlayer player) {
        disconnect(player.getUUID());
        PanelPackets.open(player, new ControlPanel(ControlPanel.Kind.BURNER, UUID.randomUUID(), "rom_burner", "ROM burner",
                "The burner is no longer available. Return to it to continue.", List.of(), List.of()));
    }
}

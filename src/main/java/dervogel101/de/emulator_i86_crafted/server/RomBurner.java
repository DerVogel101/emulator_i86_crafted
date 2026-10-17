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
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Player-owned menus/uploads; chips are burned from the player's inventory on the server thread. */
public final class RomBurner {
    private static final int PAGE_SIZE = 6;
    private static final long UPLOAD_INTERVAL = TimeUnit.SECONDS.toNanos(30);
    private static final class Session {
        final ServerLevel level;
        final BlockPos position;
        UUID menuNonce, selected, uploadToken;
        RomUpload upload;
        byte[] draft;
        boolean creating;
        int page;
        long activeUntil;
        String name = "ROM image", description = "", search = "";
        Session(ServerLevel level, BlockPos position) { this.level = level; this.position = position.immutable(); }
    }
    private final ServerMachines owner;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> uploadStarts = new HashMap<>();
    private final LongSupplier nanoTime;
    public void indicators() {
        for (var session : sessions.values()) if (session.level.hasChunkAt(session.position)
                && session.level.getBlockState(session.position).is(ModContent.BLOCKS.get("rom_burner")))
            owner.indicators.set(session.level, session.position, session.upload != null || session.activeUntil > owner.records.gameTicks ? 1 : 2, 0);
    }
    public RomBurner(ServerMachines owner) { this(owner, System::nanoTime); }
    public RomBurner(ServerMachines owner, LongSupplier nanoTime) { this.owner = owner; this.nanoTime = nanoTime; }
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
                case "search" -> {
                    String query = tag.getStringOr("search", "");
                    if (query.length() > 128) throw new IllegalArgumentException("Search allows up to 128 characters");
                    session.search = query.strip(); session.page = 0; library(player, session);
                }
                case "previous" -> { session.page--; library(player, session); }
                case "next" -> { session.page++; library(player, session); }
                case "back" -> main(player, session, "");
                case "create" -> {
                    session.creating = true; session.draft = null; session.name = "ROM image"; session.description = "";
                    creation(player, session, "");
                }
                case "cancel_create" -> {
                    session.creating = false; session.draft = null; finishUpload(session); main(player, session, "");
                }
                case "done" -> {
                    if (!session.creating) throw new IllegalStateException("Open Create ROM first.");
                    metadata(session, tag);
                    if (session.draft == null) throw new IllegalStateException("Upload a ROM file before choosing Done.");
                    var entry = owner.roms.upload(session.draft, session.name, session.description, player.getUUID(), player.getScoreboardName());
                    session.selected = entry.id(); session.draft = null; session.creating = false;
                    owner.indicators.failure(session.level, session.position, false);
                    owner.indicators.pulse(session.level, session.position, 2);
                    main(player, session, "Created " + entry.name());
                }
                case "select" -> {
                    String id = tag.getStringOr("rom", "");
                    if (id.length() != 36) throw new IllegalArgumentException("Invalid ROM ID");
                    var entry = owner.roms.entry(UUID.fromString(id)).orElseThrow(() -> new IllegalArgumentException("ROM no longer exists"));
                    session.selected = entry.id(); main(player, session, "");
                }
                case "burn" -> { burn(player, session.selected); busy(session); main(player, session, "ROM chip burned. Its reference is permanent."); }
                case "upload" -> {
                    if (!session.creating) throw new IllegalStateException("Open Create ROM before uploading.");
                    metadata(session, tag);
                    if (!ServerPlayNetworking.canSend(player, RomPackets.ChooseFile.TYPE)) throw new IllegalStateException("Client ROM upload channel is unavailable");
                    UUID token = prepareUpload(player, session.name, session.description);
                    ServerPlayNetworking.send(player, new RomPackets.ChooseFile(token));
                }
                default -> main(player, session, "Unknown burner action");
            }
        } catch (RomLibrary.DuplicateRom duplicate) {
            session.selected = duplicate.existing.id(); session.draft = null; session.creating = false;
            main(player, session, "Duplicate upload rejected. Selected the existing ROM; its original name, description and uploader are retained.");
        } catch (IOException | IllegalArgumentException | IllegalStateException invalid) {
            owner.indicators.failure(session.level, session.position, true);
            finishUpload(session);
            if (session.creating) creation(player, session, invalid.getMessage()); else main(player, session, invalid.getMessage());
        }
    }
    private static void metadata(Session session, CompoundTag tag) throws IOException {
        String name = tag.getStringOr("name", ""), description = tag.getStringOr("description", "");
        if (name.length() > 128 || description.length() > 4096) throw new IOException("ROM name or description is too long");
        session.name = name; session.description = description;
    }
    public UUID prepareUpload(ServerPlayer player, String name, String description) throws IOException {
        var session = sessions.get(player.getUUID());
        if (session == null || !usable(player, session)) throw new IllegalStateException("Open a nearby ROM burner before uploading");
        RomLibrary.validateMetadata(name, description);
        long now = nanoTime.getAsLong();
        uploadStarts.values().removeIf(start -> now - start >= UPLOAD_INTERVAL);
        Long previous = uploadStarts.get(player.getUUID());
        if (previous != null) {
            long seconds = Math.ceilDiv(UPLOAD_INTERVAL - (now - previous), TimeUnit.SECONDS.toNanos(1));
            throw new IllegalStateException("You can upload another ROM in " + seconds + " seconds.");
        }
        uploadStarts.put(player.getUUID(), now);
        session.name = name; session.description = description; session.menuNonce = null;
        session.creating = true;
        session.uploadToken = UUID.randomUUID(); session.upload = new RomUpload();
        owner.indicators.failure(session.level, session.position, false);
        refresh(session.level, session.position);
        return session.uploadToken;
    }
    public void chunk(ServerPlayer player, RomPackets.Chunk packet) {
        var session = sessions.get(player.getUUID());
        if (session == null || session.upload == null || !packet.token().equals(session.uploadToken)) return;
        if (!usable(player, session)) { unavailable(player); return; }
        try {
            owner.indicators.pulse(session.level, session.position, 1);
            if (!session.upload.append(packet.index(), packet.bytes())) return;
            owner.indicators.pulse(session.level, session.position, 2);
            session.draft = session.upload.image(); finishUpload(session);
            creation(player, session, "ROM file uploaded (256 KiB). Choose Done to save it.");
        } catch (IllegalArgumentException invalid) {
            owner.indicators.failure(session.level, session.position, true);
            finishUpload(session); creation(player, session, invalid.getMessage());
        }
    }
    public void cancel(ServerPlayer player, UUID token, String message) {
        var session = sessions.get(player.getUUID());
        if (session == null || !token.equals(session.uploadToken)) return;
        finishUpload(session);
        if (usable(player, session)) creation(player, session, message); else unavailable(player);
    }
    public void burn(ServerPlayer player, UUID id) throws IOException {
        if (id == null) throw new IllegalArgumentException("Select a ROM from the library first");
        var entry = owner.roms.entry(id).orElseThrow(() -> new IllegalArgumentException("Unknown ROM ID"));
        var operationSession = sessions.get(player.getUUID());
        if (operationSession != null) owner.indicators.pulse(operationSession.level, operationSession.position, 2);
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
            inventory.setChanged(); player.containerMenu.broadcastChanges();
            var session = sessions.get(player.getUUID());
            if (session != null) {
                owner.indicators.failure(session.level, session.position, false);
                owner.indicators.pulse(session.level, session.position, 4);
            }
            return;
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
                + "\nUploaded by: " + selected.uploaderName()
                + (owner.roms.available(selected.id()) ? "" : "\nMissing or invalid ROM file.");
        text += "\n\nBurn uses one blank ROM chip from your inventory. Existing chips keep their reference permanently.";
        var buttons = new ArrayList<ControlPanel.Action>();
        buttons.add(button(session, "Browse ROM library", "library", null));
        buttons.add(button(session, "Create ROM", "create", null));
        if (selected != null) buttons.add(button(session, "Burn blank chip", "burn", null));
        show(player, session, "ROM burner", text, List.of(), buttons);
    }
    private void creation(ServerPlayer player, Session session, String notice) {
        session.menuNonce = UUID.randomUUID();
        String text = (notice.isEmpty() ? "" : notice + "\n\n")
                + (session.draft == null ? "No file uploaded." : "ROM file ready (256 KiB).")
                + "\nUpload a local ROM, enter its name and description, then choose Done. Cancel discards this draft.";
        show(player, session, "Create ROM", text,
                List.of(ControlPanel.text("name", "Name", session.name, 128), ControlPanel.text("description", "Description", session.description, 4096)),
                List.of(button(session, "Upload", "upload", null), button(session, "Cancel", "cancel_create", null), button(session, "Done", "done", null)));
    }
    private void library(ServerPlayer player, Session session) {
        session.menuNonce = UUID.randomUUID();
        var entries = owner.roms.search(session.search); int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        session.page = Math.max(0, Math.min(session.page, pages - 1));
        var buttons = new ArrayList<ControlPanel.Action>();
        var text = new StringBuilder("Page " + (session.page + 1) + " / " + pages + " — " + entries.size() + " saved ROMs");
        if (!session.search.isEmpty()) text.append(" matching \"").append(session.search).append('"');
        if (entries.isEmpty()) text.append("\n\nNo ROMs found.");
        buttons.add(button(session, "Search", "search", null));
        int start = session.page * PAGE_SIZE;
        for (int i = start; i < Math.min(entries.size(), start + PAGE_SIZE); i++) {
            var entry = entries.get(i); int number = i + 1;
            text.append("\n\n").append(number).append(". ").append(entry.name()).append("\n").append(entry.id());
            text.append("\nUploaded by: ").append(entry.uploaderName());
            String description = entry.description(); text.append("\n").append(description.substring(0, Math.min(160, description.length())));
            if (!owner.roms.available(entry.id())) text.append("\nMissing or invalid file");
            buttons.add(button(session, "Select " + number + ": " + entry.name(), "select", entry.id()));
        }
        if (session.page > 0) buttons.add(button(session, "Previous page", "previous", null));
        if (session.page + 1 < pages) buttons.add(button(session, "Next page", "next", null));
        buttons.add(button(session, "Back to burner", "back", null));
        show(player, session, "ROM library", text.toString(),
                List.of(ControlPanel.text("search", "Search name or description", session.search, 128)), buttons);
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

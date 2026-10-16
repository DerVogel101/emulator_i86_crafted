package dervogel101.de.emulator_i86_crafted.network;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import dervogel101.de.emulator_i86_crafted.server.storage.RomUpload;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

public final class RomPackets {
    private RomPackets() {}
    public record ChooseFile(UUID token) implements CustomPacketPayload {
        public static final Type<ChooseFile> TYPE = new Type<>(ModContent.id("rom_choose_file"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChooseFile> CODEC = StreamCodec.ofMember(ChooseFile::write, ChooseFile::new);
        private ChooseFile(RegistryFriendlyByteBuf input) { this(input.readUUID()); }
        private void write(RegistryFriendlyByteBuf output) { output.writeUUID(token); }
        @Override public Type<ChooseFile> type() { return TYPE; }
    }
    public record Chunk(UUID token, int index, byte[] bytes) implements CustomPacketPayload {
        public static final Type<Chunk> TYPE = new Type<>(ModContent.id("rom_upload_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Chunk> CODEC = StreamCodec.ofMember(Chunk::write, Chunk::new);
        public Chunk { if (bytes.length > RomUpload.CHUNK_BYTES) throw new IllegalArgumentException("Oversized ROM chunk"); bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        private Chunk(RegistryFriendlyByteBuf input) { this(input.readUUID(), input.readVarInt(), input.readByteArray(RomUpload.CHUNK_BYTES)); }
        private void write(RegistryFriendlyByteBuf output) { output.writeUUID(token); output.writeVarInt(index); output.writeByteArray(bytes); }
        @Override public Type<Chunk> type() { return TYPE; }
    }
    public record Cancel(UUID token, String message) implements CustomPacketPayload {
        public static final Type<Cancel> TYPE = new Type<>(ModContent.id("rom_upload_cancel"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Cancel> CODEC = StreamCodec.ofMember(Cancel::write, Cancel::new);
        public Cancel { if (message.length() > 160) throw new IllegalArgumentException("Oversized upload cancellation"); }
        private Cancel(RegistryFriendlyByteBuf input) { this(input.readUUID(), input.readUtf(160)); }
        private void write(RegistryFriendlyByteBuf output) { output.writeUUID(token); output.writeUtf(message, 160); }
        @Override public Type<Cancel> type() { return TYPE; }
    }
    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(ChooseFile.TYPE, ChooseFile.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Chunk.TYPE, Chunk.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Cancel.TYPE, Cancel.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Chunk.TYPE, (packet, context) ->
                ServerMachines.get(context.server()).burner.chunk(context.player(), packet));
        ServerPlayNetworking.registerGlobalReceiver(Cancel.TYPE, (packet, context) ->
                ServerMachines.get(context.server()).burner.cancel(context.player(), packet.token(), packet.message()));
    }
}

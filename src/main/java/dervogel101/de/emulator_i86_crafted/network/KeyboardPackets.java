package dervogel101.de.emulator_i86_crafted.network;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

public final class KeyboardPackets {
    private KeyboardPackets() {}
    public record Capture(UUID token, boolean active) implements CustomPacketPayload {
        public static final Type<Capture> TYPE = new Type<>(ModContent.id("keyboard_capture"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Capture> CODEC = StreamCodec.ofMember(
                (p, out) -> { out.writeUUID(p.token); out.writeBoolean(p.active); },
                in -> new Capture(in.readUUID(), in.readBoolean()));
        @Override public Type<Capture> type() { return TYPE; }
    }
    public record Overflow(UUID token) implements CustomPacketPayload {
        public static final Type<Overflow> TYPE = new Type<>(ModContent.id("keyboard_full"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Overflow> CODEC = StreamCodec.ofMember(
                (p, out) -> out.writeUUID(p.token), in -> new Overflow(in.readUUID()));
        @Override public Type<Overflow> type() { return TYPE; }
    }
    /** Zero releases capture; every other value must be an allowed keyboard byte. */
    public record Key(UUID token, int value) implements CustomPacketPayload {
        public static final Type<Key> TYPE = new Type<>(ModContent.id("keyboard_key"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Key> CODEC = StreamCodec.ofMember(
                (p, out) -> { out.writeUUID(p.token); out.writeByte(p.value); },
                in -> new Key(in.readUUID(), in.readUnsignedByte()));
        @Override public Type<Key> type() { return TYPE; }
    }
    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(Capture.TYPE, Capture.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Overflow.TYPE, Overflow.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Key.TYPE, Key.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Key.TYPE, (packet, context) ->
                context.server().execute(() -> ServerMachines.get(context.server()).keyboards.input(context.player(), packet)));
    }
}

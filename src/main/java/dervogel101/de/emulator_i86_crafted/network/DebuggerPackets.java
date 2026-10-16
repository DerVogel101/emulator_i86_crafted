package dervogel101.de.emulator_i86_crafted.network;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import dervogel101.de.launcher.DebuggerProtocol;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.UUID;

public final class DebuggerPackets {
    public static final int MAX_REQUEST = 4096;
    private DebuggerPackets() {}
    public record Open(UUID session) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ModContent.id("debugger_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.ofMember(Open::write, Open::new);
        private Open(RegistryFriendlyByteBuf in) { this(in.readUUID()); }
        private void write(RegistryFriendlyByteBuf out) { out.writeUUID(session); }
        @Override public Type<Open> type() { return TYPE; }
    }
    public record State(UUID session, byte[] bytes) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(ModContent.id("debugger_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.ofMember(State::write, State::new);
        public State { if (bytes.length > DebuggerProtocol.MAX_FRAME) throw new IllegalArgumentException("Oversized debugger state"); bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        private State(RegistryFriendlyByteBuf in) { this(in.readUUID(), in.readByteArray(DebuggerProtocol.MAX_FRAME)); }
        private void write(RegistryFriendlyByteBuf out) { out.writeUUID(session); out.writeByteArray(bytes); }
        @Override public Type<State> type() { return TYPE; }
    }
    public record Request(UUID session, byte[] bytes) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ModContent.id("debugger_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.ofMember(Request::write, Request::new);
        public Request { if (bytes.length > MAX_REQUEST) throw new IllegalArgumentException("Oversized debugger request"); bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        private Request(RegistryFriendlyByteBuf in) { this(in.readUUID(), in.readByteArray(MAX_REQUEST)); }
        private void write(RegistryFriendlyByteBuf out) { out.writeUUID(session); out.writeByteArray(bytes); }
        @Override public Type<Request> type() { return TYPE; }
    }
    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(Open.TYPE, Open.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(State.TYPE, State.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (packet, context) ->
                ServerMachines.get(context.server()).debugger.request(context.player(), packet.session(), packet.bytes()));
    }
}

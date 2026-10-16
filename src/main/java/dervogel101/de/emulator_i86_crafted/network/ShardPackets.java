package dervogel101.de.emulator_i86_crafted.network;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import dervogel101.de.i86.runtime.ShardSpeed;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.List;

public final class ShardPackets {
    private ShardPackets() {}

    public record Settings(List<ShardSpeed> tiers) implements CustomPacketPayload {
        public static final Type<Settings> TYPE = new Type<>(ModContent.id("shard_settings"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Settings> CODEC = StreamCodec.ofMember(Settings::write, Settings::new);
        public Settings {
            tiers = List.copyOf(tiers);
            if (tiers.size() != 3) throw new IllegalArgumentException("Three shard tiers required");
        }
        private Settings(RegistryFriendlyByteBuf in) { this(List.of(readSpeed(in), readSpeed(in), readSpeed(in))); }
        private static ShardSpeed readSpeed(RegistryFriendlyByteBuf in) { return new ShardSpeed(in.readVarInt(), in.readVarInt()); }
        private void write(RegistryFriendlyByteBuf out) {
            for (var speed : tiers) { out.writeVarInt(speed.stepsPerTick()); out.writeVarInt(speed.batchesPerTick()); }
        }
        @Override public Type<Settings> type() { return TYPE; }
    }

    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(Settings.TYPE, Settings.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var machines = ServerMachines.get(server);
            ServerPlayNetworking.send(handler.getPlayer(), new Settings(List.of(
                    machines.shardSpeed(1), machines.shardSpeed(2), machines.shardSpeed(3))));
        });
    }
}

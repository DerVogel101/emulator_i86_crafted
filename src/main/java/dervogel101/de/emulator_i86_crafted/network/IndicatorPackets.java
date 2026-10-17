package dervogel101.de.emulator_i86_crafted.network;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.i86.runtime.IndicatorState;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.util.ArrayList;
import java.util.List;

public final class IndicatorPackets {
    public static final int LIMIT = 2048;
    private IndicatorPackets() {}
    public record Entry(BlockPos position, int flags) {
        public Entry {
            position = position.immutable();
            if (flags < 0 || flags > 31 && flags != IndicatorState.REMOVE) throw new IllegalArgumentException("Invalid LED flags");
        }
    }
    public record Update(String dimension, List<Entry> entries) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(ModContent.id("indicators"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = StreamCodec.ofMember(Update::write, Update::new);
        public Update {
            if (dimension.length() > 256 || entries.size() > LIMIT) throw new IllegalArgumentException("LED batch too large");
            entries = List.copyOf(entries);
        }
        private Update(RegistryFriendlyByteBuf in) { this(in.readUtf(256), readEntries(in)); }
        private static List<Entry> readEntries(RegistryFriendlyByteBuf in) {
            int count = in.readVarInt();
            if (count < 0 || count > LIMIT) throw new IllegalArgumentException("LED batch too large");
            var entries = new ArrayList<Entry>(count);
            for (int i = 0; i < count; i++) entries.add(new Entry(in.readBlockPos(), in.readUnsignedByte()));
            return entries;
        }
        private void write(RegistryFriendlyByteBuf out) {
            out.writeUtf(dimension, 256); out.writeVarInt(entries.size());
            for (var entry : entries) { out.writeBlockPos(entry.position()); out.writeByte(entry.flags()); }
        }
        @Override public Type<Update> type() { return TYPE; }
    }
    public static void initialize() { PayloadTypeRegistry.clientboundPlay().register(Update.TYPE, Update.CODEC); }
}

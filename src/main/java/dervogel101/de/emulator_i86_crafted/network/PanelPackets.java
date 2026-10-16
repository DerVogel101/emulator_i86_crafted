package dervogel101.de.emulator_i86_crafted.network;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.server.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

public final class PanelPackets {
    private PanelPackets() {}
    public record Open(ControlPanel panel) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ModContent.id("panel_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.ofMember(Open::write, Open::read);
        private static Open read(RegistryFriendlyByteBuf in) {
            var kind = in.readEnum(ControlPanel.Kind.class); var token = in.readUUID();
            String icon = in.readUtf(64), title = in.readUtf(128), body = in.readUtf(24576);
            var fields = new ArrayList<ControlPanel.Field>();
            for (int i = count(in, 16); i > 0; i--) {
                String key = in.readUtf(32), label = in.readUtf(128), value = in.readUtf(4096); int maximum = in.readVarInt();
                var options = new ArrayList<String>();
                for (int j = count(in, 128); j > 0; j--) options.add(in.readUtf(4096));
                fields.add(new ControlPanel.Field(key, label, value, maximum, options));
            }
            var actions = new ArrayList<ControlPanel.Action>();
            for (int i = count(in, 32); i > 0; i--) actions.add(new ControlPanel.Action(in.readUtf(256), in.readUtf(32), readValues(in)));
            return new Open(new ControlPanel(kind, token, icon, title, body, fields, actions));
        }
        private void write(RegistryFriendlyByteBuf out) {
            out.writeEnum(panel.kind()); out.writeUUID(panel.token()); out.writeUtf(panel.icon(), 64); out.writeUtf(panel.title(), 128); out.writeUtf(panel.body(), 24576);
            out.writeVarInt(panel.fields().size());
            for (var field : panel.fields()) {
                out.writeUtf(field.key(), 32); out.writeUtf(field.label(), 128); out.writeUtf(field.value(), 4096); out.writeVarInt(field.maximum()); out.writeVarInt(field.options().size());
                for (var option : field.options()) out.writeUtf(option, 4096);
            }
            out.writeVarInt(panel.actions().size());
            for (var action : panel.actions()) { out.writeUtf(action.label(), 256); out.writeUtf(action.action(), 32); writeValues(out, action.values()); }
        }
        @Override public Type<Open> type() { return TYPE; }
    }
    /** Updates an existing screen only; never opens a closed or unrelated screen. */
    public record Update(ControlPanel panel) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(ModContent.id("panel_update"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC = Open.CODEC.map(
                open -> new Update(open.panel()), update -> new Open(update.panel()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Submit(ControlPanel.Kind kind, UUID token, String action, Map<String, String> values) implements CustomPacketPayload {
        public static final Type<Submit> TYPE = new Type<>(ModContent.id("panel_submit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Submit> CODEC = StreamCodec.ofMember(Submit::write, Submit::new);
        public Submit { Objects.requireNonNull(kind); Objects.requireNonNull(token); ControlPanel.bounded(action, 32); values = ControlPanel.values(values); }
        private Submit(RegistryFriendlyByteBuf in) { this(in.readEnum(ControlPanel.Kind.class), in.readUUID(), in.readUtf(32), readValues(in)); }
        private void write(RegistryFriendlyByteBuf out) { out.writeEnum(kind); out.writeUUID(token); out.writeUtf(action, 32); writeValues(out, values); }
        @Override public Type<Submit> type() { return TYPE; }
        public CompoundTag tag() {
            var tag = new CompoundTag(); values.forEach(tag::putString); tag.putString("token", token.toString()); tag.putString("action", action); return tag;
        }
    }
    private static int count(RegistryFriendlyByteBuf in, int maximum) {
        int count = in.readVarInt();
        if (count < 0 || count > maximum) throw new IllegalArgumentException("Panel collection exceeds limit");
        return count;
    }
    private static Map<String, String> readValues(RegistryFriendlyByteBuf in) {
        var values = new LinkedHashMap<String, String>();
        for (int i = count(in, 16); i > 0; i--) if (values.put(in.readUtf(32), in.readUtf(4096)) != null) throw new IllegalArgumentException("Duplicate panel value");
        return ControlPanel.values(values);
    }
    private static void writeValues(RegistryFriendlyByteBuf out, Map<String, String> values) {
        out.writeVarInt(values.size()); values.forEach((key, value) -> { out.writeUtf(key, 32); out.writeUtf(value, 4096); });
    }
    public static void open(ServerPlayer player, ControlPanel panel) { ServerPlayNetworking.send(player, new Open(panel)); }
    public static void update(ServerPlayer player, ControlPanel panel) { ServerPlayNetworking.send(player, new Update(panel)); }
    public static void initialize() {
        PayloadTypeRegistry.clientboundPlay().register(Open.TYPE, Open.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Update.TYPE, Update.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Submit.TYPE, Submit.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Submit.TYPE, (packet, context) -> {
            var player = context.player();
            if (packet.action().equals("__close")) {
                switch (packet.kind()) {
                    case MACHINE -> MachineDialogs.close(player.getUUID(), packet.token());
                    case PORT -> PortDialogs.close(player.getUUID(), packet.token());
                    case BURNER -> ServerMachines.get(context.server()).burner.close(player.getUUID(), packet.token());
                }
            } else switch (packet.kind()) {
                case MACHINE -> MachineDialogs.submit(player, packet.tag());
                case PORT -> PortDialogs.submit(player, packet.tag());
                case BURNER -> ServerMachines.get(context.server()).burner.submit(player, packet.tag());
            }
        });
    }
}

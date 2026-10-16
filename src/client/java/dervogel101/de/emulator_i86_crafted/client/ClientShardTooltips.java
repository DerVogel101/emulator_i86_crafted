package dervogel101.de.emulator_i86_crafted.client;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.network.ShardPackets;
import dervogel101.de.i86.runtime.ShardSpeed;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import java.util.List;

public final class ClientShardTooltips {
    private static final List<ShardSpeed> DEFAULTS = List.of(ShardSpeed.TIER_1, ShardSpeed.TIER_2, ShardSpeed.TIER_3);
    private static List<ShardSpeed> speeds = DEFAULTS;
    private ClientShardTooltips() {}

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(ShardPackets.Settings.TYPE, (packet, context) -> speeds = packet.tiers());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> speeds = DEFAULTS);
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            for (int tier = 1; tier <= 3; tier++) if (stack.is(ModContent.ITEMS.get("speed_shard_" + tier))) {
                var speed = speeds.get(tier - 1);
                lines.add(Component.translatable("tooltip.emulator_i86_crafted.steps_per_tick", speed.stepsPerTick()).withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("tooltip.emulator_i86_crafted.batches_per_tick", speed.batchesPerTick()).withStyle(ChatFormatting.GRAY));
                break;
            }
        });
    }
}

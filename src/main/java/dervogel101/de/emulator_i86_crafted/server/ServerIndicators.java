package dervogel101.de.emulator_i86_crafted.server;

import dervogel101.de.emulator_i86_crafted.block.MachineBlock;
import dervogel101.de.emulator_i86_crafted.network.IndicatorPackets;
import dervogel101.de.i86.runtime.IndicatorState;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import java.util.*;

/** Server-thread collection; transient pulses never change a block or trigger a save. */
public final class ServerIndicators {
    private record Position(ServerLevel level, BlockPos position) {
        Position { position = position.immutable(); }
    }
    private final Map<Position, Integer> next = new HashMap<>(), events = new HashMap<>();
    private final Set<Position> failures = new HashSet<>();
    private final Map<UUID, Map<BlockPos, Integer>> sent = new HashMap<>();
    private final Map<UUID, String> dimensions = new HashMap<>();
    public void begin() { next.clear(); }
    public void disconnect(UUID player) { sent.remove(player); dimensions.remove(player); }
    public void remove(ServerLevel level, BlockPos position) {
        var key = new Position(level, position); next.remove(key); events.remove(key); failures.remove(key);
    }
    public void set(ServerLevel level, BlockPos position, int status, int activity) {
        next.put(new Position(level, position), IndicatorState.pack(status, activity));
    }
    public void pulse(ServerLevel level, BlockPos position, int activity) {
        events.merge(new Position(level, position), activity, (a, b) -> a | b);
    }
    public void failure(ServerLevel level, BlockPos position, boolean failed) {
        var key = new Position(level, position);
        if (failed) failures.add(key); else failures.remove(key);
    }
    public int state(ServerLevel level, BlockPos position) { return next.getOrDefault(new Position(level, position), 0); }

    public void flush(MinecraftServer server) {
        events.forEach((key, activity) -> next.merge(key, activity << 2, (a, b) -> a | b));
        events.clear();
        failures.removeIf(key -> !key.level().hasChunkAt(key.position())
                || !(key.level().getBlockState(key.position()).getBlock() instanceof MachineBlock));
        for (var key : failures) next.merge(key, IndicatorState.FAILURE, (a, b) -> (a & ~3) | b);
        var watched = new HashMap<ServerPlayer, Map<BlockPos, Integer>>();
        var chunks = new HashMap<ServerLevel, Map<Long, Collection<ServerPlayer>>>();
        for (var entry : next.entrySet()) {
            var key = entry.getKey(); var level = key.level(); var position = key.position();
            if (!level.hasChunkAt(position) || !(level.getBlockState(position).getBlock() instanceof MachineBlock block)
                    || block == dervogel101.de.emulator_i86_crafted.ModContent.BLOCKS.get("super_structure")) continue;
            var watchers = chunks.computeIfAbsent(level, ignored -> new HashMap<>()).computeIfAbsent(ChunkPos.pack(position),
                    ignored -> PlayerLookup.tracking(level, new ChunkPos(position.getX() >> 4, position.getZ() >> 4)));
            for (var player : watchers) watched.computeIfAbsent(player, ignored -> new HashMap<>()).put(position, entry.getValue());
        }
        var connected = new HashSet<UUID>();
        for (var player : server.getPlayerList().getPlayers()) {
            var id = player.getUUID(); connected.add(id);
            if (!ServerPlayNetworking.canSend(player, IndicatorPackets.Update.TYPE)) continue;
            String dimension = player.level().dimension().identifier().toString();
            var previous = sent.computeIfAbsent(id, ignored -> new HashMap<>());
            if (!dimension.equals(dimensions.put(id, dimension))) previous.clear();
            var current = watched.getOrDefault(player, Map.of());
            var changes = new ArrayList<IndicatorPackets.Entry>();
            current.forEach((position, flags) -> {
                if (!Objects.equals(previous.get(position), flags)) changes.add(new IndicatorPackets.Entry(position, flags));
            });
            previous.keySet().stream().filter(position -> !current.containsKey(position))
                    .forEach(position -> changes.add(new IndicatorPackets.Entry(position, IndicatorState.REMOVE)));
            for (int i = 0; i < changes.size(); i += IndicatorPackets.LIMIT)
                ServerPlayNetworking.send(player, new IndicatorPackets.Update(dimension,
                        changes.subList(i, Math.min(i + IndicatorPackets.LIMIT, changes.size()))));
            previous.clear(); previous.putAll(current);
        }
        sent.keySet().retainAll(connected); dimensions.keySet().retainAll(connected);
    }
}

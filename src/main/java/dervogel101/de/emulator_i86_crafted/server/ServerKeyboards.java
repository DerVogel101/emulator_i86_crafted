package dervogel101.de.emulator_i86_crafted.server;

import dervogel101.de.CpuHostBridge;
import dervogel101.de.emulator_i86_crafted.block.PortBlockEntity;
import dervogel101.de.emulator_i86_crafted.network.KeyboardPackets;
import dervogel101.de.i86.runtime.KeyboardInput;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Server-thread capture leases; only bounded byte admission crosses to the CPU. */
public final class ServerKeyboards {
    private record Connection(CpuHostBridge bridge, long mainframe, int slot) {}
    private record Session(ServerPlayer player, PortBlockEntity port, Connection connection, UUID token) {}
    private final Map<PortBlockEntity, Connection> connections = new IdentityHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();

    public void connect(PortBlockEntity port, CpuHostBridge bridge) {
        var next = bridge == null ? null : new Connection(bridge, port.settings().mainframe(), port.settings().slot());
        var previous = connections.get(port);
        if (Objects.equals(previous, next)) return;
        if (previous != null) previous.bridge.submitKeyboardConnection(previous.slot, false);
        if (next == null) connections.remove(port);
        else { connections.put(port, next); bridge.submitKeyboardConnection(next.slot, true); }
        for (var session : List.copyOf(sessions.values())) if (session.port == port) close(session);
    }
    public void capture(ServerPlayer player, PortBlockEntity port) {
        var connection = connections.get(port);
        if (connection == null || !reachable(player, port) || !online(port, connection)) {
            player.sendSystemMessage(Component.literal("Keyboard is offline or out of reach")); return;
        }
        if (sessions.values().stream().anyMatch(s -> s.port == port && s.player != player)) {
            player.sendSystemMessage(Component.literal("Keyboard is in use")); return;
        }
        disconnect(player.getUUID());
        var session = new Session(player, port, connection, UUID.randomUUID());
        sessions.put(player.getUUID(), session);
        ServerPlayNetworking.send(player, new KeyboardPackets.Capture(session.token, true));
    }
    public void input(ServerPlayer player, KeyboardPackets.Key packet) {
        var session = sessions.get(player.getUUID());
        if (session == null || !session.token.equals(packet.token())) return;
        if (packet.value() == 0 || !valid(session)) { close(session); return; }
        if (!KeyboardInput.allowedByte(packet.value())) return;
        if (!session.connection.bridge.submitKeyboardInput(session.connection.slot, packet.value()))
            ServerPlayNetworking.send(player, new KeyboardPackets.Overflow(session.token));
        else ServerMachines.get(player.level().getServer()).indicators.pulse(player.level(), session.port.getBlockPos(), 1);
    }
    public void tick() { for (var session : List.copyOf(sessions.values())) if (!valid(session)) close(session); }
    public void disconnect(UUID player) { var session = sessions.get(player); if (session != null) close(session); }
    private boolean valid(Session session) {
        return Objects.equals(connections.get(session.port), session.connection) && reachable(session.player, session.port)
                && online(session.port, session.connection);
    }
    private static boolean online(PortBlockEntity port, Connection connection) {
        var owner = ServerMachines.get(((net.minecraft.server.level.ServerLevel)port.getLevel()).getServer());
        var machine = owner.machine(connection.mainframe);
        return port.settings().mainframe() == connection.mainframe && port.settings().slot() == connection.slot
                && machine != null && machine.record.powered && machine.bridge() == connection.bridge
                && owner.ports.status(port).equals("Online");
    }
    private static boolean reachable(ServerPlayer player, PortBlockEntity port) {
        return player.isAlive() && !player.isSpectator() && !port.isRemoved() && player.level() == port.getLevel()
                && player.level().hasChunkAt(port.getBlockPos())
                && player.level().getBlockEntity(port.getBlockPos()) == port
                && player.isWithinBlockInteractionRange(port.getBlockPos(), 0);
    }
    private void close(Session session) {
        sessions.remove(session.player.getUUID(), session);
        ServerPlayNetworking.send(session.player, new KeyboardPackets.Capture(session.token, false));
    }
}

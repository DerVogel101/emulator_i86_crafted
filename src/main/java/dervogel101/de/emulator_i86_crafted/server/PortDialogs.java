package dervogel101.de.emulator_i86_crafted.server;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.block.PortBlockEntity;
import dervogel101.de.emulator_i86_crafted.server.storage.PortBindings;
import dervogel101.de.i86.runtime.RedstoneIrqMode;
import net.minecraft.core.Holder;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import dervogel101.de.emulator_i86_crafted.network.ControlPanel;
import dervogel101.de.emulator_i86_crafted.network.PanelPackets;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Native Minecraft forms; submissions are bound to the player, block instance, and one-use nonce. */
public final class PortDialogs {
    private record Session(PortBlockEntity port, UUID nonce) {}
    private static final Map<UUID, Session> OPEN = new HashMap<>();
    private PortDialogs() {}
    public static void disconnect(UUID player) { OPEN.remove(player); }
    public static void open(ServerPlayer player, PortBlockEntity port) {
        var machines = ServerMachines.get(player.level().getServer());
        var s = port.settings(); var nonce = UUID.randomUUID();
        OPEN.put(player.getUUID(), new Session(port, nonce));
        var inputs = new ArrayList<ControlPanel.Field>();
        inputs.add(text("mainframe", "Mainframe ID (0 = unbound)", Long.toString(s.mainframe()), 20));
        inputs.add(text("slot", port.kind() == PortBindings.Kind.KEYBOARD ? "Slot ID (0–7)" : "Slot ID (0–127)", Integer.toString(s.slot()), 3));
        inputs.add(text("name", "Friendly name", s.name(), 128));
        if (port.kind() == PortBindings.Kind.REDSTONE) {
            inputs.add(option("direction", "Direction", s.input() ? "input" : "output", List.of("input", "output")));
            inputs.add(option("irq", "Input interrupt", s.irq().name(), Arrays.stream(RedstoneIrqMode.values()).map(Enum::name).toList()));
        } else if (port.kind() == PortBindings.Kind.SIGN) inputs.add(option("sides", "Sign click interrupts: 0=off, 1=front, 2=back, 3=both", Integer.toString(s.signSides()), List.of("0", "1", "2", "3")));
        StringBuilder info = new StringBuilder(machines.ports.status(port));
        info.append(port.kind() == PortBindings.Kind.KEYBOARD
                ? "\nAttaching or changing a slot requires power off. Names may be changed while powered. Keyboard input interrupts are automatic."
                : "\nAttaching or changing a slot/direction requires power off. Names and IRQ settings may be changed while powered.");
        for (var record : machines.records.mainframes().stream().filter(record -> record.controllers.stream()
                .anyMatch(p -> p.dimension().equals(player.level().dimension().identifier().toString()))).limit(12).toList())
            info.append("\n").append(record.displayName()).append(record.powered ? " — on" : " — off");
        boolean control = s.mainframe() == 0 || machines.leases.canControl(s.mainframe(), player.getUUID());
        if (!control) info.append("\nRead only: another player controls the debugger.");
        var buttons = control ? List.of(button("Apply", nonce, "apply"), button("Unbind", nonce, "unbind")) : List.<ControlPanel.Action>of();
        String icon = switch (port.kind()) { case SIGN -> "sign_port"; case REDSTONE -> "redstone_port"; case KEYBOARD -> "keyboard"; };
        PanelPackets.open(player, new ControlPanel(ControlPanel.Kind.PORT, nonce, icon,
                port.kind() == PortBindings.Kind.KEYBOARD ? "Keyboard" : port.kind() == PortBindings.Kind.SIGN ? "Sign Port" : "Redstone Port", info.toString(), inputs, buttons));
    }
    public static void submit(ServerPlayer player, Tag payload) {
        if (!(payload instanceof CompoundTag tag)) return;
        Session session = OPEN.get(player.getUUID());
        if (session == null || !session.nonce().toString().equals(tag.getStringOr("token", ""))) return;
        OPEN.remove(player.getUUID());
        var port = session.port();
        if (port.isRemoved() || port.getLevel() != player.level() || player.isSpectator()
                || !player.level().hasChunkAt(port.getBlockPos()) || player.level().getBlockEntity(port.getBlockPos()) != port
                || player.distanceToSqr(Vec3.atCenterOf(port.getBlockPos())) > 64 || !player.level().mayInteract(player, port.getBlockPos())) return;
        try {
            var old = port.settings(); PortBindings.Settings next;
            if (tag.getStringOr("action", "").equals("unbind")) next = new PortBindings.Settings(0, old.slot(), old.name(), old.input(), old.irq(), old.signSides());
            else if (tag.getStringOr("action", "").equals("apply")) {
                String name = value(tag, "name", "", 128);
                String direction = value(tag, "direction", "input", 6);
                if (!direction.equals("input") && !direction.equals("output")) throw new IllegalArgumentException("Invalid port direction");
                next = new PortBindings.Settings(Long.parseLong(value(tag, "mainframe", "", 20)), Integer.parseInt(value(tag, "slot", "", 3)), name,
                        direction.equals("input"), RedstoneIrqMode.valueOf(value(tag, "irq", "DISABLED", 8)), Integer.parseInt(value(tag, "sides", "0", 1)));
            } else return;
            var ports = ServerMachines.get(player.level().getServer()).ports;
            ports.configure(port, next, player.getUUID());
            player.sendSystemMessage(Component.literal("Port " + next.name() + " [" + next.mainframe() + ":" + next.slot() + "]: " + ports.status(port)));
        } catch (IllegalArgumentException | IllegalStateException invalid) { player.sendSystemMessage(Component.literal(invalid.getMessage())); }
    }
    private static String value(CompoundTag tag, String key, String fallback, int maximum) {
        String value = tag.getStringOr(key, fallback);
        if (value.length() > maximum) throw new IllegalArgumentException("Invalid port field length: " + key);
        return value;
    }
    private static ControlPanel.Field text(String key, String label, String initial, int maximum) { return ControlPanel.text(key, label, initial, maximum); }
    static ControlPanel.Field option(String key, String label, String initial, List<String> values) { return ControlPanel.option(key, label, initial, values); }
    private static ControlPanel.Action button(String label, UUID nonce, String action) { return new ControlPanel.Action(label, action, Map.of()); }
    public static void close(UUID player, UUID token) {
        var session = OPEN.get(player);
        if (session != null && session.nonce().equals(token)) OPEN.remove(player);
    }
}

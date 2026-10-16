package dervogel101.de.emulator_i86_crafted.server;

import dervogel101.de.CpuHostBridge;
import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.block.*;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.i86.runtime.SignLine;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.*;
import java.util.*;

/** All world access stays on the server thread; workers receive bridge submissions only. */
public final class ServerPorts {
    private static final FontDescription FONT = new FontDescription.Resource(ModContent.id("monocraft"));
    private final ServerMachines owner;
    private final Set<PortBlockEntity> loaded = new LinkedHashSet<>();
    private final Map<WorldRecords.Location, Set<PortBlockEntity>> signs = new HashMap<>();
    public ServerPorts(ServerMachines owner) { this.owner = owner; }
    public void register(PortBlockEntity port) {
        if (!(port.getLevel() instanceof ServerLevel level)) return;
        if (!loaded.add(port)) return;
        var location = ServerMachines.location(level, port.getBlockPos());
        owner.records.ports.at(location).filter(saved -> saved.kind() == port.kind())
                .ifPresent(saved -> port.applySettings(saved.settings()));
        port.sampledBridge = null; port.signTarget = null; port.signVersion = Long.MIN_VALUE;
        changed(port);
    }
    public void changed(PortBlockEntity port) {
        if (port.getLevel() instanceof ServerLevel level)
            owner.records.ports.put(ServerMachines.location(level, port.getBlockPos()), port.kind(), port.settings());
    }
    public void configureInterrupts(WorldRecords.Location location, long mainframe, UUID player,
                                    dervogel101.de.i86.runtime.RedstoneIrqMode irq, int sides) {
        var saved = owner.records.ports.at(location).orElseThrow(() -> new IllegalArgumentException("Port no longer exists"));
        var old = saved.settings();
        if (old.mainframe() != mainframe || !owner.leases.canControl(mainframe, player))
            throw new IllegalStateException("Port binding changed or another player controls the debugger");
        var next = new PortBindings.Settings(mainframe, old.slot(), old.name(), old.input(), irq, sides);
        owner.records.ports.put(location, saved.kind(), next);
        for (var port : loaded) if (ServerMachines.location((ServerLevel) port.getLevel(), port.getBlockPos()).equals(location))
            port.applySettings(next);
    }
    public void unload(PortBlockEntity port) {
        loaded.remove(port); port.online = false; port.sampledBridge = null;
        port.signTarget = null; port.signVersion = Long.MIN_VALUE;
        signs.values().forEach(ports -> ports.remove(port));
    }
    public void remove(ServerLevel level, BlockPos pos) {
        owner.records.ports.remove(ServerMachines.location(level, pos));
        for (var port : List.copyOf(loaded)) if (port.getLevel() == level && port.getBlockPos().equals(pos)) unload(port);
    }
    public void configure(PortBlockEntity port, PortBindings.Settings next, UUID player) {
        var old = port.settings();
        boolean attachment = old.mainframe() != next.mainframe() || old.slot() != next.slot()
                || port.kind() == PortBindings.Kind.REDSTONE && old.input() != next.input();
        for (long id : new long[]{old.mainframe(), next.mainframe()}) if (id != 0) {
            var record = owner.records.mainframe(id).orElse(null);
            if (record == null) {
                if (id == next.mainframe()) throw new IllegalArgumentException("Unknown mainframe ID: " + id);
                continue;
            }
            if (!owner.leases.canControl(id, player)) throw new IllegalStateException("Another player controls this mainframe's debugger");
            if (attachment && record.powered) throw new IllegalStateException("Power off mainframe " + id + " before attaching or changing a port");
        }
        if (next.mainframe() != 0) {
            var record = owner.records.mainframe(next.mainframe()).orElseThrow();
            String dimension = ((ServerLevel) port.getLevel()).dimension().identifier().toString();
            if (!record.controllers.isEmpty() && record.controllers.stream().noneMatch(p -> p.dimension().equals(dimension)))
                throw new IllegalArgumentException("External ports cannot link across dimensions");
        }
        port.settings(next);
    }
    public String status(PortBlockEntity port) {
        return status(ServerMachines.location((ServerLevel) port.getLevel(), port.getBlockPos()));
    }
    public String status(WorldRecords.Location location) {
        var binding = owner.records.ports.at(location).orElse(null);
        if (binding == null || binding.settings().mainframe() == 0) return "Unbound";
        var settings = binding.settings();
        var conflicts = owner.records.ports.conflicts(location);
        if (!conflicts.isEmpty()) return "Duplicate port slot: " + conflicts;
        var record = owner.records.mainframe(settings.mainframe()).orElse(null);
        if (record == null || record.controllers.isEmpty()) return "Offline: controller absent";
        if (record.conflicted()) return "Offline: duplicate controller ID at " + record.controllers;
        if (record.controllers.stream().noneMatch(p -> p.dimension().equals(location.dimension()))) return "Offline: controller is in another dimension";
        var hardware = owner.portHardware(record.id);
        int capacity = binding.kind() == PortBindings.Kind.REDSTONE ? hardware.redstoneCapacity() : hardware.signCapacity();
        if (settings.slot() >= capacity) return "Offline: slot " + settings.slot() + " needs more installed modules (capacity " + capacity + ")";
        return "Online";
    }
    public void tick(Collection<MainframeMachine> machines) {
        var active = new HashMap<Long, MainframeMachine>();
        for (var machine : machines) { machine.outputs(); active.put(machine.record.id, machine); }
        signs.clear();
        for (var port : List.copyOf(loaded)) {
            if (port.isRemoved()) { loaded.remove(port); continue; }
            ServerLevel level = (ServerLevel) port.getLevel();
            // World reads would renew UNKNOWN tickets and prevent this chunk from unloading.
            if (!level.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.pack(port.getBlockPos()))) {
                port.online = false; port.sampledBridge = null; port.signTarget = null;
                continue;
            }
            var s = port.settings(); String status = status(port);
            if (!status.equals(port.status) && status.startsWith("Duplicate")) {
                var machine = active.get(s.mainframe());
                if (machine != null && machine.bridge() != null) machine.bridge().reportHostError(status + "\n");
            }
            boolean online = status.equals("Online");
            if (!online || !port.online) { port.sampledBridge = null; port.signTarget = null; port.signVersion = Long.MIN_VALUE; }
            port.status = status; port.online = online;
            if (!online) { port.signal(0); visual(port, false); continue; }
            var record = owner.records.mainframe(s.mainframe()).orElseThrow();
            var machine = active.get(record.id); CpuHostBridge bridge = machine == null ? null : machine.bridge();
            BlockPos target = port.getBlockPos().relative(port.getBlockState().getValue(PortBlock.PORT_FACING));
            boolean targetLoaded = level.hasChunkAt(target);
            if (port.kind() == PortBindings.Kind.REDSTONE) {
                port.signal(s.input() ? 0 : record.redstoneOutputs[s.slot()] & 15);
                if (s.input() && targetLoaded && bridge != null) {
                    int strength = level.getSignal(target, port.getBlockState().getValue(PortBlock.PORT_FACING));
                    if (port.sampledBridge != bridge || port.appliedIrq != s.irq()) bridge.submitRedstoneIrqMode(s.slot(), s.irq());
                    port.appliedIrq = s.irq();
                    if (port.sampledBridge != bridge) {
                        bridge.submitRedstoneInput(s.slot(), strength, false);
                    } else if (port.sampledInput != strength) bridge.submitRedstoneInput(s.slot(), strength);
                    port.sampledBridge = bridge; port.sampledInput = strength;
                } else port.sampledBridge = null;
            } else {
                signs.computeIfAbsent(ServerMachines.location(level, target), ignored -> new LinkedHashSet<>()).add(port);
                if (bridge != null && (port.sampledBridge != bridge || port.appliedSignSides != s.signSides()))
                    bridge.submitSignIrqSides(s.slot(), s.signSides());
                port.appliedSignSides = s.signSides();
                port.sampledBridge = bridge;
                var sign = targetLoaded && level.getBlockEntity(target) instanceof SignBlockEntity found ? found : null;
                if (sign != null && (port.signTarget != sign || port.signVersion != record.signVersions[s.slot()])) {
                    writeSign(level, sign, record.signOutputs[s.slot()]);
                    port.signVersion = record.signVersions[s.slot()];
                }
                port.signTarget = sign;
            }
            visual(port, record.powered);
        }
    }
    public void signClick(ServerPlayer player, BlockPos target) {
        if (!(player.level().getBlockEntity(target) instanceof SignBlockEntity sign)) return;
        var ports = signs.get(ServerMachines.location(player.level(), target));
        if (ports == null) return;
        var side = sign.getSlotPlayerIsFacing(player) == SignTextSlot.FRONT ? CpuHostBridge.SignSide.FRONT : CpuHostBridge.SignSide.BACK;
        for (var port : ports) if (!port.isRemoved() && port.online && status(port).equals("Online")) {
            var machine = owner.machine(port.settings().mainframe());
            if (machine != null && machine.record.powered && machine.bridge() != null) machine.bridge().submitSignClick(port.settings().slot(), side);
        }
    }
    private static void visual(PortBlockEntity port, boolean powered) {
        var state = port.getBlockState();
        if (state.getValue(MachineBlock.POWERED) != powered) port.getLevel().setBlock(port.getBlockPos(), state.setValue(MachineBlock.POWERED, powered), Block.UPDATE_CLIENTS);
    }
    private static void writeSign(ServerLevel level, SignBlockEntity sign, byte[] bytes) {
        for (int side = 0; side < 2; side++) {
            var slot = side == 0 ? SignTextSlot.FRONT : SignTextSlot.BACK;
            var text = sign.getText(slot).asMutable();
            for (int line = 0; line < 4; line++) {
                var decoded = SignLine.decode(bytes, side, line); int format = decoded.format();
                Style style = Style.EMPTY.withFont(FONT).withStrikethrough((format & 0x20) != 0)
                        .withItalic((format & 0x40) != 0).withUnderlined((format & 0x80) != 0);
                if ((format & 0x10) == 0) style = style.withColor(ChatFormatting.getByCode(Character.forDigit(format & 15, 16)));
                text.setLine(line, Component.literal(decoded.text()).withStyle(style));
            }
            sign.setText(text.asImmutable(), slot);
        }
        sign.setWaxed(true);
        sign.setAllowedPlayerEditor(null);
        sign.setChanged();
        var state = sign.getBlockState(); level.sendBlockUpdated(sign.getBlockPos(), state, state, Block.UPDATE_CLIENTS);
    }
}

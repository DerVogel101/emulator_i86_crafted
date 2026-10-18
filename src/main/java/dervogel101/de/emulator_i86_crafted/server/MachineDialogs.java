package dervogel101.de.emulator_i86_crafted.server;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.block.*;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.i86.runtime.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import dervogel101.de.emulator_i86_crafted.network.ControlPanel;
import dervogel101.de.emulator_i86_crafted.network.PanelPackets;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Consumer;

/** One-use native forms; every action rechecks the surviving block and debugger lease. */
public final class MachineDialogs {
    private record Session(ServerLevel level, BlockPos origin, Block block, net.minecraft.world.level.block.entity.BlockEntity entity, long mainframe, UUID nonce,
                           Map<String, Consumer<CompoundTag>> actions, int terminalPage, ControlPanel panel) {}
    private static final Map<UUID, Session> OPEN = new HashMap<>();
    private static final int PAGE_SIZE = 8, LOG_PAGE_SIZE = 3000;
    private MachineDialogs() {}
    public static boolean terminalViewed(long mainframe) {
        return OPEN.values().stream().anyMatch(session -> session.mainframe() == mainframe && session.terminalPage() >= 0);
    }

    public static void disconnect(UUID player) { OPEN.remove(player); }

    public static void open(ServerPlayer player, BlockPos position) {
        if (!(player.level().getBlockState(position).getBlock() instanceof MachineBlock block) || block.hardware() == null) return;
        var machines = ServerMachines.get(player.level().getServer());
        if (player.level().getBlockEntity(position) instanceof ControllerBlockEntity controller) machines.attach(controller);
        var form = new Form(player, position, block.hardware().displayName);
        form.info.append(block.hardware().displayName).append('\n').append(form.status());
        if (form.record != null) {
            if (form.record.conflicted()) form.info.append("\nDuplicate controllers: ").append(form.record.controllers);
            if (form.record.faulted) form.info.append(String.format("\nCPU fault at %04X:%04X", form.record.faultCs, form.record.faultIp));
        }
        if (player.level().getBlockEntity(position) instanceof ControllerBlockEntity controller) {
            form.info.append("\n\n").append(machines.structureDiagnostics(controller));
            form.info.append("\n\nROM: ").append(controller.romId() == null ? "none" : controller.romId())
                    .append("\nSpeed shard: ").append(controller.shardTier() == 0 ? "none" : controller.shardTier());
            form.add("ROM / speed shard", ignored -> controller.open(player));
            if (form.record != null) {
                form.inputs.add(text("name", "Friendly name (ID is always displayed)", form.record.name, 128));
                if (machines.canEdit(form.record.id, player.getUUID())) form.add("Rename", tag -> {
                    form.edit(); machines.rename(controller, value(tag, "name", 128), player.getUUID()); open(player, position);
                });
            }
            if (form.record != null) form.add("Open debugger", ignored -> machines.debugger.open(player, controller));
        }
        if (player.level().getBlockEntity(position) instanceof ArrayBlockEntity array) {
            if (array.isDisk()) form.info.append("\nDisk ID: ").append(array.diskId(machines.records));
            else {
                form.info.append("\nShared memory ID: ").append(array.memoryId().isEmpty() ? "unassigned" : array.memoryId())
                        .append("\nIDs ignore case; 1–32 letters, digits or underscores. New regions contain zeroes.");
                form.inputs.add(text("memory", "Shared memory ID", array.memoryId(), 32));
                if (machines.canConfigurePart(player.level(), position, player.getUUID())) form.add("Select region", tag -> {
                    form.edit(); array.memoryId(value(tag, "memory", 32), player.getUUID()); open(player, position);
                });
            }
        }
        if (form.record != null) {
            switch (block.hardware()) {
                case TERMINAL -> { terminal(player, position, 0); return; }
                case INTERRUPTS -> { devices(player, position, 0); return; }
                case POWER -> {
                    form.info.append("\nPSU redstone input: ").append(player.level().getBestNeighborSignal(position))
                            .append("\nPower cycle restarts automatically only while this input is powered.");
                    if (form.controls()) form.add("Power cycle", ignored -> {
                        form.edit(); machines.powerOff(form.record.id, "Manual power cycle");
                        form.record.faulted = false; form.record.paused = false;
                        open(player, position);
                    });
                }
                case RESET -> {
                    form.info.append("\nCPU reset starts a powered CPU and preserves RAM and device state.");
                    if (form.controls()) form.add("CPU reset", ignored -> {
                        form.edit(); form.machine().reset(); open(player, position);
                    });
                }
                case NMI -> {
                    form.info.append("\nNMI is independent of the device interrupt controller.");
                    if (form.controls() && form.record.powered) form.add("Pulse NMI", ignored -> {
                        form.edit(); form.machine().nmi(); open(player, position);
                    });
                }
                default -> {}
            }
        }
        form.add("Refresh", ignored -> open(player, position));
        form.show();
    }

    private static void terminal(ServerPlayer player, BlockPos position, int page) { terminal(player, position, page, null); }
    private static void terminal(ServerPlayer player, BlockPos position, int page, UUID refreshToken) {
        var form = new Form(player, position, "Access Terminal", refreshToken == null ? UUID.randomUUID() : refreshToken);
        if (form.record == null) { open(player, position); return; }
        String log;
        try { log = form.machines.terminal(form.record.id); }
        catch (IllegalStateException unavailable) { log = "Terminal unavailable: " + unavailable.getMessage(); }
        int pages = Math.max(1, (log.length() + LOG_PAGE_SIZE - 1) / LOG_PAGE_SIZE);
        int selected = Math.clamp(page, 0, pages - 1);
        int end = Math.max(0, log.length() - selected * LOG_PAGE_SIZE), start = Math.max(0, end - LOG_PAGE_SIZE);
        form.info.append(form.status()).append("\nTerminal page ").append(selected + 1).append('/').append(pages)
                .append(" (newest first)\n\n").append(log.isEmpty() ? "No terminal output." : log.substring(start, end));
        form.terminalPage = selected;
        if (selected + 1 < pages) form.add("older", "Older output", ignored -> terminal(player, position, selected + 1));
        if (selected > 0) form.add("newer", "Newer output", ignored -> terminal(player, position, selected - 1));
        form.add("latest", "Latest / refresh", ignored -> terminal(player, position, 0));
        form.show(refreshToken != null);
    }

    private static void devices(ServerPlayer player, BlockPos position, int page) {
        var form = new Form(player, position, "Interrupt Controller");
        if (form.record == null) { open(player, position); return; }
        var entries = form.machines.records.ports.entries().entrySet().stream()
                .filter(entry -> entry.getValue().settings().mainframe() == form.record.id)
                .sorted(Comparator.comparing((Map.Entry<WorldRecords.Location, PortBindings.Port> e) -> e.getValue().kind())
                        .thenComparingInt(e -> e.getValue().settings().slot()).thenComparing(e -> e.getKey().toString())).toList();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE), selected = Math.clamp(page, 0, pages - 1);
        form.info.append(form.status()).append("\nDevice page ").append(selected + 1).append('/').append(pages)
                .append("\nIncludes unloaded ports. Duplicate slots disable all conflicting ports.");
        if (entries.isEmpty()) form.info.append("\nNo ports assigned.");
        for (var entry : entries.subList(selected * PAGE_SIZE, Math.min(entries.size(), (selected + 1) * PAGE_SIZE))) {
            var binding = entry.getValue(); var settings = binding.settings(); var location = entry.getKey();
            String label = binding.kind() + " [" + settings.slot() + "] " + settings.name();
            form.info.append("\n\n").append(label).append("\n").append(location).append("\n").append(form.machines.ports.status(location));
            form.add(label, ignored -> device(player, position, location, selected));
        }
        if (selected > 0) form.add("Previous page", ignored -> devices(player, position, selected - 1));
        if (selected + 1 < pages) form.add("Next page", ignored -> devices(player, position, selected + 1));
        form.add("Refresh", ignored -> devices(player, position, selected));
        form.show();
    }

    private static void device(ServerPlayer player, BlockPos position, WorldRecords.Location location, int page) {
        var form = new Form(player, position, "Device IRQ settings");
        var binding = form.machines.records.ports.at(location).orElse(null);
        if (form.record == null || binding == null || binding.settings().mainframe() != form.record.id) {
            devices(player, position, page); return;
        }
        var settings = binding.settings();
        form.info.append(form.status()).append('\n').append(binding.kind()).append(" [").append(settings.slot()).append("] ")
                .append(settings.name()).append('\n').append(location).append('\n').append(form.machines.ports.status(location));
        if (binding.kind() == PortBindings.Kind.REDSTONE) {
            form.info.append(settings.input() ? "\nInput: every analog change can raise an IRQ." : "\nOutput port: input IRQs are inactive.");
            form.inputs.add(PortDialogs.option("irq", "Redstone IRQ mode", settings.irq().name(), Arrays.stream(RedstoneIrqMode.values()).map(Enum::name).toList()));
        } else if (binding.kind() == PortBindings.Kind.SIGN)
            form.inputs.add(PortDialogs.option("sides", "Click IRQs: 0=off, 1=front, 2=back, 3=both", Integer.toString(settings.signSides()), List.of("0", "1", "2", "3")));
        else form.info.append("\nKeyboard input interrupts are automatic.");
        if (form.controls() && binding.kind() != PortBindings.Kind.KEYBOARD) form.add("Apply IRQ settings", tag -> {
            form.edit();
            if (form.machines.records.ports.at(location).filter(binding::equals).isEmpty())
                throw new IllegalStateException("Port settings changed; reopen its device settings");
            var irq = binding.kind() == PortBindings.Kind.REDSTONE ? RedstoneIrqMode.valueOf(value(tag, "irq", 8)) : settings.irq();
            int sides = binding.kind() == PortBindings.Kind.SIGN ? Integer.parseInt(value(tag, "sides", 1)) : settings.signSides();
            form.machines.ports.configureInterrupts(location, form.record.id, player.getUUID(), irq, sides);
            device(player, position, location, page);
        });
        form.add("Back to devices", ignored -> devices(player, position, page));
        form.show();
    }

    private static boolean valid(ServerPlayer player, Session session) {
        if (player.level() != session.level() || !player.level().hasChunkAt(session.origin())
                || !player.level().getBlockState(session.origin()).is(session.block())
                || player.level().getBlockEntity(session.origin()) != session.entity()
                || player.distanceToSqr(Vec3.atCenterOf(session.origin())) > 64) return false;
        var machines = ServerMachines.get(player.level().getServer());
        if (machines.mainframeForPart(player.level(), session.origin()).map(record -> record.id).orElse(0L) != session.mainframe()) return false;
        return true;
    }

    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (server.getTickCount() % 5 != 0) return;
        for (var entry : List.copyOf(OPEN.entrySet())) {
            var session = entry.getValue();
            if (session.level().getServer() != server || session.terminalPage() != 0) continue;
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !valid(player, session)) {
                OPEN.remove(entry.getKey(), session);
                if (player != null) PanelPackets.update(player, new ControlPanel(ControlPanel.Kind.MACHINE, session.nonce(),
                        "access_terminal", "Access Terminal", "Terminal disconnected.", List.of(), List.of()));
                continue;
            }
            terminal(player, session.origin(), 0, session.nonce());
        }
    }

    public static void submit(ServerPlayer player, Tag payload) {
        if (!(payload instanceof CompoundTag tag)) return;
        var session = OPEN.get(player.getUUID());
        if (session == null || !session.nonce().toString().equals(tag.getStringOr("token", ""))) return;
        OPEN.remove(player.getUUID());
        if (!valid(player, session)) return;
        var action = session.actions().get(tag.getStringOr("action", ""));
        if (action == null) return;
        try { action.accept(tag); }
        catch (IllegalArgumentException | IllegalStateException failure) { player.sendSystemMessage(Component.literal(failure.getMessage())); }
    }

    private static String value(CompoundTag tag, String key, int maximum) {
        String result = tag.getStringOr(key, "");
        if (result.length() > maximum) throw new IllegalArgumentException("Invalid field length: " + key);
        return result;
    }
    private static ControlPanel.Field text(String key, String label, String initial, int maximum) {
        return ControlPanel.text(key, label, initial, maximum);
    }
    public static void close(UUID player, UUID token) {
        var session = OPEN.get(player);
        if (session != null && session.nonce().equals(token)) OPEN.remove(player);
    }

    private static final class Form {
        final ServerPlayer player;
        final BlockPos position;
        final ServerMachines machines;
        final WorldRecords.Mainframe record;
        final String title;
        final UUID nonce;
        int terminalPage = -1;
        final StringBuilder info = new StringBuilder();
        final List<ControlPanel.Field> inputs = new ArrayList<>();
        final List<ControlPanel.Action> buttons = new ArrayList<>();
        final Map<String, Consumer<CompoundTag>> actions = new HashMap<>();
        Form(ServerPlayer player, BlockPos position, String title) { this(player, position, title, UUID.randomUUID()); }
        Form(ServerPlayer player, BlockPos position, String title, UUID nonce) {
            this.nonce = nonce;
            this.player = player; this.position = position.immutable(); this.title = title;
            machines = ServerMachines.get(player.level().getServer());
            record = machines.mainframeForPart(player.level(), position).orElse(null);
        }
        String status() {
            if (record != null && record.status.equals("Powered off: invalid mainframe structure")) return "Invalid assembly.";
            return record == null ? "Invalid assembly." : record.displayName() + " — " + record.status
                    + (controls() ? "" : "\nRead only: another player controls the debugger.");
        }
        boolean controls() { return record == null || machines.leases.canControl(record.id, player.getUUID()); }
        void edit() {
            if (player.isSpectator() || !player.level().mayInteract(player, position) || !controls())
                throw new IllegalStateException("You cannot edit this mainframe; another player may control its debugger");
        }
        MainframeMachine machine() {
            var machine = record == null ? null : machines.machine(record.id);
            if (machine == null) throw new IllegalStateException("Mainframe is unloaded");
            return machine;
        }
        void add(String label, Consumer<CompoundTag> action) {
            add(Integer.toString(actions.size()), label, action);
        }
        void add(String key, String label, Consumer<CompoundTag> action) {
            actions.put(key, action);
            buttons.add(new ControlPanel.Action(label, key, Map.of()));
        }
        void show() { show(false); }
        void show(boolean update) {
            var previous = OPEN.get(player.getUUID());
            if (update && (previous == null || !previous.nonce().equals(nonce))) return;
            var block = (MachineBlock) player.level().getBlockState(position).getBlock();
            var panel = new ControlPanel(ControlPanel.Kind.MACHINE, nonce, block.hardware().id, title, info.toString(), inputs, buttons);
            OPEN.put(player.getUUID(), new Session(player.level(), position, block, player.level().getBlockEntity(position),
                    record == null ? 0 : record.id, nonce, Map.copyOf(actions), terminalPage, panel));
            if (!update) PanelPackets.open(player, panel);
            else if (!panel.equals(previous.panel())) PanelPackets.update(player, panel);
        }
    }
}

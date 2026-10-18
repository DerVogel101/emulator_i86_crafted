package dervogel101.de.emulator_i86_crafted.server;

import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.block.*;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.i86.runtime.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Server-thread owner of loaded machines, world records, placements and control leases. */
public final class ServerMachines {
    private static final Logger LOGGER = LoggerFactory.getLogger(Emulator_i86_crafted.MOD_ID);
    private static final Map<MinecraftServer, ServerMachines> SERVERS = new IdentityHashMap<>();
    public final WorldRecords records;
    public final RomLibrary roms;
    public final PunchcardLibrary punchcards;
    public final DebuggerLeases leases = new DebuggerLeases();
    public final ServerPorts ports = new ServerPorts(this);
    public final ServerKeyboards keyboards = new ServerKeyboards();
    public final RomBurner burner = new RomBurner(this);
    public final ServerDebugger debugger = new ServerDebugger(this);
    public final ServerIndicators indicators = new ServerIndicators();
    public final Path folder;
    private final WorldStore store;
    private final MachineConfig config;
    private final DiskClaims disks = new DiskClaims();
    private final Map<ControllerBlockEntity, Loaded> loaded = new LinkedHashMap<>();
    private final Set<ControllerBlockEntity> pending = new LinkedHashSet<>();
    private final Map<WorldRecords.Location, WorldRecords.Mainframe> positions = new HashMap<>();
    private boolean stopping;
    private boolean stopSaveFailed;
    private List<Loaded> indicatorMachines = List.of();

    private record Structure(WallLayout wall, Map<Hardware, List<BlockPos>> parts, String key, List<MachineBlock> blocks) {
        BlockPos part(Hardware hardware) { return parts.get(hardware).getFirst(); }
        boolean contains(BlockPos position) { return wall.positions().contains(new WallLayout.Pos(position.getX(), position.getY(), position.getZ())); }
        boolean stillMatches(ServerLevel level) {
            for (int index = 0; index < wall.positions().size(); index++) {
                var position = wall.positions().get(index);
                var state = level.getBlockState(new BlockPos(position.x(), position.y(), position.z()));
                if (state.getBlock() != blocks.get(index) || !state.getValue(MachineBlock.FACING).name().equals(wall.facing().name())) return false;
            }
            return true;
        }
    }
    private static final class Loaded {
        final ControllerBlockEntity controller;
        final MainframeMachine machine;
        Structure structure;
        boolean dirty = true, sampled;
        Boolean visualPower;
        boolean visualRunning;
        boolean indicatorPowered;
        long hostErrors;
        Object indicatorBridge;
        Loaded(ControllerBlockEntity controller, MainframeMachine machine) {
            this.controller = controller; this.machine = machine; indicatorPowered = machine.record.powered;
        }
        ServerLevel level() { return (ServerLevel) controller.getLevel(); }
    }

    private ServerMachines(MinecraftServer server) throws IOException {
        folder = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(Emulator_i86_crafted.MOD_ID);
        store = new WorldStore(folder); records = store.load(); roms = new RomLibrary(folder.resolve("roms"));
        punchcards = new PunchcardLibrary(folder.resolve("punchcards"));
        config = MachineConfig.load(FabricLoader.getInstance().getConfigDir().resolve(Emulator_i86_crafted.MOD_ID + ".properties"));
        for (var record : records.mainframes()) {
            for (var location : record.controllers) positions.put(location, record);
            if (record.powered && record.diskId != null && record.hardware.has(Hardware.DISK))
                disks.claim(record.diskId, record.id, record.controllers.iterator().next().toString());
        }
    }
    public static ServerMachines get(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, ignored -> {
            try { return new ServerMachines(server); }
            catch (IOException failure) { throw new UncheckedIOException("Cannot load retained mainframe data; existing data was not replaced", failure); }
        });
    }

    public static void initialize() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var machines = SERVERS.get(server); if (machines != null) {
                machines.debugger.disconnect(handler.getPlayer().getUUID());
                machines.leases.disconnect(handler.getPlayer().getUUID());
                machines.indicators.disconnect(handler.getPlayer().getUUID());
            }
            PortDialogs.disconnect(handler.getPlayer().getUUID());
            MachineDialogs.disconnect(handler.getPlayer().getUUID());
            if (machines != null) machines.burner.disconnect(handler.getPlayer().getUUID());
            if (machines != null) machines.keyboards.disconnect(handler.getPlayer().getUUID());
        });
        ServerLifecycleEvents.SERVER_STARTED.register(ServerMachines::get);
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ControllerBlockEntity controller) get(level.getServer()).pending.add(controller);
            if (entity instanceof dervogel101.de.emulator_i86_crafted.block.PortBlockEntity port) get(level.getServer()).ports.register(port);
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((entity, level) -> {
            var machines = SERVERS.get(level.getServer());
            if (machines != null && entity instanceof ControllerBlockEntity controller) machines.detach(controller);
            if (machines != null && entity instanceof dervogel101.de.emulator_i86_crafted.block.PortBlockEntity port) machines.ports.unload(port);
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!player.isSpectator() && player.isShiftKeyDown() && level.hasChunkAt(hit.getBlockPos()) && level.getBlockEntity(hit.getBlockPos()) instanceof dervogel101.de.emulator_i86_crafted.block.PunchcardBlockEntity punchcard) {
                if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer && player.isWithinBlockInteractionRange(hit.getBlockPos(),0) && level.mayInteract(player,hit.getBlockPos())) PortDialogs.open(serverPlayer,punchcard);
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer && !player.isSpectator()
                    && hand == net.minecraft.world.InteractionHand.MAIN_HAND)
                get(serverPlayer.level().getServer()).ports.signClick(serverPlayer, hit.getBlockPos());
            return net.minecraft.world.InteractionResult.PASS;
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> get(server).tick(server));
        ServerTickEvents.END_SERVER_TICK.register(MachineDialogs::tick);
        ServerTickEvents.END_SERVER_TICK.register(server -> get(server).keyboards.tick());
        ServerTickEvents.END_SERVER_TICK.register(server -> get(server).publishIndicators(server));
        ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> get(server).save());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var machines = get(server); machines.stopping = true;
            machines.debugger.shutdown();
            try { machines.save(); }
            finally {
                for (var current : machines.loaded.values()) {
                    try { current.machine.unload(); }
                    catch (RuntimeException failure) { machines.stopSaveFailed = true; LOGGER.error("Cannot suspend mainframe {}", current.machine.record.id, failure); }
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SERVERS.remove(server));
    }

    public void attach(ControllerBlockEntity controller) {
        if (stopping) return;
        pending.remove(controller);
        if (loaded.containsKey(controller) || !(controller.getLevel() instanceof ServerLevel level)) return;
        if (controller.id() == 0) controller.assignId(records.allocate().id);
        var record = records.mainframe(controller.id()).orElse(null);
        if (record == null) return;
        var location = location(level, controller.getBlockPos());
        var previous = positions.put(location, record);
        if (previous != null && previous != record) { previous.controllers.remove(location); powerOff(previous.id, "Controller replaced"); }
        var name = controller.components().get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        if (name != null) record.name = name.getString().substring(0, Math.min(128, name.getString().length()));
        record.controllers.add(location);
        var machine = new MainframeMachine(records, record, roms, disks);
        var current = new Loaded(controller, machine); loaded.put(controller, current);
        if (record.conflicted()) powerOff(record.id, "Duplicate controller ID " + record.id + ": " + record.controllers);
        else {
            if (record.powered && machine.restore()) debugger.restored(machine);
            recovery(controller, record);
        }
    }

    private void detach(ControllerBlockEntity controller) {
        pending.remove(controller);
        Loaded current = loaded.remove(controller);
        if (current != null) {
            current.machine.unload();
            debugger.unloading(current.machine);
        }
    }

    public void controllerRemoved(ServerLevel level, BlockPos position) {
        if (stopping) return;
        var location = location(level, position); var record = positions.remove(location);
        if (record == null) return;
        powerOff(record.id, "Controller removed"); record.faulted = false; record.controllers.remove(location);
        for (var current : List.copyOf(loaded.values()))
            if (current.level() == level && current.controller.getBlockPos().equals(position)) detach(current.controller);
    }

    public void structureChanged(ServerLevel level, BlockPos position) {
        if (stopping) return;
        for (var current : loaded.values()) {
            BlockPos controller = current.controller.getBlockPos();
            if (current.level() != level || Math.abs(controller.getX() - position.getX()) > 6
                    || Math.abs(controller.getY() - position.getY()) > 2 || Math.abs(controller.getZ() - position.getZ()) > 6) continue;
            current.dirty = true;
            if (current.structure != null && current.structure.contains(position) && current.machine.record.powered) {
                current.machine.powerOff("Powered off: mainframe structure changed"); current.sampled = false; visuals(current, false);
            }
            current.machine.record.faulted = false;
        }
    }

    public void inputsChanged(ServerLevel level, BlockPos position) {
        if (stopping) return;
        for (var current : loaded.values()) {
            if (current.level() != level || current.structure == null || !current.structure.contains(position)) continue;
            if (current.machine.record.powered && level.getBestNeighborSignal(current.structure.part(Hardware.POWER)) == 0) {
                current.machine.powerOff("Powered off"); current.sampled = false; visuals(current, false);
            } else samplePins(current);
        }
    }

    private void tick(MinecraftServer server) {
        if (stopping) return;
        indicators.begin();
        long start = System.nanoTime(), duration = server.tickRateManager().nanosecondsPerTick();
        for (var current : loaded.values()) current.machine.stopWork();
        for (var controller : List.copyOf(pending)) {
            pending.remove(controller);
            if (!controller.isRemoved()) attach(controller);
        }
        // This event precedes TickRateManager.tick().
        boolean ticking = !server.tickRateManager().isFrozen() || server.tickRateManager().isSteppingForward();
        if (ticking) records.gameTicks++;
        // Reading world state outside the ticking range would renew tickets and prevent unload.
        var currentMachines = List.copyOf(loaded.values()).stream().filter(current -> !current.controller.isRemoved()
                && current.level().shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.pack(current.controller.getBlockPos()))).toList();
        for (var current : currentMachines) {
            process(current);
            if (records.gameTicks % 20 == 0) markChipValidity(current.controller);
        }
        ports.tick(currentMachines.stream().map(current -> current.machine).toList());
        indicatorMachines = currentMachines;
        for (var current : currentMachines) {
            var record = current.machine.record;
            current.machine.debugger(leases.owner(record.id).isPresent());
            if (ticking && record.powered) current.machine.tick(start, duration, config.speed(record.shardTier), records.gameTicks, current.level().getDefaultClockTime());
            visuals(current, record.powered);
        }
        debugger.tick(server.getTickCount());
    }

    private void publishIndicators(MinecraftServer server) {
        if (stopping) return;
        var sharedAccesses = new HashMap<String, Integer>();
        for (var current : indicatorMachines) if (current.machine.record.powered
                && (current.machine.activity().device(Hardware.SHARED_MEMORY) & 3) != 0)
            sharedAccesses.merge(current.machine.record.sharedId, 1, Integer::sum);
        for (var current : indicatorMachines) if (!current.controller.isRemoved()) indicatorState(current, sharedAccesses);
        ports.indicators(indicatorMachines.stream().map(current -> current.machine).toList());
        burner.indicators(); indicators.flush(server);
    }

    public static int indicatorStatus(WorldRecords.Mainframe record, dervogel101.de.i86.runtime.CpuActivity.Snapshot activity) {
        if (record.faulted || record.conflicted() || !record.powered && record.status.startsWith("Powered off: ")) return IndicatorState.FAILURE;
        if (!record.powered) return IndicatorState.OFF;
        return record.paused || activity.halted() || activity.waiting() ? IndicatorState.IDLE : IndicatorState.RUNNING;
    }

    private void indicatorState(Loaded current, Map<String, Integer> sharedAccesses) {
        var machine = current.machine; var record = machine.record;
        var snapshot = machine.activity(); int status = indicatorStatus(record, snapshot);
        if (current.structure == null) {
            indicators.set(current.level(), current.controller.getBlockPos(), status, 0); return;
        }
        if (current.indicatorPowered != record.powered) {
            machine.activity(Hardware.POWER, record.powered ? 2 : 4); current.indicatorPowered = record.powered;
        }
        if (machine.bridge() != null) {
            if (current.indicatorBridge != machine.bridge()) { current.hostErrors = 0; current.indicatorBridge = machine.bridge(); }
            long errors = machine.bridge().hostErrors();
            if (errors != current.hostErrors && record.hardware.has(Hardware.TERMINAL)) machine.activity(Hardware.TERMINAL, 4);
            current.hostErrors = errors;
        }
        for (var group : current.structure.parts().entrySet()) {
            var hardware = group.getKey(); int host = machine.takeActivity(hardware);
            for (int index = 0; index < group.getValue().size(); index++) {
                var position = group.getValue().get(index);
                int activity = record.powered ? snapshot.device(hardware) : 0;
                if (hardware == Hardware.GPIO || hardware == Hardware.GPU) {
                    activity = 0;
                    if (record.powered) for (int slot = index * 32; slot < Math.min(128, (index + 1) * 32); slot++)
                        activity |= hardware == Hardware.GPIO ? snapshot.inputs()[slot] | snapshot.outputs()[slot] : snapshot.signs()[slot];
                }
                if (hardware == Hardware.POWER || hardware == Hardware.RESET || hardware == Hardware.NMI || hardware == Hardware.TEST)
                    activity |= current.level().getBestNeighborSignal(position) > 0 ? 1 : 0;
                if (hardware == Hardware.TEST && record.powered && snapshot.waiting()) activity |= 2;
                if (hardware == Hardware.TERMINAL && MachineDialogs.terminalViewed(record.id)) activity |= 2;
                if (hardware == Hardware.SHARED_MEMORY && record.powered && !record.sharedId.isEmpty()
                        && sharedAccesses.getOrDefault(record.sharedId, 0) > ((snapshot.device(Hardware.SHARED_MEMORY) & 3) != 0 ? 1 : 0)) activity |= 4;
                indicators.set(current.level(), position, status, activity | host);
            }
        }
    }

    public void arrayActivity(long mainframe, Hardware hardware, int slot, int activity) {
        for (var current : loaded.values()) if (current.machine.record.id == mainframe && current.structure != null) {
            var positions = current.structure.parts().getOrDefault(hardware, List.of());
            if (slot / 32 < positions.size()) indicators.pulse(current.level(), positions.get(slot / 32), activity);
        }
    }

    private void process(Loaded current) {
        var record = current.machine.record;
        if (record.conflicted()) { powerOff(record.id, "Duplicate controller ID " + record.id + ": " + record.controllers); return; }
        if (current.dirty || records.gameTicks % 20 == 0 || current.structure != null && !current.structure.stillMatches(current.level())) {
            var next = inspect(current.level(), current.controller);
            if (next == null) {
                if (record.powered) current.machine.powerOff("Powered off: invalid mainframe structure");
                else record.status = "Powered off: invalid mainframe structure";
                record.hardware = new dervogel101.de.i86.runtime.HardwareProfile(Map.of());
                visuals(current, false); current.structure = null; current.dirty = false; return;
            }
            current.structure = next; current.dirty = false; current.visualPower = null;
        }
        if (current.structure == null) return;
        UUID disk = null; String memory = "";
        if (current.structure.wall().hardware().has(Hardware.DISK)
                && current.level().getBlockEntity(current.structure.part(Hardware.DISK)) instanceof ArrayBlockEntity array) disk = array.diskId(records);
        if (current.structure.wall().hardware().has(Hardware.SHARED_MEMORY)
                && current.level().getBlockEntity(current.structure.part(Hardware.SHARED_MEMORY)) instanceof ArrayBlockEntity array) memory = array.memoryId();
        if (record.powered && !current.machine.matchesInstalled(current.structure.wall().hardware(), current.controller.romId(),
                current.controller.shardTier(), memory, disk, current.structure.key())) {
            current.machine.powerOff("Powered off: mainframe hardware or bindings changed"); current.sampled = false;
        }
        record.structureKey = current.structure.key();
        if (!record.powered) record.hardware = current.structure.wall().hardware();
        if (current.level().getBestNeighborSignal(current.structure.part(Hardware.POWER)) == 0) {
            if (record.powered) current.machine.powerOff("Powered off");
            else record.status = "Powered off";
            record.faulted = false; record.paused = false; current.sampled = false; visuals(current, false); return;
        }
        if (!record.powered && !record.faulted) {
            current.sampled = false; record.paused = false;
            if (current.machine.powerOn(current.structure.wall().hardware(), current.controller.romId(), current.controller.shardTier(), memory, disk))
                recovery(current.controller, record);
        }
        samplePins(current); visuals(current, record.powered);
    }

    private static Structure inspect(ServerLevel level, ControllerBlockEntity controller) {
        var facing = WallLayout.Facing.valueOf(controller.getBlockState().getValue(MachineBlock.FACING).name());
        BlockPos position = controller.getBlockPos();
        var wall = WallLayout.find(new WallLayout.Pos(position.getX(), position.getY(), position.getZ()), facing,
                candidate -> wallCell(level, candidate)).orElse(null);
        if (wall == null) return null;
        var parts = new EnumMap<Hardware, List<BlockPos>>(Hardware.class);
        var signature = new StringBuilder(wall.facing().name());
        var blocks = new ArrayList<MachineBlock>();
        for (var cell : wall.positions()) {
            var blockPos = new BlockPos(cell.x(), cell.y(), cell.z());
            var block = (MachineBlock) level.getBlockState(blockPos).getBlock(); blocks.add(block);
            Hardware hardware = block.hardware();
            signature.append(';').append(cell.x()).append(',').append(cell.y()).append(',').append(cell.z())
                    .append(':').append(hardware == null ? "super_structure" : hardware.id);
            if (hardware != null) parts.computeIfAbsent(hardware, ignored -> new ArrayList<>()).add(blockPos);
        }
        parts.replaceAll((hardware, positions) -> List.copyOf(positions)); return new Structure(wall, Map.copyOf(parts), signature.toString(), List.copyOf(blocks));
    }

    private static WallLayout.Cell wallCell(ServerLevel level, WallLayout.Pos position) {
        var blockPos = new BlockPos(position.x(), position.y(), position.z());
        if (!level.hasChunkAt(blockPos)) return null;
        var state = level.getBlockState(blockPos);
        if (!(state.getBlock() instanceof MachineBlock block)
                || block.hardware() == null && block != ModContent.BLOCKS.get("super_structure")) return null;
        return new WallLayout.Cell(block.hardware(), WallLayout.Facing.valueOf(state.getValue(MachineBlock.FACING).name()));
    }

    public String structureDiagnostics(ControllerBlockEntity controller) {
        var level = (ServerLevel) controller.getLevel();
        var position = controller.getBlockPos();
        var facing = WallLayout.Facing.valueOf(controller.getBlockState().getValue(MachineBlock.FACING).name());
        var inspection = WallLayout.diagnose(new WallLayout.Pos(position.getX(), position.getY(), position.getZ()), facing,
                candidate -> wallCell(level, candidate));
        var detected = new ArrayList<String>();
        for (var part : Hardware.values()) if (inspection.counts().getOrDefault(part, 0) > 0)
            detected.add(part.displayName + " × " + inspection.counts().get(part));
        String inventory = "\nDetected components:\n" + String.join("\n", detected);
        if (inspection.layout().isPresent()) return "Structure: VALID — 7 × 3 × 1; all required components and maximum counts satisfied." + inventory;
        var origin = inspection.origin();
        var text = new StringBuilder("Structure: INVALID\nDimension: ").append(level.dimension().identifier())
                .append("\nChecking closest matching 7 × 3 × 1 area from ").append(coordinates(origin))
                .append(" to ").append(coordinates(origin.offset(facing, 6, 2))).append(", facing ").append(facing).append('.');
        for (var issue : inspection.issues()) {
            if (issue.kind() == WallLayout.IssueKind.BLOCK) {
                for (var cell : issue.positions()) {
                    var pos = new BlockPos(cell.x(), cell.y(), cell.z());
                    text.append("\n- ");
                    if (!level.hasChunkAt(pos)) text.append("Unloaded wall position");
                    else {
                        var state = level.getBlockState(pos);
                        if (state.isAir()) text.append("Missing wall block");
                        else text.append("Invalid wall block: ").append(state.getBlock().getName().getString())
                                .append("; use a mainframe component or Super Structure");
                    }
                    text.append(" at ").append(coordinates(cell));
                }
            } else {
                text.append("\n- ").append(issue.message());
                if (!issue.positions().isEmpty()) {
                    text.append("\n  At ");
                    text.append(String.join("; ", issue.positions().stream().map(ServerMachines::coordinates).toList()));
                }
            }
        }
        if (inspection.issues().stream().anyMatch(issue -> issue.kind() == WallLayout.IssueKind.COMPONENT && issue.positions().isEmpty()))
            text.append("\nRequired components may go anywhere in this area; replace Super Structure or fill an empty wall position.");
        return text.append(inventory).toString();
    }

    private static String coordinates(WallLayout.Pos pos) { return "(" + pos.x() + ", " + pos.y() + ", " + pos.z() + ")"; }

    private void samplePins(Loaded current) {
        var bridge = current.machine.bridge();
        if (bridge == null || !current.machine.record.powered || current.structure == null) return;
        var level = current.level();
        bridge.submitResetPin(level.getBestNeighborSignal(current.structure.part(Hardware.RESET)) > 0, current.sampled);
        bridge.submitNmiPin(level.getBestNeighborSignal(current.structure.part(Hardware.NMI)) > 0, current.sampled);
        bridge.submitTestPin(level.getBestNeighborSignal(current.structure.part(Hardware.TEST)) > 0); current.sampled = true;
    }

    private void visuals(Loaded current, boolean powered) {
        boolean running = powered && !current.machine.record.paused;
        if (Objects.equals(current.visualPower, powered) && current.visualRunning == running || current.structure == null) return;
        for (var position : current.structure.wall().positions()) {
            var blockPos = new BlockPos(position.x(), position.y(), position.z());
            var state = current.level().getBlockState(blockPos);
            if (state.getBlock() instanceof MachineBlock block) {
                var next = state.setValue(MachineBlock.POWERED, powered);
                if (block.hardware() == Hardware.CONTROLLER) next = next.setValue(MachineBlock.ACTIVE, running);
                if (state != next) current.level().setBlock(blockPos, next, 2);
            }
        }
        current.visualPower = powered;
        current.visualRunning = running;
    }

    public void powerOff(long id, String reason) {
        if (stopping) return;
        boolean found = false;
        for (var current : loaded.values()) if (current.machine.record.id == id) {
            current.machine.powerOff(reason); current.sampled = false; visuals(current, false); found = true;
        }
        if (!found) records.mainframe(id).ifPresent(record -> {
            record.powered = false; record.status = reason; records.shared.powerOff(id); disks.release(id);
        });
    }

    public dervogel101.de.i86.runtime.ShardSpeed shardSpeed(int tier) { return config.speed(Math.max(1, tier)); }
    public MainframeMachine machine(long id) {
        return loaded.values().stream().filter(current -> current.machine.record.id == id && !current.controller.isRemoved())
                .map(current -> current.machine).findFirst().orElse(null);
    }
    public dervogel101.de.i86.runtime.HardwareProfile portHardware(long id) {
        for (var current : loaded.values()) if (current.machine.record.id == id)
            return current.structure == null ? new dervogel101.de.i86.runtime.HardwareProfile(Map.of()) : current.structure.wall().hardware();
        return records.mainframe(id).orElseThrow().hardware;
    }
    public boolean canEdit(long id, UUID player) {
        return !stopping && records.mainframe(id).map(record -> !record.powered && !record.conflicted() && leases.canControl(id, player)).orElse(false);
    }
    public Optional<WorldRecords.Mainframe> mainframeForPart(ServerLevel level, BlockPos part) {
        for (var current : loaded.values()) if (belongs(current, level, part)) return Optional.of(current.machine.record);
        return Optional.empty();
    }
    public void rename(ControllerBlockEntity controller, String name, UUID player) {
        if (name.isBlank() || name.length() > 128) throw new IllegalArgumentException("Name must contain 1–128 characters");
        if (!canEdit(controller.id(), player)) throw new IllegalStateException("Power off the mainframe and obtain debugger control before renaming it");
        var record = records.mainframe(controller.id()).orElseThrow();
        controller.setComponents(net.minecraft.core.component.DataComponentMap.builder().addAll(controller.components())
                .set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(name)).build());
        controller.setChanged();
        record.name = name;
        recovery(controller, record);
    }
    public String terminal(long id) {
        var current = machine(id);
        if (current != null && current.bridge() != null) return current.bridge().terminalText();
        var record = records.mainframe(id).orElseThrow();
        if (record.execution.length == 0) return "";
        try {
            return dervogel101.de.CPU8086.restoreState(record.execution, new dervogel101.de.state.SharedMemoryRegion()).hostBridge().terminalText();
        } catch (IOException invalid) { throw new IllegalStateException("Saved terminal is unavailable: " + invalid.getMessage()); }
    }
    public boolean canConfigurePart(ServerLevel level, BlockPos part, UUID player) {
        for (var current : loaded.values()) if (belongs(current, level, part)
                && !canEdit(current.machine.record.id, player)) return false;
        return true;
    }
    private static boolean belongs(Loaded current, ServerLevel level, BlockPos part) {
        if (current.level() != level) return false;
        if (current.controller.getBlockPos().equals(part)) return true;
        if (current.structure != null && current.structure.contains(part)) return true;
        var existing = inspect(level, current.controller);
        if (existing != null && existing.contains(part)) return true;
        return current.machine.record.structureKey.contains(";" + part.getX() + "," + part.getY() + "," + part.getZ() + ":");
    }
    public void sharedSelected(ServerLevel level, BlockPos part, String id) {
        records.shared.region(id);
        for (var current : loaded.values()) if (belongs(current, level, part)) {
            current.machine.record.sharedId = id; current.machine.record.sharedReselection = false;
        }
    }
    public void inventoryChanged(ControllerBlockEntity controller) {
        if (stopping) return;
        var current = loaded.get(controller);
        if (current == null) return;
        if (current.machine.record.powered) current.machine.powerOff("Powered off: installed ROM or shard changed");
        current.machine.record.faulted = false;
        markChipValidity(controller); recovery(controller, current.machine.record);
    }
    private void markChipValidity(ControllerBlockEntity controller) {
        var chip = controller.inventory.getItem(0);
        if (!chip.isEmpty()) {
            boolean invalid = controller.romId() != null && !roms.available(controller.romId());
            if (!Objects.equals(chip.get(ModComponents.ROM_INVALID), invalid)) { chip.set(ModComponents.ROM_INVALID, invalid); controller.setChanged(); }
        }
    }
    private void recovery(ControllerBlockEntity controller, WorldRecords.Mainframe record) {
        if (record.conflicted()) return;
        try { record.recovery = ItemRecovery.save(controller.recoveryItem(), controller.getLevel().registryAccess()); }
        catch (IOException failure) { LOGGER.error("Cannot retain controller recovery item {}", record.id, failure); }
    }
    public byte[] snapshotDisk(UUID id) {
        for (var current : loaded.values()) if (current.machine.record.powered && id.equals(current.machine.record.diskId)) current.machine.capture();
        return records.hasDisk(id) ? records.disk(id) : null;
    }
    private void save() {
        if (stopping && stopSaveFailed) return;
        for (var current : loaded.values()) current.machine.stopWork();
        try {
            for (var current : loaded.values()) current.machine.capture();
            store.save(records);
            for (var current : loaded.values()) {
                if (current.machine.record.hardware.has(Hardware.NVRAM)) current.machine.activity(Hardware.NVRAM, 4);
                if (current.machine.record.hardware.has(Hardware.DISK)) current.machine.activity(Hardware.DISK, 4);
                if (current.structure != null) for (var hardware : List.of(Hardware.NVRAM, Hardware.DISK))
                    for (var position : current.structure.parts().getOrDefault(hardware, List.of())) indicators.failure(current.level(), position, false);
            }
        } catch (IOException | RuntimeException failure) {
            if (stopping) stopSaveFailed = true;
            for (var current : loaded.values()) if (current.structure != null)
                for (var hardware : List.of(Hardware.NVRAM, Hardware.DISK))
                    for (var position : current.structure.parts().getOrDefault(hardware, List.of())) indicators.failure(current.level(), position, true);
            LOGGER.error("Mainframe world save failed; previous snapshot was retained", failure);
        }
    }
    public static WorldRecords.Location location(ServerLevel level, BlockPos position) {
        return new WorldRecords.Location(level.dimension().identifier().toString(), position.getX(), position.getY(), position.getZ());
    }
}

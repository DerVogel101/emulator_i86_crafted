package dervogel101.de.emulator_i86_crafted.server;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.arguments.*;
import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.block.ArrayBlockEntity;
import dervogel101.de.emulator_i86_crafted.server.storage.ItemRecovery;
import dervogel101.de.emulator_i86_crafted.server.storage.RomLibrary;
import dervogel101.de.state.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.phys.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;
import static net.minecraft.commands.Commands.*;

public final class MachineCommands {
    private MachineCommands() {}
    public static void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(literal("i86").executes(context -> help(context.getSource()))
            .then(literal("list").requires(MachineCommands::admin).executes(context -> list(context, 1, false))
                .then(argument("page", IntegerArgumentType.integer(1)).executes(context -> list(context, IntegerArgumentType.getInteger(context, "page"), false))))
            .then(literal("disks").requires(MachineCommands::admin).executes(context -> list(context, 1, true))
                .then(argument("page", IntegerArgumentType.integer(1)).executes(context -> list(context, IntegerArgumentType.getInteger(context, "page"), true))))
            .then(literal("recover").requires(MachineCommands::admin).executes(context -> help(context.getSource()))
                .then(literal("controller").then(recipient(argument("id", LongArgumentType.longArg(1)), context -> checked(context, () -> {
                    var source = context.getSource(); var machines = ServerMachines.get(source.getServer());
                    var record = machines.records.mainframe(LongArgumentType.getLong(context, "id")).orElseThrow(() -> new IllegalArgumentException("Unknown mainframe ID"));
                    if (!record.controllers.isEmpty()) throw new IllegalStateException("Controller is still placed at " + record.controllers);
                    if (record.recovery.length == 0) throw new IllegalStateException("No retained controller item");
                    give(context, ItemRecovery.load(record.recovery, source.getServer().registryAccess())); return 1;
                }))))
                .then(literal("disk").then(recipient(argument("id", UuidArgument.uuid()), context -> checked(context, () -> {
                    var source = context.getSource(); var machines = ServerMachines.get(source.getServer());
                    UUID id = UuidArgument.getUuid(context, "id"); byte[] bytes = machines.snapshotDisk(id);
                    if (bytes == null) throw new IllegalArgumentException("Unknown disk ID");
                    var stack = new ItemStack(ModContent.ITEMS.get("harddisk_array"));
                    stack.set(ModComponents.DISK_ID, id); stack.set(ModComponents.DISK_CONTENTS, ByteBuffer.wrap(bytes).asReadOnlyBuffer()); give(context, stack); return 1;
                })))))
            .then(literal("rom").requires(MachineCommands::admin)
                .then(literal("list").executes(context -> romList(context, 1))
                    .then(argument("page", IntegerArgumentType.integer(1)).executes(context -> romList(context, IntegerArgumentType.getInteger(context, "page")))))
                .then(literal("chip").then(recipient(argument("id", UuidArgument.uuid()), context -> checked(context, () -> {
                    var source = context.getSource(); var machines = ServerMachines.get(source.getServer());
                    var entry = machines.roms.entry(UuidArgument.getUuid(context, "id")).orElseThrow(() -> new IllegalArgumentException("Unknown ROM ID"));
                    var chip = RomChips.create(entry, !machines.roms.available(entry.id()));
                    give(context, chip); return 1;
                }))))
                .then(literal("delete").then(argument("id", UuidArgument.uuid()).executes(context -> checked(context, () -> {
                    var source = context.getSource(); var machines = ServerMachines.get(source.getServer()); UUID id = UuidArgument.getUuid(context, "id");
                    machines.roms.delete(id);
                    for (var record : machines.records.mainframes()) if (id.equals(record.romId)) machines.powerOff(record.id, "Powered off: ROM deleted: " + id);
                    source.sendSuccess(() -> Component.literal("Deleted ROM " + id), false); return 1;
                }))))
                .then(literal("import").then(argument("file", StringArgumentType.string()).then(argument("name", StringArgumentType.string())
                    .executes(context -> importRom(context, ""))
                    .then(argument("description", StringArgumentType.greedyString()).executes(context -> importRom(context, StringArgumentType.getString(context, "description"))))))))
            .then(literal("memory")
                .then(literal("list").requires(MachineCommands::admin).executes(context -> memoryList(context, 1))
                    .then(argument("page", IntegerArgumentType.integer(1)).executes(context -> memoryList(context, IntegerArgumentType.getInteger(context, "page")))))
                .then(literal("select").then(argument("id", StringArgumentType.word()).executes(context -> checked(context, () -> {
                    var player = context.getSource().getPlayerOrException(); var hit = player.pick(6, 1, false);
                    if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK
                            || !(player.level().getBlockEntity(block.getBlockPos()) instanceof ArrayBlockEntity array) || array.isDisk())
                        throw new IllegalArgumentException("Look at an Ender Tunnel Array within six blocks");
                    array.memoryId(StringArgumentType.getString(context, "id"), player.getUUID());
                    context.getSource().sendSuccess(() -> Component.literal("Memory ID: " + array.memoryId()), false); return 1;
                }))))
                .then(literal("delete").requires(MachineCommands::admin).then(argument("id", StringArgumentType.word()).executes(context -> checked(context, () -> {
                    var source = context.getSource(); var machines = ServerMachines.get(source.getServer());
                    String id = SharedMemoryGroups.normalize(StringArgumentType.getString(context, "id")); machines.records.shared.delete(id);
                    for (var record : machines.records.mainframes()) if (record.sharedId.equals(id)) {
                        machines.powerOff(record.id, "Powered off: shared region deleted; select a memory ID"); record.sharedReselection = true;
                    }
                    source.sendSuccess(() -> Component.literal("Shared region is now absent: " + id), false); return 1;
                })))))));
    }

    private static boolean admin(CommandSourceStack source) { return source.permissions().hasPermission(Permissions.COMMANDS_ADMIN); }
    private interface Action { int run() throws Exception; }
    private static int checked(CommandContext<CommandSourceStack> context, Action action) {
        try { return action.run(); }
        catch (Exception failure) { context.getSource().sendFailure(Component.literal(Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()))); return 0; }
    }
    private static <T> RequiredArgumentBuilder<CommandSourceStack, T> recipient(
            RequiredArgumentBuilder<CommandSourceStack, T> id, Command<CommandSourceStack> action) {
        return id.executes(action).then(argument("player", EntityArgument.player()).executes(action));
    }
    private static void give(CommandContext<CommandSourceStack> context, ItemStack stack) throws Exception {
        var source = context.getSource();
        var player = context.getNodes().stream().anyMatch(node -> node.getNode().getName().equals("player"))
                ? EntityArgument.getPlayer(context, "player") : source.getPlayerOrException();
        Component name = stack.getHoverName().copy();
        var inventory = player.getInventory();
        // Creative inventory insertion discards leftovers when full.
        if (inventory.getFreeSlot() < 0 && inventory.getSlotWithRemainingSpace(stack) < 0 || !inventory.add(stack))
            player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY);
        source.sendSuccess(() -> Component.literal("Recovered " + name.getString() + " for " + player.getScoreboardName()), false);
    }
    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("/i86 memory select <id> — select the array you are looking at (CPU off)."), false);
        if (admin(source)) {
            source.sendSuccess(() -> Component.literal("/i86 list [page]; /i86 disks [page]; /i86 rom list [page]; /i86 memory list [page]"), false);
            source.sendSuccess(() -> Component.literal("/i86 recover controller <id> [player]; /i86 recover disk <uuid> [player]; /i86 rom chip <uuid> [player]. Console commands require an online player. A full inventory drops the item at that player."), false);
            source.sendSuccess(() -> Component.literal("/i86 rom import <file> <name> [description]; /i86 rom delete <uuid>; /i86 memory delete <id>. Recovery preserves identities; placing duplicates disables conflicting devices."), false);
        }
        return 1;
    }
    private static int list(CommandContext<CommandSourceStack> context, int page, boolean disks) {
        var machines = ServerMachines.get(context.getSource().getServer());
        return disks ? page(context.getSource(), machines.records.diskIds().stream().sorted().toList(), page,
                id -> id + " — data=" + machines.records.hasDisk(id) + " — referenced by " + machines.records.mainframes().stream()
                        .filter(record -> id.equals(record.diskId)).map(record -> record.displayName() + " at " + record.controllers).toList())
            : page(context.getSource(), machines.records.mainframes(), page,
                record -> record.displayName() + " — " + record.status + " — " + record.controllers
                        + " — recovery=" + (record.recovery.length > 0) + "; NVRAM=" + record.nvram.length + " bytes; ROM="
                        + (record.romId == null ? "none" : record.romId + " available=" + machines.roms.available(record.romId)));
    }
    private static int romList(CommandContext<CommandSourceStack> context, int page) {
        var library = ServerMachines.get(context.getSource().getServer()).roms;
        return page(context.getSource(), library.entries(), page,
                entry -> entry.id() + " — " + entry.name() + " — " + entry.description() + " — uploaded by " + entry.uploaderName()
                        + (entry.uploaderId() == null ? "" : " [" + entry.uploaderId() + "]") + " — available=" + library.available(entry.id()));
    }
    private static int memoryList(CommandContext<CommandSourceStack> context, int page) {
        var machines = ServerMachines.get(context.getSource().getServer());
        return page(context.getSource(), machines.records.shared.snapshot().keySet().stream().sorted().toList(), page,
                id -> id + " — data=65536 bytes — powered CPUs=" + machines.records.mainframes().stream()
                        .filter(record -> record.powered && record.sharedId.equals(id) && record.hardware.has(dervogel101.de.i86.runtime.Hardware.SHARED_MEMORY)).count());
    }
    private static <T> int page(CommandSourceStack source, List<T> entries, int page, Function<T, String> format) {
        long start = (page - 1L) * 20;
        source.sendSuccess(() -> Component.literal(entries.size() + " entries; page " + page), false);
        if (start >= entries.size()) return 0;
        int end = (int) Math.min(entries.size(), start + 20);
        for (T entry : entries.subList((int) start, end)) source.sendSuccess(() -> Component.literal(format.apply(entry)), false);
        return end - (int) start;
    }
    private static int importRom(CommandContext<CommandSourceStack> context, String description) {
        return checked(context, () -> {
            var source = context.getSource(); var machines = ServerMachines.get(source.getServer());
            String filename = StringArgumentType.getString(context, "file");
            byte[] bytes = RomLibrary.readUpload(machines.folder.resolve("uploads"), filename);
            var player = source.getEntity() instanceof ServerPlayer uploader ? uploader : null;
            var entry = machines.roms.upload(bytes, StringArgumentType.getString(context, "name"), description,
                    player == null ? null : player.getUUID(), player == null ? "Server import" : player.getScoreboardName());
            source.sendSuccess(() -> Component.literal("Imported " + entry.name() + " [" + entry.id() + "]"), false); return 1;
        });
    }
}

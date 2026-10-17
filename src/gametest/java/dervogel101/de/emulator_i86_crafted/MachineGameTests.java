package dervogel101.de.emulator_i86_crafted;

import dervogel101.de.emulator_i86_crafted.block.*;
import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.i86.runtime.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.*;
import java.util.*;

public final class MachineGameTests {
    @GameTest public void pinLightsFollowIncomingRedstoneWithoutAValidWall(GameTestHelper test) {
        var ids = List.of("power_supply", "reset_port", "nmi_port", "test_port");
        for (int i = 0; i < ids.size(); i++) {
            var pos = new BlockPos(2 + i % 2 * 4, 2, 2 + i / 2 * 4);
            test.setBlock(pos, ModContent.BLOCKS.get(ids.get(i)));
            test.setBlock(pos.above(), Blocks.REDSTONE_BLOCK);
            var state = test.getLevel().getBlockState(test.absolutePos(pos));
            test.assertTrue(state.getValue(MachineBlock.ACTIVE), "Incoming pin lights independently of a booted CPU");
            test.assertFalse(state.getValue(MachineBlock.POWERED), "A redstone pin does not imply successful startup");
            test.setBlock(pos.above(), Blocks.AIR);
            test.assertFalse(test.getLevel().getBlockState(test.absolutePos(pos)).getValue(MachineBlock.ACTIVE),
                    "Removing redstone extinguishes pin light");
        }
        test.succeed();
    }

    @GameTest(maxTicks = 180) public void redstonePinsHandleHaltWaitAndManualResetStartsExecution(GameTestHelper test) throws Exception {
        var wall = new Wall(test, 28672);
        ((ArrayBlockEntity) test.getLevel().getBlockEntity(wall.part(Hardware.SHARED_MEMORY)))
                .memoryId("pin_validation", wall.player.getUUID());
        byte[] image = new byte[256 * 1024];
        // Count boots, WAIT on TEST, then HLT; NMI returns to WAIT/HLT with IF clear.
        byte[] program = {(byte) 0xBC, 0, (byte) 0x80, (byte) 0xFF, 6, 0, 0x10, (byte) 0x9B, (byte) 0xF4, (byte) 0xEB, (byte) 0xFC};
        System.arraycopy(program, 0, image, image.length - 16, program.length);
        byte[] handler = {(byte) 0xFF, 6, 2, 0x10, (byte) 0xCF};
        System.arraycopy(handler, 0, image, 0x100, handler.length);
        UUID rom;
        try { rom = wall.machines.roms.upload(image, "Pin validation", "").id(); }
        catch (RomLibrary.DuplicateRom duplicate) { rom = duplicate.existing.id(); }
        wall.controller.inventory.setItem(0, RomChips.create(wall.machines.roms.entry(rom).orElseThrow(), false));
        wall.controller.inventory.setItem(1, new net.minecraft.world.item.ItemStack(ModContent.ITEMS.get("speed_shard_1")));
        var level = test.getLevel();
        var power = wall.part(Hardware.POWER).south();
        var reset = wall.part(Hardware.RESET).south();
        var nmi = wall.part(Hardware.NMI).south();
        var busy = wall.part(Hardware.TEST).south();
        level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        test.startSequence().thenWaitUntil(() -> {
            var machine = wall.machines.machine(wall.record().id);
            test.assertTrue(wall.record().powered && machine.onWorker(cpu -> cpu.isHalted()), "Running CPU reaches HLT");
            test.assertFalse(wall.record().paused, "HLT keeps the host scheduler running");
        }).thenExecute(() -> {
            wall.machines.machine(wall.record().id).onWorker(cpu -> {
                cpu.memory().write16(0, 8, 0x100); cpu.memory().write16(0, 10, 0xC000); return null;
            });
            level.setBlock(nmi, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        }).thenWaitUntil(() -> {
            test.assertTrue(wall.machines.machine(wall.record().id).onWorker(cpu ->
                    cpu.isHalted() && cpu.memory().read16(0, 0x1002) == 1), "NMI wakes HLT with IF clear and IRET resumes");
        }).thenIdle(3).thenExecute(() -> {
            test.assertValueEqual(wall.machines.machine(wall.record().id).onWorker(cpu -> cpu.memory().read16(0, 0x1002)), 1, "Held NMI does not retrigger");
            level.setBlock(nmi, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(reset, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        }).thenWaitUntil(() -> {
            test.assertTrue(wall.machines.machine(wall.record().id).onWorker(cpu ->
                    !cpu.isHalted() && cpu.registers().getCs() == 0xFFFF && cpu.registers().getIp() == 0), "Redstone RESET wakes HLT and holds the reset vector");
        }).thenExecute(() -> {
            level.setBlock(nmi, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(busy, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(reset, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }).thenWaitUntil(() -> {
            test.assertTrue(wall.machines.machine(wall.record().id).onWorker(cpu ->
                    !cpu.isHalted() && cpu.registers().getIp() == 7 && cpu.memory().read16(0, 0x1000) == 2
                            && cpu.memory().read16(0, 0x1002) == 1), "TEST high stalls WAIT; RESET preserves RAM and ignores NMI edges while held");
        }).thenExecute(() -> {
            level.setBlock(nmi, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(nmi, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        }).thenWaitUntil(() -> {
            test.assertTrue(wall.machines.machine(wall.record().id).onWorker(cpu ->
                    !cpu.isHalted() && cpu.registers().getIp() == 7 && cpu.memory().read16(0, 0x1002) == 2), "NMI interrupts WAIT and IRET retries it");
        }).thenExecute(() -> level.setBlock(busy, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)).thenWaitUntil(() -> {
            test.assertTrue(wall.machines.machine(wall.record().id).onWorker(cpu -> cpu.isHalted()), "TEST low releases WAIT to HLT");
        }).thenExecute(() -> level.setBlock(busy, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL)).thenIdle(3).thenExecute(() -> {
            var machine = wall.machines.machine(wall.record().id);
            test.assertTrue(machine.onWorker(cpu -> cpu.isHalted()), "TEST does not wake HLT");
            try { MachineDialogs.submit(wall.player, wall.action(wall.part(Hardware.RESET), 0)); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            test.assertTrue(!wall.record().paused && machine.onWorker(cpu ->
                    !cpu.isHalted() && cpu.registers().getCs() == 0xFFFF && cpu.registers().getIp() == 0), "Manual reset clears HLT and starts execution");
        }).thenWaitUntil(() -> {
            test.assertTrue(wall.machines.machine(wall.record().id).onWorker(cpu ->
                    !cpu.isHalted() && cpu.registers().getIp() == 7 && cpu.memory().read16(0, 0x1000) == 3), "Manual reset boots again without Run or Step and preserves TEST and RAM");
        }).thenExecute(() -> {
            level.setBlock(reset, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(reset, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }).thenWaitUntil(() -> {
            test.assertTrue(wall.machines.machine(wall.record().id).onWorker(cpu ->
                    !cpu.isHalted() && cpu.registers().getIp() == 7 && cpu.memory().read16(0, 0x1000) == 4), "A later redstone RESET also boots without Run or Step");
        }).thenExecute(() -> {
            var machine = wall.machines.machine(wall.record().id);
            machine.pause(true);
            try { MachineDialogs.submit(wall.player, wall.action(wall.part(Hardware.RESET), 0)); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            test.assertFalse(wall.record().paused, "Manual reset also starts a debugger-paused CPU");
        }).thenWaitUntil(() -> {
            test.assertTrue(wall.machines.machine(wall.record().id).onWorker(cpu ->
                    !cpu.isHalted() && cpu.registers().getIp() == 7 && cpu.memory().read16(0, 0x1000) == 5), "A paused CPU boots again after manual Reset");
        }).thenExecute(() -> {
            level.setBlock(busy, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }).thenWaitUntil(() -> {
            test.assertTrue(wall.machines.machine(wall.record().id).onWorker(cpu -> cpu.isHalted()), "Reset CPU reaches HLT again normally");
        }).thenExecute(() -> {
            for (var source : List.of(power, reset, nmi, busy)) level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            wall.close();
        }).thenSucceed();
    }

    @GameTest(maxTicks = 100) public void controllerLightsFollowRunningPausedAndOff(GameTestHelper test) throws Exception {
        var wall = new Wall(test, 24576);
        var displayed = new java.util.concurrent.atomic.AtomicInteger();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server == test.getLevel().getServer() && !wall.controller.isRemoved())
                displayed.set(wall.machines.indicators.state(test.getLevel(), wall.origin));
        });
        var shared = (ArrayBlockEntity) test.getLevel().getBlockEntity(wall.part(Hardware.SHARED_MEMORY));
        shared.memoryId("visual_states", wall.player.getUUID());
        byte[] image = new byte[256 * 1024];
        int reset = image.length - 16;
        image[reset] = 15; image[reset + 1] = 1; image[reset + 2] = (byte) 0xEB; image[reset + 3] = (byte) 0xFC;
        UUID rom;
        try { rom = wall.machines.roms.upload(image, "Visual state check", "").id(); }
        catch (RomLibrary.DuplicateRom duplicate) { rom = duplicate.existing.id(); }
        wall.controller.inventory.setItem(0, RomChips.create(wall.machines.roms.entry(rom).orElseThrow(), false));
        wall.controller.inventory.setItem(1, new net.minecraft.world.item.ItemStack(ModContent.ITEMS.get("speed_shard_1")));
        var source = wall.part(Hardware.POWER).south();
        test.getLevel().setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        test.startSequence().thenWaitUntil(() -> {
            var state = wall.controller.getBlockState();
            test.assertTrue(wall.record().powered && state.getValue(MachineBlock.ACTIVE), "Running CPU lights activity");
            test.assertValueEqual(displayed.get() & 3, IndicatorState.RUNNING, "Controller status is running");
            test.assertValueEqual(displayed.get() & 4, 0, "Controller LED 2 stays dark");
        }).thenExecute(() -> wall.record().paused = true).thenIdle(2).thenExecute(() -> {
            var state = wall.controller.getBlockState();
            test.assertTrue(state.getValue(MachineBlock.POWERED), "Paused CPU retains its power indicator");
            test.assertFalse(state.getValue(MachineBlock.ACTIVE), "Paused CPU has no running indicator");
            test.assertValueEqual(displayed.get() & 3, IndicatorState.IDLE, "Paused status is amber");
            test.getLevel().setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }).thenIdle(2).thenExecute(() -> {
            var state = wall.controller.getBlockState();
            test.assertFalse(state.getValue(MachineBlock.POWERED), "Shutdown extinguishes controller strips");
            test.assertValueEqual(displayed.get() & 3, IndicatorState.OFF, "Shutdown status is dark");
            test.assertFalse(state.getValue(MachineBlock.ACTIVE), "Shutdown extinguishes controller activity");
            wall.close();
        }).thenSucceed();
    }

    static final class Wall implements AutoCloseable {
        final GameTestHelper test;
        final ServerMachines machines;
        final BlockPos origin;
        final int chunkX, chunkZ;
        final ControllerBlockEntity controller;
        final ServerPlayer player;
        Wall(GameTestHelper test, int offset) {
            this.test = test; machines = ServerMachines.get(test.getLevel().getServer());
            var anchor = test.absolutePos(BlockPos.ZERO).offset(offset, 1, offset);
            chunkX = Math.floorDiv(anchor.getX(), 16); chunkZ = Math.floorDiv(anchor.getZ(), 16);
            origin = new BlockPos(chunkX * 16 + 1, anchor.getY(), chunkZ * 16 + 1);
            test.getLevel().setChunkForced(chunkX, chunkZ, true); test.getLevel().getChunk(chunkX, chunkZ);
            for (int i = 0; i < 21; i++) {
                var block = ModContent.BLOCKS.get(i < Hardware.values().length ? Hardware.values()[i].id : "super_structure");
                test.getLevel().setBlock(origin.offset(i % 7, i / 7, 0), block.defaultBlockState().setValue(MachineBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
            }
            controller = (ControllerBlockEntity) test.getLevel().getBlockEntity(origin); machines.attach(controller);
            player = test.makeMockServerPlayerInLevel(); near(origin);
        }
        BlockPos part(Hardware hardware) { int i = hardware.ordinal(); return origin.offset(i % 7, i / 7, 0); }
        void near(BlockPos pos) {
            player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            player.level().getChunkSource().move(player);
        }
        WorldRecords.Mainframe record() { return machines.records.mainframe(controller.id()).orElseThrow(); }
        CompoundTag action(BlockPos pos, int action) throws ReflectiveOperationException {
            near(pos); MachineDialogs.open(player, pos);
            return pending(action);
        }
        CompoundTag pending(int action) throws ReflectiveOperationException {
            var field = MachineDialogs.class.getDeclaredField("OPEN"); field.setAccessible(true);
            var session = ((Map<?, ?>) field.get(null)).get(player.getUUID());
            var nonce = session.getClass().getDeclaredMethod("nonce"); nonce.setAccessible(true);
            var tag = new CompoundTag(); tag.putString("token", nonce.invoke(session).toString()); tag.putString("action", Integer.toString(action));
            return tag;
        }
        @Override public void close() {
            machines.debugger.disconnect(player.getUUID());
            machines.leases.disconnect(player.getUUID()); MachineDialogs.disconnect(player.getUUID());
            player.level().getServer().getPlayerList().remove(player);
            for (int i = 0; i < 21; i++) test.getLevel().setBlock(origin.offset(i % 7, i / 7, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            test.getLevel().setChunkForced(chunkX, chunkZ, false);
        }
    }

    @GameTest
    public void controllerFormsRejectLeaseRacesReplaysAndReplacedBlocks(GameTestHelper test) throws ReflectiveOperationException {
        try (var wall = new Wall(test, 4096)) {
            var record = wall.record(); var other = UUID.randomUUID();
            var rename = wall.action(wall.origin, 1); rename.putString("name", "Blocked");
            wall.machines.leases.open(record.id, other);
            MachineDialogs.submit(wall.player, rename);
            test.assertValueEqual(record.name, "Mainframe", "A debugger acquired after opening the form must block edits");
            wall.machines.leases.close(record.id, other);
            rename = wall.action(wall.origin, 1); rename.putString("name", "Native test CPU");
            MachineDialogs.submit(wall.player, rename);
            test.assertValueEqual(record.name, "Native test CPU", "Controller form renames retained identity");
            test.assertValueEqual(wall.controller.recoveryItem().get(DataComponents.CUSTOM_NAME).getString(), record.name, "Mined item retains friendly name");
            rename.putString("name", "Replayed"); MachineDialogs.submit(wall.player, rename);
            test.assertValueEqual(record.name, "Native test CPU", "Submitted nonce cannot be replayed");
            var stale = wall.action(wall.origin, 1); stale.putString("name", "Stale");
            test.getLevel().setBlock(wall.origin, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            test.getLevel().setBlock(wall.origin, ModContent.BLOCKS.get("mainframe_controller").defaultBlockState(), Block.UPDATE_ALL);
            var replacement = (ControllerBlockEntity) test.getLevel().getBlockEntity(wall.origin);
            replacement.assignId(record.id); wall.machines.attach(replacement);
            MachineDialogs.submit(wall.player, stale);
            test.assertValueEqual(record.name, "Native test CPU", "Replacing a block invalidates its old form even with the same mainframe ID");
        }
        test.succeed();
    }

    @GameTest
    public void arraySelectionAndManualResetPowerCycleFollowMachineRules(GameTestHelper test) throws Exception {
        try (var wall = new Wall(test, 6144)) {
            var record = wall.record(); var sharedPos = wall.part(Hardware.SHARED_MEMORY);
            var select = wall.action(sharedPos, 0); select.putString("memory", "Native_Shared");
            MachineDialogs.submit(wall.player, select);
            var array = (ArrayBlockEntity) test.getLevel().getBlockEntity(sharedPos);
            test.assertValueEqual(array.memoryId(), "native_shared", "Array form normalizes selected region ID");
            test.assertTrue(wall.machines.records.shared.snapshot().containsKey("native_shared"), "Selecting a new named region creates it");
            UUID rom;
            byte[] image = new byte[256 * 1024]; image[0] = 42;
            try { rom = wall.machines.roms.upload(image, "Native component CPU", "").id(); }
            catch (RomLibrary.DuplicateRom duplicate) { rom = duplicate.existing.id(); }
            var disk = (ArrayBlockEntity) test.getLevel().getBlockEntity(wall.part(Hardware.DISK));
            var machine = wall.machines.machine(record.id);
            select = wall.action(sharedPos, 0); select.putString("memory", "changed");
            test.assertTrue(machine.powerOn(new HardwareProfile(Arrays.stream(Hardware.values()).collect(java.util.stream.Collectors.toMap(part -> part, part -> 1))), rom, 1, array.memoryId(), disk.diskId(wall.machines.records)), "Start server-owned CPU");
            machine.onWorker(cpu -> { cpu.memory().write8(0, 0x1234, 0x5A); return null; });
            select.putString("memory", "changed"); MachineDialogs.submit(wall.player, select);
            test.assertValueEqual(array.memoryId(), "native_shared", "A selection form opened before startup cannot alter running hardware");
            var reset = wall.action(wall.part(Hardware.RESET), 0); MachineDialogs.submit(wall.player, reset);
            test.assertTrue(!record.paused && record.powered, "Manual reset leaves CPU powered and running");
            test.assertValueEqual(machine.onWorker(cpu -> cpu.memory().read8(0, 0x1234)), 0x5A, "Reset preserves RAM");
            machine.onWorker(cpu -> {
                cpu.memory().write8(0, 8, 0); cpu.memory().write8(0, 9, 0x20);
                cpu.memory().write8(0, 10, 0); cpu.memory().write8(0, 11, 0);
                return null;
            });
            var nmi = wall.action(wall.part(Hardware.NMI), 0); MachineDialogs.submit(wall.player, nmi);
            test.assertValueEqual(machine.step(), dervogel101.de.CPU8086.StepOutcome.EVENT, "Manual NMI is delivered at a CPU boundary");
            test.assertValueEqual(machine.onWorker(cpu -> cpu.registers().getIp()), 0x2000, "Manual NMI uses vector 2");
            var remote = new WorldRecords.Location(test.getLevel().dimension().identifier().toString(), wall.origin.getX() + 100_000, wall.origin.getY(), wall.origin.getZ());
            wall.machines.records.ports.put(remote, PortBindings.Kind.REDSTONE,
                    new PortBindings.Settings(record.id, 0, "Unloaded port", true, RedstoneIrqMode.DISABLED, 0));
            var selectDevice = wall.action(wall.part(Hardware.INTERRUPTS), 0); MachineDialogs.submit(wall.player, selectDevice);
            var irq = wall.pending(0); irq.putString("irq", "ANALOG"); MachineDialogs.submit(wall.player, irq);
            test.assertValueEqual(wall.machines.records.ports.at(remote).orElseThrow().settings().irq(), RedstoneIrqMode.ANALOG, "IRQ manager edits unloaded ports while CPU is on");
            wall.machines.records.ports.remove(remote);
            machine.bridge().reportHostError("Saved terminal marker\n");
            var cycle = wall.action(wall.part(Hardware.POWER), 0); MachineDialogs.submit(wall.player, cycle);
            test.assertFalse(record.powered, "PSU cycle powers CPU off; physical redstone decides later startup");
            test.assertTrue(wall.machines.terminal(record.id).contains("Saved terminal marker"), "Power-off retains terminal history");
            var detached = new MainframeMachine(wall.machines.records, record, wall.machines.roms, new DiskClaims());
            test.assertTrue(record.execution.length > 0 && detached.bridge() == null, "Power-off captured a restorable snapshot");
            byte[] snapshot = record.execution;
            record.execution = new byte[]{1, 2, 3};
            MachineDialogs.open(wall.player, wall.part(Hardware.TERMINAL));
            record.execution = snapshot;
            MachineDialogs.open(wall.player, wall.part(Hardware.TERMINAL));
            MachineDialogs.open(wall.player, wall.part(Hardware.INTERRUPTS));
        }
        test.succeed();
    }
    @GameTest
    public void consoleRecoveryTargetsOnlinePlayersAndPreservesItemData(GameTestHelper test) throws Exception {
        try (var wall = new Wall(test, 8192)) {
            var server = test.getLevel().getServer();
            var dispatcher = server.getCommands().getDispatcher();
            var console = server.createCommandSourceStack().withSuppressedOutput();
            String targetTag = "i86_recovery_" + wall.player.getUUID();
            wall.player.addTag(targetTag);
            String target = "@a[tag=" + targetTag + ",limit=1]";
            wall.machines.rename(wall.controller, "Recovered CPU", wall.player.getUUID());
            UUID rom;
            byte[] image = new byte[256 * 1024]; image[0] = 78;
            try { rom = wall.machines.roms.upload(image, "Recovery test", "").id(); }
            catch (RomLibrary.DuplicateRom duplicate) { rom = duplicate.existing.id(); }
            final UUID expectedRom = rom;
            wall.controller.inventory.setItem(0, RomChips.create(wall.machines.roms.entry(rom).orElseThrow(), false));
            wall.controller.inventory.setItem(1, new net.minecraft.world.item.ItemStack(ModContent.ITEMS.get("speed_shard_2")));
            test.assertValueEqual(dispatcher.execute("i86 recover controller " + wall.controller.id() + " " + target, console), 0, "Still-placed controller recovery reports its location and rejects duplication");
            test.getLevel().setBlock(wall.origin, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            test.assertValueEqual(dispatcher.execute("i86 recover controller " + wall.controller.id() + " " + target, console), 1, "Console can recover a controller to an online player");
            var recovered = wall.player.getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> Objects.equals(stack.get(ModComponents.MAINFRAME_ID), wall.controller.id())).findFirst().orElseThrow();
            test.assertValueEqual(recovered.get(DataComponents.CUSTOM_NAME).getString(), "Recovered CPU", "Controller recovery preserves name");
            var inventory = recovered.get(DataComponents.CONTAINER).itemCopies().toList();
            test.assertValueEqual(inventory.getFirst().get(ModComponents.ROM_ID), rom, "Controller recovery preserves ROM chip");
            test.assertTrue(inventory.get(1).is(ModContent.ITEMS.get("speed_shard_2")), "Controller recovery preserves shard");
            UUID disk = wall.machines.records.createDisk();
            byte[] bytes = wall.machines.records.disk(disk); bytes[137] = 99; wall.machines.records.updateDisk(disk, bytes);
            wall.machines.records.shared.region("admin_listing");
            test.assertTrue(dispatcher.execute("i86 memory list", console) > 0, "Admin lists retained shared regions");
            test.assertTrue(dispatcher.execute("i86 list", console) > 0 && dispatcher.execute("i86 disks", console) > 0
                    && dispatcher.execute("i86 rom list", console) > 0, "Admin lists controller/disk/ROM data status");
            boolean listingDenied = false;
            try { dispatcher.execute("i86 memory list", console.withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS)); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { listingDenied = true; }
            test.assertTrue(listingDenied, "Ordinary players cannot enumerate shared regions");
            test.assertValueEqual(dispatcher.execute("i86 recover disk " + disk + " " + target, console), 1, "Console can recover a disk to an online player");
            var recoveredDisk = wall.player.getInventory().getNonEquipmentItems().stream()
                    .filter(stack -> disk.equals(stack.get(ModComponents.DISK_ID))).findFirst().orElseThrow();
            test.assertValueEqual(recoveredDisk.get(ModComponents.DISK_CONTENTS).get(137), (byte) 99, "Disk recovery carries latest retained bytes");
            test.assertValueEqual(dispatcher.execute("i86 rom chip " + rom + " " + target, console), 1, "Console can recreate ROM references");
            test.assertTrue(wall.player.getInventory().getNonEquipmentItems().stream().anyMatch(stack -> expectedRom.equals(stack.get(ModComponents.ROM_ID))), "Recovered chip points to existing immutable ROM");
            test.assertValueEqual(dispatcher.execute("i86 recover disk " + disk + " MissingPlayer", console), 0, "Offline target cannot receive an item");
            test.assertValueEqual(dispatcher.execute("i86 recover disk " + disk, console), 0, "Console must specify a player");
            boolean denied = false;
            try { dispatcher.execute("i86 recover disk " + disk + " " + target, console.withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS)); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { denied = true; }
            test.assertTrue(denied, "Recovery commands remain admin-only");
            var self = wall.player.createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS).withSuppressedOutput();
            test.assertValueEqual(dispatcher.execute("i86 rom chip " + rom, self), 1, "Player self-recovery remains available");
            wall.near(test.absolutePos(new BlockPos(1, 1, 1)));
            var slots = wall.player.getInventory().getNonEquipmentItems();
            for (int i = 0; i < slots.size(); i++) slots.set(i, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64));
            test.assertValueEqual(dispatcher.execute("i86 rom chip " + rom + " " + target, console), 1, "Full inventory falls back to item drop");
            var drops = java.util.stream.StreamSupport.stream(test.getLevel().getAllEntities().spliterator(), false)
                    .filter(entity -> entity instanceof net.minecraft.world.entity.item.ItemEntity && entity.distanceToSqr(wall.player) < 16)
                    .map(entity -> (net.minecraft.world.entity.item.ItemEntity) entity).toList();
            test.assertTrue(drops.stream().anyMatch(drop -> expectedRom.equals(drop.getItem().get(ModComponents.ROM_ID))), "Full-inventory recovery drops at the target player");
            for (var drop : drops) drop.discard();
        }
        test.succeed();
    }

    @GameTest(maxTicks = 600)
    public void poweredMainframeSuspendsAndResumesThroughNativeChunkUnload(GameTestHelper test) throws Exception {
        var wall = new Wall(test, 10240);
        var level = test.getLevel(); var record = wall.record();
        var source = wall.part(Hardware.POWER).north();
        Runnable cleanup = () -> { level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); wall.close(); };
        try {
            byte[] image = new byte[256 * 1024];
            image[image.length - 16] = 0x0F; image[image.length - 15] = 0x01;
            image[image.length - 14] = (byte) 0xEB; image[image.length - 13] = (byte) 0xFC;
            UUID rom;
            try { rom = wall.machines.roms.upload(image, "Native lifecycle loop", "Yield tick and jump").id(); }
            catch (RomLibrary.DuplicateRom duplicate) { rom = duplicate.existing.id(); }
            ((ArrayBlockEntity) level.getBlockEntity(wall.part(Hardware.SHARED_MEMORY))).memoryId("native_lifecycle", wall.player.getUUID());
            wall.controller.inventory.setItem(0, RomChips.create(wall.machines.roms.entry(rom).orElseThrow(), false));
            wall.controller.inventory.setItem(1, new net.minecraft.world.item.ItemStack(ModContent.ITEMS.get("speed_shard_1")));
            level.setBlock(source, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            int[] phase = {0}; long[] checkpoint = {0, 0}; byte[][] execution = {null}; MainframeMachine[] original = {null};
            test.onEachTick(() -> {
                try {
                    var machine = wall.machines.machine(record.id);
                    if (phase[0] == 0 && record.powered && machine != null && machine.instructions() > 0) {
                        original[0] = machine;
                        machine.onWorker(cpu -> {
                            cpu.registers().setAx(0x1357); cpu.memory().write8(0, 0x2345, 0x5A);
                            cpu.memory().write8(0xA000, 7, 0x4B); cpu.memory().write8(0xB000, 9, 0x6C);
                            byte[] disk = cpu.io().storageDevice().snapshot().bytes(); disk[137] = 99; cpu.io().storageDevice().load(disk);
                            return null;
                        });
                        wall.machines.debugger.open(wall.player, wall.controller);
                        machine.runTo(0, 0x200);
                        wall.near(test.absolutePos(BlockPos.ZERO));
                        level.setChunkForced(wall.chunkX, wall.chunkZ, false); phase[0] = 1;
                    } else if (phase[0] == 1 && machine == null && !level.hasChunkAt(wall.origin)) {
                        test.assertTrue(record.powered, "Chunk unload preserves logical power");
                        checkpoint[0] = record.instructions; checkpoint[1] = test.getTick(); execution[0] = record.execution.clone();
                        test.assertTrue(checkpoint[0] > 0 && execution[0].length > 0, "Unload captures running execution");
                        phase[0] = 2;
                    } else if (phase[0] == 2 && test.getTick() >= checkpoint[1] + 3) {
                        test.assertValueEqual(record.instructions, checkpoint[0], "Unloaded CPU runs no further steps");
                        test.assertTrue(Arrays.equals(record.execution, execution[0]), "Unloaded execution snapshot remains stable");
                        test.assertValueEqual(wall.machines.records.shared.region("native_lifecycle").read8(9), 0x6C, "Unloaded powered CPU retains shared bytes");
                        test.assertValueEqual(wall.machines.records.disk(record.diskId)[137], (byte) 99, "Unload captures latest disk data");
                        level.setChunkForced(wall.chunkX, wall.chunkZ, true); level.getChunk(wall.chunkX, wall.chunkZ); phase[0] = 3;
                    } else if (phase[0] == 3 && machine != null && machine != original[0] && machine.instructions() > checkpoint[0]) {
                        test.assertTrue(record.powered && !record.paused && !record.faulted, "Reload resumes powered execution");
                        test.assertValueEqual(machine.debugTarget(), new MainframeMachine.DebugTarget(0, 0x200), "A live debugger cursor target survives actual chunk unload/reload");
                        machine.onWorker(cpu -> {
                            test.assertValueEqual(cpu.registers().getAx(), 0x1357, "Reload preserves registers");
                            test.assertValueEqual(cpu.memory().read8(0, 0x2345), 0x5A, "Reload preserves RAM");
                            test.assertValueEqual(cpu.memory().read8(0xA000, 7), 0x4B, "Reload preserves NVRAM");
                            test.assertValueEqual(cpu.memory().read8(0xB000, 9), 0x6C, "Reload preserves shared bytes");
                            test.assertValueEqual(cpu.io().storageDevice().snapshot().bytes()[137], (byte) 99, "Reload reattaches latest disk bytes");
                            return null;
                        });
                        phase[0] = 4; cleanup.run(); test.succeed();
                    }
                } catch (RuntimeException | Error failure) { phase[0] = 4; cleanup.run(); throw failure; }
            });
            test.runAfterDelay(580, () -> {
                if (phase[0] != 4) {
                    int stopped = phase[0];
                    String details = record.status + "; machine loaded=" + (wall.machines.machine(record.id) != null)
                            + "; chunk=" + level.getChunkSource().getChunkDebugData(new net.minecraft.world.level.ChunkPos(wall.chunkX, wall.chunkZ))
                            + "; tickets=" + level.getChunkSource().getDataStorage().computeIfAbsent(net.minecraft.world.level.TicketStorage.TYPE)
                                    .getTickets(net.minecraft.world.level.ChunkPos.pack(wall.chunkX, wall.chunkZ));
                    phase[0] = 4; cleanup.run();
                    test.assertTrue(false, "Native chunk lifecycle stalled in phase " + stopped + "; " + details);
                }
            });
        } catch (Exception | Error failure) { cleanup.run(); throw failure; }
    }

}

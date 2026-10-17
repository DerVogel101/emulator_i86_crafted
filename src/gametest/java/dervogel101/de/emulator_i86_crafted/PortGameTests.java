package dervogel101.de.emulator_i86_crafted;

import dervogel101.de.emulator_i86_crafted.block.*;
import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.i86.runtime.*;
import dervogel101.de.state.CPUIO;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import java.util.*;

public final class PortGameTests {
    @GameTest public void signTargetsFollowAllSixMountingFaces(GameTestHelper test) {
        var machines = ServerMachines.get(test.getLevel().getServer());
        var record = retainedController(test);
        var center = new BlockPos(3, 3, 3);
        for (var facing : Direction.values()) {
            test.setBlock(center, ModContent.BLOCKS.get("sign_port").defaultBlockState().setValue(PortBlock.PORT_FACING, facing));
            var port = (PortBlockEntity) test.getLevel().getBlockEntity(test.absolutePos(center));
            port.settings(new PortBindings.Settings(record.id, 0, facing.name(), true, RedstoneIrqMode.DISABLED, 3));
            var signState = facing == Direction.UP ? Blocks.OAK_SIGN.defaultBlockState()
                    : facing == Direction.DOWN ? Blocks.OAK_HANGING_SIGN.defaultBlockState()
                    : Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing);
            var target = center.relative(facing);
            test.setBlock(target, signState);
            var sign = (SignBlockEntity) test.getLevel().getBlockEntity(test.absolutePos(target));
            record.signOutputs[0][0] = 0x10;
            record.signOutputs[0][1] = (byte) ('A' + facing.ordinal());
            machines.ports.tick(List.of());
            test.assertTrue(port.signTarget == sign, "The selected face addresses its actual adjacent sign: " + facing);
            test.assertValueEqual(sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString(),
                    Character.toString('A' + facing.ordinal()), "Text reaches the mounted sign: " + facing);
            test.assertTrue(port.getBlockState().getValue(MachineBlock.ACTIVE), "Mounted sign reports real synchronization activity");
            test.setBlock(target, Blocks.AIR);
            test.setBlock(center, Blocks.AIR);
        }
        test.succeed();
    }

    @GameTest public void indicatorsSeparatePortDirectionPowerAndSignal(GameTestHelper test) {
        var machines = ServerMachines.get(test.getLevel().getServer());
        var record = retainedController(test);
        record.powered = true; record.redstoneOutputs[0] = 9;
        var input = port(test, new BlockPos(2, 2, 2), "redstone_port", record.id, 0);
        input.settings(new PortBindings.Settings(record.id, 0, "Input", true, RedstoneIrqMode.ANALOG, 0));
        var output = port(test, new BlockPos(6, 2, 2), "redstone_port", record.id, 0);
        test.setBlock(new BlockPos(3, 2, 2), Blocks.REDSTONE_BLOCK);
        machines.ports.tick(List.of());
        test.assertTrue(input.getBlockState().getValue(PortBlock.INPUT), "Input arrow follows saved direction");
        test.assertFalse(output.getBlockState().getValue(PortBlock.INPUT), "Output arrow follows saved direction");
        for (var port : List.of(input, output)) {
            test.assertTrue(port.getBlockState().getValue(MachineBlock.POWERED), "Port has machine power");
            test.assertTrue(port.getBlockState().getValue(MachineBlock.ACTIVE), "Nonzero signal lights activity lens");
        }
        record.redstoneOutputs[0] = 0;
        test.setBlock(new BlockPos(3, 2, 2), Blocks.AIR);
        machines.ports.tick(List.of());
        for (var port : List.of(input, output)) {
            test.assertTrue(port.getBlockState().getValue(MachineBlock.POWERED), "Zero signal does not remove machine power");
            test.assertFalse(port.getBlockState().getValue(MachineBlock.ACTIVE), "Zero signal extinguishes activity lens");
        }
        record.powered = false;
        machines.ports.tick(List.of());
        test.assertFalse(input.getBlockState().getValue(MachineBlock.POWERED), "Power-off extinguishes power strips");
        test.assertFalse(output.getBlockState().getValue(MachineBlock.ACTIVE), "Power-off extinguishes activity");
        record.redstoneOutputs[0] = 9;
        machines.ports.tick(List.of());
        test.assertFalse(output.getBlockState().getValue(MachineBlock.POWERED), "Retained output does not imply CPU power");
        test.assertTrue(output.getBlockState().getValue(MachineBlock.ACTIVE), "Retained output still powers the physical target");
        test.succeed();
    }

    @GameTest public void sameIdInputAndOutputBothReachTheirOwnIoRegisters(GameTestHelper test) throws Exception {
        var machines = ServerMachines.get(test.getLevel().getServer());
        var record = retainedController(test);
        record.paused = true;
        record.redstoneOutputs[0] = 9;
        var input = port(test, new BlockPos(2, 2, 2), "redstone_port", record.id, 0);
        input.settings(new PortBindings.Settings(record.id, 0, "Input", true, RedstoneIrqMode.ANALOG, 0));
        var output = port(test, new BlockPos(6, 2, 2), "redstone_port", record.id, 0);
        test.setBlock(new BlockPos(3, 1, 2), Blocks.STONE);
        test.setBlock(new BlockPos(3, 2, 2), Blocks.DAYLIGHT_DETECTOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER, 7));
        UUID rom;
        try { rom = machines.roms.upload(new byte[256 * 1024], "Paired port check", "").id(); }
        catch (RomLibrary.DuplicateRom duplicate) { rom = duplicate.existing.id(); }
        var machine = new MainframeMachine(machines.records, record, machines.roms, new DiskClaims());
        try {
            test.assertTrue(machine.powerOn(record.hardware, rom, 1, "paired_ports", null), "Paired-port CPU powers on");
            record.redstoneOutputs[0] = 9;
            machines.ports.tick(List.of(machine));
            test.assertTrue(input.online && output.online, "Input 0 and output 0 must coexist without a slot conflict");
            test.assertValueEqual(output.signal(), 9, "Output keeps its own value");
            machine.onWorker(cpu -> {
                cpu.beforeInstruction();
                test.assertValueEqual(cpu.io().read8(0), 7, "Input reaches the even I/O register");
                test.assertValueEqual(cpu.hostBridge().redstoneIrqMode(0), RedstoneIrqMode.ANALOG,
                        "Output configuration must not replace the input IRQ mode");
                return null;
            });
        } finally { machine.powerOff("Paired-port check complete"); }
        test.succeed();
    }

    @GameTest public void redstoneWiresConnectOnlyToTheSelectedPortFace(GameTestHelper test) {
        var center = new BlockPos(3, 2, 3);
        var absolute = test.absolutePos(center);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            test.setBlock(center.offset(x, -1, z), Blocks.STONE);
            if (x != 0 || z != 0) test.setBlock(center.offset(x, 0, z), Blocks.REDSTONE_WIRE);
        }
        for (var facing : Direction.values()) {
            test.setBlock(center, ModContent.BLOCKS.get("redstone_port").defaultBlockState().setValue(PortBlock.PORT_FACING, facing));
            var state = test.getLevel().getBlockState(absolute);
            for (var query : Direction.values())
                test.assertValueEqual(state.shouldRedstoneWireConnectTo(test.getLevel(), absolute, query),
                        query == facing.getOpposite(), "Only the selected face accepts a wire connection: " + facing + "/" + query);
            test.assertFalse(state.shouldRedstoneWireConnectTo(test.getLevel(), absolute, null), "Unspecified faces do not connect");
            for (var side : Direction.Plane.HORIZONTAL) {
                var wire = test.getLevel().getBlockState(absolute.relative(side));
                var connection = wire.getValue(RedstoneWireBlock.PROPERTY_BY_DIRECTION.get(side.getOpposite()));
                test.assertValueEqual(connection.isConnected(), side == facing, "Actual wire shape follows port face " + facing);
            }
        }
        test.succeed();
    }

    private static PortBlockEntity port(GameTestHelper test, BlockPos pos, String type, long id, int slot) {
        test.setBlock(pos, ModContent.BLOCKS.get(type).defaultBlockState().setValue(PortBlock.PORT_FACING, Direction.EAST));
        var port = (PortBlockEntity) test.getLevel().getBlockEntity(test.absolutePos(pos));
        port.settings(new PortBindings.Settings(id, slot, "Port", false, RedstoneIrqMode.ANALOG, 3));
        return port;
    }
    private static WorldRecords.Mainframe retainedController(GameTestHelper test) {
        var machines = ServerMachines.get(test.getLevel().getServer()); var record = machines.records.allocate();
        record.hardware = HardwareProfile.complete();
        // An unloaded controller is represented by its retained placement and bridge cache.
        var far = test.absolutePos(BlockPos.ZERO).offset(100_000, 0, 100_000);
        record.controllers.add(ServerMachines.location(test.getLevel(), far));
        return record;
    }
    @GameTest public void portBlockDataRoundTripsAndDialogMixinLoads(GameTestHelper test) throws ClassNotFoundException {
        Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
        var record = retainedController(test);
        var original = port(test, new BlockPos(2, 2, 2), "redstone_port", record.id, 127);
        var data = original.saveWithFullMetadata(test.getLevel().registryAccess());
        var restored = BlockEntity.loadStatic(original.getBlockPos(), original.getBlockState(), data, test.getLevel().registryAccess());
        test.assertTrue(restored instanceof PortBlockEntity, "Saved external port must restore its entity type");
        test.assertValueEqual(((PortBlockEntity) restored).settings(), original.settings(), "Mainframe, slot, name, direction and IRQ settings must survive chunk serialization");
        test.succeed();
    }
    @GameTest public void analogInputsAndSilentReconnectReachCpuBridge(GameTestHelper test) throws Exception {
        var machines = ServerMachines.get(test.getLevel().getServer()); var record = retainedController(test);
        record.paused = true;
        var port = port(test, new BlockPos(2, 2, 2), "redstone_port", record.id, 0);
        port.settings(new PortBindings.Settings(record.id, 0, "Analog input", true, RedstoneIrqMode.ANALOG, 0));
        var source = new BlockPos(3, 2, 2);
        test.setBlock(source.below(), Blocks.STONE);
        test.setBlock(source, Blocks.DAYLIGHT_DETECTOR.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER, 7));
        java.util.UUID rom;
        try { rom = machines.roms.upload(new byte[256 * 1024], "Native input check", "Paused CPU").id(); }
        catch (RomLibrary.DuplicateRom duplicate) { rom = duplicate.existing.id(); }
        var machine = new MainframeMachine(machines.records, record, machines.roms, new DiskClaims());
        try {
            test.assertTrue(machine.powerOn(record.hardware, rom, 1, "native_input", null), "Input check CPU must power on");
            machines.ports.tick(List.of(machine));
            machine.onWorker(cpu -> {
                cpu.beforeInstruction();
                test.assertValueEqual(cpu.io().read8(0), 7, "Initial input strength must reach the bridge");
                test.assertValueEqual(cpu.io().read8(0xFF02) & 2, 0, "First sample must not generate an IRQ"); return null;
            });
            test.setBlock(source, Blocks.DAYLIGHT_DETECTOR.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER, 10));
            machines.ports.tick(List.of(machine));
            machine.onWorker(cpu -> {
                cpu.beforeInstruction();
                test.assertValueEqual(cpu.io().read8(0), 10, "Analog level change must reach the CPU");
                test.assertValueEqual(cpu.io().read8(0xFF02) & 2, 2, "Nonzero-to-nonzero analog change must queue an IRQ");
                cpu.io().write8(0xFF02, 1); cpu.beforeInstruction(); return null;
            });
            machines.ports.unload(port);
            test.setBlock(source, Blocks.DAYLIGHT_DETECTOR.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER, 3));
            machines.ports.register(port); machines.ports.tick(List.of(machine));
            machine.onWorker(cpu -> {
                cpu.beforeInstruction();
                test.assertValueEqual(cpu.io().read8(0), 3, "Reconnect replaces retained input");
                test.assertValueEqual(cpu.io().read8(0xFF02) & 2, 0, "Reconnect must not replay missed IRQs"); return null;
            });
        } finally { machine.powerOff("Native input check finished"); }
        test.succeed();
    }
    @GameTest(maxTicks = 100) public void poweredOffWallRetainsInstalledPortCapacity(GameTestHelper test) {
        var level = test.getLevel(); var machines = ServerMachines.get(level.getServer());
        var anchor = test.absolutePos(BlockPos.ZERO).offset(2048, 1, 2048);
        int chunkX = Math.floorDiv(anchor.getX(), 16), chunkZ = Math.floorDiv(anchor.getZ(), 16);
        var origin = new BlockPos(chunkX * 16 + 1, anchor.getY(), chunkZ * 16 + 1);
        var placed = new ArrayList<BlockPos>();
        Runnable cleanup = () -> {
            for (var position : placed) level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setChunkForced(chunkX, chunkZ, false);
        };
        level.setChunkForced(chunkX, chunkZ, true); level.getChunk(chunkX, chunkZ);
        try {
            var hardware = Hardware.values();
            for (int i = 0; i < 21; i++) {
                var position = origin.offset(i % 7, i / 7, 0); placed.add(position);
                var block = ModContent.BLOCKS.get(i < hardware.length ? hardware[i].id : "super_structure");
                level.setBlock(position, block.defaultBlockState().setValue(MachineBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
            }
            var controller = (ControllerBlockEntity) level.getBlockEntity(origin);
            machines.attach(controller);
            test.runAfterDelay(2, () -> {
                try {
                    var record = machines.records.mainframe(controller.id()).orElseThrow();
                    test.assertFalse(record.powered, "Unpowered PSU keeps the wall off");
                    test.assertValueEqual(machines.portHardware(record.id).redstoneCapacity(), 32, "Loaded wall GPIO capacity");
                    test.assertValueEqual(record.hardware.redstoneCapacity(), 32, "Installed capacity must remain recorded while off for later unload");
                    test.assertValueEqual(record.hardware.signCapacity(), 32, "Installed GPU capacity must remain recorded while off");
                    test.succeed();
                } finally { cleanup.run(); }
            });
        } catch (RuntimeException | Error failure) { cleanup.run(); throw failure; }
    }
    @GameTest public void directedRedstoneRetainsOutputWithoutLoadedCpu(GameTestHelper test) {
        var machines = ServerMachines.get(test.getLevel().getServer()); var record = retainedController(test);
        record.redstoneOutputs[5] = 9;
        var port = port(test, new BlockPos(2, 2, 2), "redstone_port", record.id, 5);
        machines.ports.tick(List.of());
        test.assertValueEqual(test.getLevel().getSignal(port.getBlockPos(), Direction.WEST), 9, "Output must power the faced target");
        for (Direction side : Direction.values()) if (side != Direction.WEST)
            test.assertValueEqual(test.getLevel().getSignal(port.getBlockPos(), side), 0, "Other sides must not emit");
        test.assertFalse(port.getBlockState().isRedstoneConductor(test.getLevel(), port.getBlockPos()), "Machine blocks must not couple adjacent pin inputs");
        test.assertValueEqual(PortBlock.PORT_FACING.getPossibleValues().size(), 6, "Ports must support all six target directions");
        test.succeed();
    }
    @GameTest public void duplicatesDisableBothAndResolveWhenRemoved(GameTestHelper test) {
        var machines = ServerMachines.get(test.getLevel().getServer()); var record = retainedController(test);
        record.redstoneOutputs[7] = 15;
        var first = port(test, new BlockPos(2, 2, 2), "redstone_port", record.id, 7);
        var second = port(test, new BlockPos(4, 2, 2), "redstone_port", record.id, 7);
        machines.ports.tick(List.of());
        test.assertTrue(first.status.startsWith("Duplicate") && second.status.startsWith("Duplicate"), "Both assignments must expose conflict locations");
        test.assertValueEqual(first.signal(), 0, "Conflicting output must be disabled");
        test.assertValueEqual(second.signal(), 0, "Conflicting output must be disabled");
        machines.ports.unload(second); machines.ports.tick(List.of());
        test.assertValueEqual(first.signal(), 0, "Unloading a duplicate must not hide its assignment");
        test.setBlock(new BlockPos(4, 2, 2), Blocks.AIR); machines.ports.tick(List.of());
        test.assertValueEqual(first.signal(), 15, "Removing the duplicate must restore the retained output");
        test.succeed();
    }
    @GameTest public void signWritesBothSidesAndOnlyDirtyUpdatesOverwriteEdits(GameTestHelper test) {
        var machines = ServerMachines.get(test.getLevel().getServer()); var record = retainedController(test);
        byte[] bytes = record.signOutputs[3]; bytes[0] = (byte) 0xE1; bytes[1] = (byte) 0x80; bytes[2] = (byte) 0xE4;
        bytes[64] = 0x10; bytes[65] = 'B';
        var port = port(test, new BlockPos(2, 2, 2), "sign_port", record.id, 3);
        test.setBlock(new BlockPos(3, 1, 2), Blocks.STONE); test.setBlock(new BlockPos(3, 2, 2), Blocks.OAK_SIGN);
        var sign = (SignBlockEntity) test.getLevel().getBlockEntity(test.absolutePos(new BlockPos(3, 2, 2)));
        sign.setAllowedPlayerEditor(UUID.randomUUID());
        machines.ports.tick(List.of());
        test.assertTrue(port.getBlockState().getValue(MachineBlock.ACTIVE), "Synchronizing sign text pulses the data lens");
        port.activityUntil = machines.records.gameTicks;
        machines.ports.tick(List.of());
        test.assertFalse(port.getBlockState().getValue(MachineBlock.ACTIVE), "Idle sign lens goes dark after its pulse");
        test.assertTrue(sign.isWaxed() && sign.getPlayerWhoMayEdit() == null, "CPU display signs are locked and revoke existing editors");
        var restoredSign = (SignBlockEntity) BlockEntity.loadStatic(sign.getBlockPos(), sign.getBlockState(),
                sign.saveWithFullMetadata(test.getLevel().registryAccess()), test.getLevel().registryAccess());
        test.assertTrue(restoredSign.isWaxed(), "Display lock survives saving and loading");
        test.assertValueEqual(sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString(), "€ä", "Windows-1252 front text");
        test.assertValueEqual(sign.getText(SignTextSlot.BACK).getMessages(false).getFirst().getString(), "B", "Back text must also be written");
        var font = new FontDescription.Resource(ModContent.id("monocraft"));
        test.assertValueEqual(sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getStyle().getFont(), font, "Named font");
        test.assertValueEqual(sign.getText(SignTextSlot.BACK).getMessages(false).getFirst().getStyle().getFont(), font, "Back side named font");
        var style = sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getStyle();
        test.assertTrue(style.isItalic() && style.isStrikethrough() && style.isUnderlined() && style.getColor() != null, "Format byte must reach Minecraft text styling");
        var edited = sign.getText(SignTextSlot.FRONT).asMutable().setLine(0, Component.literal("Player edit")).asImmutable();
        sign.setText(edited, SignTextSlot.FRONT); machines.ports.tick(List.of());
        test.assertValueEqual(sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString(), "Player edit", "Unchanged bridge state must not overwrite edits");
        var viewer = UUID.randomUUID();
        machines.ports.configure(port, new PortBindings.Settings(record.id, 3, "Renamed", false, RedstoneIrqMode.HIGH, 1), viewer);
        machines.ports.tick(List.of());
        test.assertValueEqual(sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString(), "Player edit", "Name and IRQ edits must not dirty sign output");
        var location = ServerMachines.location(test.getLevel(), port.getBlockPos());
        machines.ports.unload(port);
        machines.ports.configureInterrupts(location, record.id, viewer, RedstoneIrqMode.LOW, 2);
        try {
            var restored = WorldRecords.load(machines.records.save());
            test.assertValueEqual(restored.ports.at(location).orElseThrow().settings().signSides(), 2, "Unloaded IRQ settings survive world save");
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        test.assertValueEqual(port.settings().signSides(), 1, "Unloaded block still has stale chunk settings");
        machines.ports.register(port);
        test.assertValueEqual(port.settings().signSides(), 2, "Reload adopts authoritative registry settings");
        machines.ports.tick(List.of());
        record.acceptOutputs(new CPUIO.OutputChanges(Map.of(), Map.of(3, new CPUIO.SignSnapshot(bytes))));
        machines.ports.tick(List.of());
        test.assertTrue(port.getBlockState().getValue(MachineBlock.ACTIVE), "A new dirty sign write renews the data pulse");
        test.assertValueEqual(sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString(), "€ä", "Dirty bridge output must overwrite edits");
        test.succeed();
    }
}

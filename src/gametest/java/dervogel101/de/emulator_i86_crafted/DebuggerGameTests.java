package dervogel101.de.emulator_i86_crafted;

import dervogel101.de.emulator_i86_crafted.network.DebuggerPackets;
import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.i86.runtime.*;
import dervogel101.de.launcher.DebuggerProtocol;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.util.*;

public final class DebuggerGameTests {
    @GameTest public void keyboardDebuggerInputUsesLiveFifosAndEnforcesOwnership(GameTestHelper test) throws Exception {
        var responses = new HashMap<UUID, List<CustomPacketPayload>>();
        try (var wall = new MachineGameTests.Wall(test, 61440)) {
            var observer = test.makeMockServerPlayerInLevel(); observer.setPos(wall.player.position());
            var service = new ServerDebugger(wall.machines, (player, packet) -> responses.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>()).add(packet));
            try {
                byte[] image = new byte[256 * 1024];
                image[image.length - 16] = 15; image[image.length - 15] = 1;
                image[image.length - 14] = (byte)0xeb; image[image.length - 13] = (byte)0xfc;
                UUID rom;
                try { rom = wall.machines.roms.upload(image, "Keyboard debugger", "Yield without ACK").id(); }
                catch (RomLibrary.DuplicateRom duplicate) { rom = duplicate.existing.id(); }
                var counts = new EnumMap<Hardware, Integer>(HardwareProfile.complete().counts()); counts.put(Hardware.KEYBOARD, 1);
                var machine = wall.machines.machine(wall.record().id);
                test.assertTrue(machine.powerOn(new HardwareProfile(counts), rom, 1, "keyboard_debugger", wall.machines.records.createDisk()), "Start keyboard CPU");
                machine.pause(true); machine.bridge().submitKeyboardConnection(7, true);
                var ownerToken = service.open(wall.player, wall.controller);
                var observerToken = service.open(observer, wall.controller);
                send(service, wall.player, ownerToken, DebuggerProtocol.Action.SELECT_KEYBOARD, 7, 0, 0);
                send(service, observer, observerToken, DebuggerProtocol.Action.KEYBOARD_INPUT, 7, 'X', 0);
                test.assertTrue(failure(responses, observer).message().contains("Read only"), "Observers cannot type");
                send(service, wall.player, ownerToken, DebuggerProtocol.Action.KEYBOARD_INPUT, 8, 'X', 0);
                send(service, wall.player, ownerToken, DebuggerProtocol.Action.KEYBOARD_INPUT, 7, 27, 0);
                send(service, wall.player, ownerToken, DebuggerProtocol.Action.KEYBOARD_INPUT, 0, 'X', 0);
                test.assertValueEqual(machine.bridge().keyboardSnapshot(7).queue().length, 0, "Invalid and unbound inputs do not enter FIFO");
                for (int i = 0; i < 8; i++) send(service, wall.player, ownerToken, DebuggerProtocol.Action.KEYBOARD_INPUT, 7, 'A' + i, 0);
                send(service, wall.player, ownerToken, DebuggerProtocol.Action.KEYBOARD_INPUT, 7, 'Z', 0);
                test.assertTrue(failure(responses, wall.player).message().contains("full"), "Full FIFO rejects the ninth byte");
                machine.onWorker(cpu -> { cpu.beforeInstruction(); cpu.io().write8(0x817f, 1); cpu.beforeInstruction(); cpu.beforeTick(); return null; });
                send(service, wall.player, ownerToken, DebuggerProtocol.Action.RUN, 0, 0, 0);
                send(service, wall.player, ownerToken, DebuggerProtocol.Action.KEYBOARD_INPUT, 7, 0x92, 0);
                service.tick(1);
                var live = state(responses, wall.player);
                test.assertTrue(live.snapshot().running() && !live.detailed(), "Running CPU exposes no detailed CPU views");
                test.assertValueEqual(live.snapshot().devices().keyboardIndex(), 7, "Selected keyboard is retained");
                test.assertValueEqual(live.snapshot().devices().keyboard().queue().length, 8, "Running owner input reaches the FIFO");
                test.assertValueEqual(Byte.toUnsignedInt(live.snapshot().devices().keyboard().queue()[7]), 0x92, "Special key arrives intact");
                send(service, wall.player, ownerToken, DebuggerProtocol.Action.PAUSE, 0, 0, 0);
            } finally { service.shutdown(); }
        }
        test.succeed();
    }
    @GameTest
    public void authenticatedDebuggerSessionsEnforceOwnershipAndPublishServerState(GameTestHelper test) throws Exception {
        var responses = new HashMap<UUID, List<CustomPacketPayload>>();
        try (var wall = new MachineGameTests.Wall(test, 12288)) {
            var second = test.makeMockServerPlayerInLevel(); var third = test.makeMockServerPlayerInLevel();
            second.setPos(wall.player.position()); third.setPos(wall.player.position());
            var service = new ServerDebugger(wall.machines, (player, packet) -> responses.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>()).add(packet));
            var remote = new WorldRecords.Location(test.getLevel().dimension().identifier().toString(), wall.origin.getX() + 100_000, wall.origin.getY(), wall.origin.getZ());
            try {
                var record = wall.record(); record.paused = true;
                byte[] image = new byte[256 * 1024]; image[0] = 0x6D; image[image.length - 16] = 15; image[image.length - 15] = 1;
                image[image.length - 14] = (byte) 0xEB; image[image.length - 13] = (byte) 0xFC;
                UUID rom;
                try { rom = wall.machines.roms.upload(image, "Native debugger", "").id(); }
                catch (RomLibrary.DuplicateRom duplicate) { rom = duplicate.existing.id(); }
                var profile = new HardwareProfile(Arrays.stream(Hardware.values()).collect(java.util.stream.Collectors.toMap(part -> part, part -> 1)));
                var machine = wall.machines.machine(record.id);
                test.assertTrue(machine.powerOn(profile, rom, 1, "native_debugger", wall.machines.records.createDisk()), "Start server-owned CPU");
                machine.onWorker(cpu -> { cpu.memory().write8(0, 0x100, 0x33); return null; });
                machine.bridge().reportHostError("Native debugger output\n");
                UUID firstToken = service.open(wall.player, wall.controller);
                UUID secondToken = service.open(second, wall.controller), thirdToken = service.open(third, wall.controller);
                test.assertValueEqual(service.open(wall.player, wall.controller), firstToken, "Reopening the same window preserves its session and queue position");
                test.assertValueEqual(wall.machines.leases.owner(record.id).orElseThrow(), wall.player.getUUID(), "First opener controls the CPU");
                test.assertTrue(state(responses, wall.player).control() && state(responses, wall.player).detailed(), "Owner gets a paused server snapshot");
                test.assertFalse(state(responses, second).control(), "Second opener observes");
                test.assertTrue(state(responses, second).snapshot().terminalLog().contains("Native debugger output"), "Observers see terminal output");
                long count = machine.instructions();
                send(service, second, firstToken, DebuggerProtocol.Action.STEP, 0, 0, 0);
                send(service, wall.player, secondToken, DebuggerProtocol.Action.STEP, 0, 0, 0);
                test.assertValueEqual(machine.instructions(), count, "Wrong player/session pairs cannot act");
                send(service, second, secondToken, DebuggerProtocol.Action.STEP, 0, 0, 0);
                test.assertTrue(failure(responses, second).message().contains("Read only"), "Observer commands are rejected");
                send(service, second, secondToken, DebuggerProtocol.Action.MEMORY_BASE, 0x100, 0, 0); service.tick(1);
                test.assertValueEqual(state(responses, second).snapshot().memory()[0], (byte) 0x33, "Observer view requests read the real paused CPU");
                send(service, wall.player, firstToken, DebuggerProtocol.Action.WRITE_MEMORY, 0x100, 0x5A, 0);
                test.assertValueEqual(machine.onWorker(cpu -> cpu.memory().read8(0, 0x100)), 0x5A, "Owner edit reaches server memory");
                send(service, wall.player, firstToken, DebuggerProtocol.Action.WRITE_MEMORY, 0x100, 256, 0);
                test.assertValueEqual(machine.onWorker(cpu -> cpu.memory().read8(0, 0x100)), 0x5A, "Invalid byte is rejected");
                send(service, wall.player, firstToken, DebuggerProtocol.Action.WRITE_MEMORY, 0xC0000, 0, 0);
                test.assertTrue(failure(responses, wall.player).message().contains("read only"), "ROM writes are rejected");
                send(service, wall.player, firstToken, DebuggerProtocol.Action.STEP, 0, 0, 0);
                test.assertValueEqual(machine.instructions(), count + 1, "Step executes on the existing server worker");
                service.request(wall.player, firstToken, DebuggerProtocol.encode(new DebuggerProtocol.Request(DebuggerProtocol.Action.WRITE_SIGN_LINE, 0, 0, 0, "€ä")));
                test.assertValueEqual(machine.onWorker(cpu -> cpu.io().read8(0x101)), 0x80, "Sign edit uses Windows-1252");
                wall.machines.records.ports.put(remote, PortBindings.Kind.REDSTONE, new PortBindings.Settings(record.id, 3, "Remote port", true, RedstoneIrqMode.DISABLED, 0));
                send(service, wall.player, firstToken, DebuggerProtocol.Action.REDSTONE_IRQ, 3, RedstoneIrqMode.ANALOG.ordinal(), 0);
                test.assertValueEqual(wall.machines.records.ports.at(remote).orElseThrow().settings().irq(), RedstoneIrqMode.ANALOG, "Debugger IRQ edits persist for unloaded ports");
                test.assertValueEqual(machine.onWorker(cpu -> cpu.hostBridge().redstoneIrqMode(3)), RedstoneIrqMode.ANALOG, "IRQ edits reach the CPU bridge");
                send(service, wall.player, firstToken, DebuggerProtocol.Action.TIMER_AUTOMATIC, 5, 0, 0);
                send(service, wall.player, firstToken, DebuggerProtocol.Action.WRITE_TIMER, 5, 17, 1);
                test.assertFalse(machine.onWorker(cpu -> cpu.hostBridge().timerAutomatic(5)), "Timer mode reaches bridge");
                test.assertValueEqual(machine.onWorker(cpu -> cpu.io().read16(0x812A)), 17, "Timer reload reaches device registers");
                send(service, wall.player, firstToken, DebuggerProtocol.Action.RUN, 0, 0, 0); service.tick(2);
                test.assertTrue(state(responses, wall.player).snapshot().running() && !state(responses, wall.player).detailed(), "Running publication excludes CPU details");
                test.assertTrue(state(responses, wall.player).snapshot().registers().isEmpty(), "Running wire message has no register data");
                send(service, wall.player, firstToken, DebuggerProtocol.Action.WRITE_MEMORY, 0x100, 1, 0);
                test.assertValueEqual(machine.onWorker(cpu -> cpu.memory().read8(0, 0x100)), 0x5A, "Running memory edit is rejected");
                send(service, wall.player, firstToken, DebuggerProtocol.Action.PAUSE, 0, 0, 0); service.tick(3);
                test.assertTrue(state(responses, wall.player).detailed(), "Pausing republishes detailed server state");
                send(service, wall.player, firstToken, DebuggerProtocol.Action.CLOSE, 0, 0, 0); service.tick(4);
                test.assertValueEqual(wall.machines.leases.owner(record.id).orElseThrow(), second.getUUID(), "Closing passes control in opening order");
                test.assertTrue(state(responses, second).control() && !state(responses, third).control(), "New owner/observer flags reach the wire");
                send(service, second, secondToken, DebuggerProtocol.Action.WRITE_MEMORY, 0x100, 0x77, 0);
                test.assertValueEqual(machine.onWorker(cpu -> cpu.memory().read8(0, 0x100)), 0x77, "New owner can edit");
                service.disconnect(second.getUUID()); service.tick(5);
                test.assertValueEqual(wall.machines.leases.owner(record.id).orElseThrow(), third.getUUID(), "Disconnect passes control to the next observer");
                for (int i = 0; i < 40; i++) send(service, third, thirdToken, DebuggerProtocol.Action.FOLLOW_PC, 0, 0, 0);
                test.assertTrue(wall.machines.leases.owner(record.id).isEmpty(), "Request flood closes the session and releases control");
                var failing = new ServerDebugger(wall.machines, (player, packet) -> { throw new IllegalStateException("Disconnected transport"); });
                boolean rejected = false;
                try { failing.open(wall.player, wall.controller); } catch (IllegalStateException expected) { rejected = true; }
                test.assertTrue(rejected && wall.machines.leases.owner(record.id).isEmpty(), "Failed client launch delivery cannot retain a lease");
            } finally {
                service.disconnect(wall.player.getUUID()); service.disconnect(second.getUUID()); service.disconnect(third.getUUID());
                wall.machines.records.ports.remove(remote);
                test.getLevel().getServer().getPlayerList().remove(second); test.getLevel().getServer().getPlayerList().remove(third);
            }
        }
        test.succeed();
    }

    @GameTest
    public void onePlayerCanViewMultipleMainframesIndependently(GameTestHelper test) throws Exception {
        try (var first = new MachineGameTests.Wall(test, 14336); var second = new MachineGameTests.Wall(test, 16384)) {
            var service = new ServerDebugger(first.machines, (player, packet) -> {});
            try {
                UUID firstToken = service.open(first.player, first.controller);
                first.near(second.origin);
                UUID secondToken = service.open(first.player, second.controller);
                test.assertTrue(!firstToken.equals(secondToken), "Each mainframe has a separate authenticated session");
                test.assertValueEqual(first.machines.leases.owner(first.record().id).orElseThrow(), first.player.getUUID(), "Opening another mainframe retains the first lease");
                test.assertValueEqual(first.machines.leases.owner(second.record().id).orElseThrow(), first.player.getUUID(), "Same player can control a second CPU");
                send(service, first.player, secondToken, DebuggerProtocol.Action.CLOSE, 0, 0, 0);
                test.assertTrue(first.machines.leases.owner(second.record().id).isEmpty(), "Closing second window releases only its lease");
                test.assertTrue(first.machines.leases.owner(first.record().id).isPresent(), "First window remains connected");
                service.open(first.player, second.controller);
                service.disconnect(first.player.getUUID());
                test.assertTrue(first.machines.leases.owner(first.record().id).isEmpty() && first.machines.leases.owner(second.record().id).isEmpty(), "Disconnect releases every window's lease");
            } finally { service.shutdown(); }
        }
        test.succeed();
    }

    @GameTest
    public void debuggerPacketCodecsBoundAllocationAndCopyPayloads(GameTestHelper test) throws IOException {
        var token = UUID.randomUUID(); var encoded = DebuggerProtocol.encode(new DebuggerProtocol.Request(DebuggerProtocol.Action.STEP, 0, 0, 0));
        var packet = new DebuggerPackets.Request(token, encoded); byte initial = encoded[2]; encoded[2] = 99;
        test.assertValueEqual(packet.bytes()[2], initial, "Packet constructor copies payload");
        var copy = packet.bytes(); copy[2] = 88; test.assertValueEqual(packet.bytes()[2], initial, "Packet getter copies payload");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), test.getLevel().registryAccess());
        try {
            DebuggerPackets.Request.CODEC.encode(buffer, packet);
            var read = DebuggerPackets.Request.CODEC.decode(buffer);
            test.assertValueEqual(read.session(), token, "Session UUID survives packet codec");
            test.assertValueEqual(DebuggerProtocol.request(read.bytes()).action(), DebuggerProtocol.Action.STEP, "Request survives packet codec");
            buffer.clear(); buffer.writeUUID(token); buffer.writeVarInt(DebuggerPackets.MAX_REQUEST + 1);
            boolean rejected = false;
            try { DebuggerPackets.Request.CODEC.decode(buffer); } catch (RuntimeException expected) { rejected = true; }
            test.assertTrue(rejected, "Decoder rejects oversized request before allocating it");
            rejected = false;
            try { new DebuggerPackets.State(token, new byte[DebuggerProtocol.MAX_FRAME + 1]); } catch (IllegalArgumentException expected) { rejected = true; }
            test.assertTrue(rejected, "Server state frame has a hard bound");
        } finally { buffer.release(); }
        test.succeed();
    }
    private static void send(ServerDebugger service, ServerPlayer player, UUID token, DebuggerProtocol.Action action, int a, int b, int c) throws IOException {
        service.request(player, token, DebuggerProtocol.encode(new DebuggerProtocol.Request(action, a, b, c)));
    }
    private static List<DebuggerProtocol.Event> events(Map<UUID, List<CustomPacketPayload>> packets, ServerPlayer player) throws IOException {
        var events = new ArrayList<DebuggerProtocol.Event>();
        for (var packet : packets.getOrDefault(player.getUUID(), List.of()))
            if (packet instanceof DebuggerPackets.State state) events.add(DebuggerProtocol.event(state.bytes()));
        return events;
    }
    private static DebuggerProtocol.State state(Map<UUID, List<CustomPacketPayload>> packets, ServerPlayer player) throws IOException {
        return events(packets, player).stream().filter(event -> event instanceof DebuggerProtocol.State).map(event -> (DebuggerProtocol.State) event).toList().getLast();
    }
    private static DebuggerProtocol.Failure failure(Map<UUID, List<CustomPacketPayload>> packets, ServerPlayer player) throws IOException {
        return events(packets, player).stream().filter(event -> event instanceof DebuggerProtocol.Failure).map(event -> (DebuggerProtocol.Failure) event).toList().getLast();
    }
}

package dervogel101.de.emulator_i86_crafted.client;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.block.ControllerBlockEntity;
import dervogel101.de.emulator_i86_crafted.block.PortBlock;
import dervogel101.de.emulator_i86_crafted.block.PortBlockEntity;
import dervogel101.de.emulator_i86_crafted.network.IndicatorPackets;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import dervogel101.de.emulator_i86_crafted.server.storage.PortBindings;
import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.i86.runtime.IndicatorState;
import dervogel101.de.i86.runtime.RedstoneIrqMode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

/** Real packet, sound-resource, mixer-budget and looping-sound lifecycle checks. */
public final class MachineSoundClientGameTest implements FabricClientGameTest {
    private static final List<BlockPos> POSITIONS = java.util.stream.IntStream.range(0, 6)
            .mapToObj(i -> new BlockPos(2 + i * 2, 96, 2)).toList();

    @Override public void runTest(ClientGameTestContext context) {
        double blocks = context.computeOnClient(client -> client.options.getSoundSourceOptionInstance(SoundSource.BLOCKS).get());
        double master = context.computeOnClient(client -> client.options.getSoundSourceOptionInstance(SoundSource.MASTER).get());
        boolean pause = context.computeOnClient(client -> client.options.pauseOnLostFocus);
        var heard = new ArrayList<String>();
        var layers = new HashMap<Long, Integer>();
        SoundEventListener listener = (sound, event, range) -> {
            if (sound.getIdentifier().getNamespace().equals("emulator_i86_crafted")) {
                heard.add(sound.getIdentifier().getPath());
                if (!sound.isLooping()) layers.merge(ticks(), 1, Integer::sum);
            }
        };
        try {
            context.runOnClient(client -> {
                client.options.pauseOnLostFocus = false;
                client.options.getSoundSourceOptionInstance(SoundSource.BLOCKS).set(1.0);
                client.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(1.0);
                client.getSoundManager().addListener(listener);
                int cues = 0;
                for (var id : BuiltInRegistries.SOUND_EVENT.keySet()) if (id.getNamespace().equals("emulator_i86_crafted")) {
                    var event = client.getSoundManager().getSoundEvent(id);
                    if (event == null || event.getWeight() == 0) throw new AssertionError("Empty sound event: " + id);
                    var clip = event.getSound(RandomSource.create(1)).getLocation();
                    if (!clip.getNamespace().equals("emulator_i86_crafted") || !clip.getPath().startsWith("machine/"))
                        throw new AssertionError("Machine cue does not resolve to bundled audio: " + id);
                    cues++;
                }
                if (cues != 16) throw new AssertionError("Missing machine sound registrations: " + cues);
                var expected = new HashSet<>(ModContent.BLOCKS.values());
                expected.remove(ModContent.BLOCKS.get("super_structure"));
                if (!map("KINDS").keySet().equals(expected))
                    throw new AssertionError("Every machine except structural filler needs a sound profile");
            });
            try (var world = context.worldBuilder().create()) {
                var server = world.getServer();
                var connection = world.getConnection();
                server.runOnServer(host -> {
                    var level = host.overworld();
                    for (int x = 0; x <= 16; x++) for (int z = -5; z <= 5; z++)
                        level.setBlock(new BlockPos(x, 95, z), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
                    for (var position : POSITIONS)
                        level.setBlock(position, ModContent.BLOCKS.get("gpu_array").defaultBlockState(), Block.UPDATE_ALL);
                });
                server.runCommand("gamemode creative @a");
                server.runCommand("tp @a 7.5 96 -2 0 0");
                connection.waitForClientboundPackets();
                connection.waitForChunksRender();
                server.runOnServer(host -> send(host, POSITIONS, IndicatorState.OFF));
                context.waitTicks(3);
                server.runOnServer(host -> send(host, POSITIONS, IndicatorState.pack(IndicatorState.RUNNING, 4)));
                context.waitTicks(30);
                context.runOnClient(client -> {
                    assertFans(client, 3);
                    if (!heard.contains("machine.fan") || Collections.frequency(heard, "machine.sign") <= 6)
                        throw new AssertionError("Steady indicator flags must keep quiet fan/transfer audio alive");
                    if (heard.contains("machine.start")) throw new AssertionError("Non-controller modules must not play boot chimes");
                    if (layers.values().stream().anyMatch(count -> count > 6)) throw new AssertionError("Local sound layer budget exceeded");
                });
                server.runCommand("tick freeze");
                context.waitTicks(4);
                context.runOnClient(client -> heard.clear());
                context.waitTicks(12);
                context.runOnClient(client -> {
                    assertFans(client, 3);
                    if (heard.contains("machine.sign")) throw new AssertionError("Frozen machines kept repeating stale activity");
                });
                server.runCommand("tick unfreeze");
                var beforeMute = context.computeOnClient(client -> List.copyOf(map("FANS").values()));
                context.runOnClient(client -> client.options.getSoundSourceOptionInstance(SoundSource.BLOCKS).set(0.0));
                context.waitTicks(2);
                context.runOnClient(client -> {
                    assertFans(client, 0);
                    assertStopped(beforeMute);
                    client.options.getSoundSourceOptionInstance(SoundSource.BLOCKS).set(1.0);
                });
                context.waitTicks(3);
                context.runOnClient(client -> assertFans(client, 3));
                context.runOnClient(client -> client.getSoundManager().reload());
                context.waitTicks(25);
                context.runOnClient(client -> assertFans(client, 3));

                var removed = context.computeOnClient(client -> map("FANS").keySet().iterator().next());
                var oldFan = context.computeOnClient(client -> map("FANS").get(removed));
                server.runOnServer(host -> send(host, List.of((BlockPos) removed), IndicatorState.REMOVE));
                context.waitTicks(2);
                context.runOnClient(client -> {
                    assertStopped(List.of(oldFan));
                    if (map("FANS").containsKey(removed)) throw new AssertionError("Removed telemetry left a fan behind");
                });
                server.runOnServer(host -> send(host, POSITIONS, IndicatorState.OFF));
                context.waitTicks(2);
                context.runOnClient(client -> assertFans(client, 0));
                server.runOnServer(host -> send(host, POSITIONS, IndicatorState.RUNNING));
                context.waitTicks(2);
                context.runOnClient(client -> assertFans(client, 3));
                server.runCommand("tp @a 80 96 -2");
                context.waitTicks(10);
                context.runOnClient(client -> assertFans(client, 0));
                server.runCommand("tp @a 7.5 96 -2");
                context.waitTicks(10);
                context.runOnClient(client -> assertFans(client, 3));

                var origin = new BlockPos(1, 96, 1);
                var power = origin.offset(Hardware.POWER.ordinal() % 7, Hardware.POWER.ordinal() / 7, 1);
                server.runOnServer(host -> {
                    KeyboardClientGameTest.build(host);
                    host.overworld().setBlockAndUpdate(power, Blocks.AIR.defaultBlockState());
                    var controller = (ControllerBlockEntity) host.overworld().getBlockEntity(origin);
                    var owner = ServerMachines.get(host);
                    owner.powerOff(controller.id(), "Audio fixture configuration");
                    var signPort = new BlockPos(12, 96, 3);
                    host.overworld().setBlockAndUpdate(signPort, ModContent.BLOCKS.get("sign_port").defaultBlockState()
                            .setValue(PortBlock.PORT_FACING, Direction.EAST));
                    host.overworld().setBlockAndUpdate(signPort.east(), Blocks.OAK_SIGN.defaultBlockState());
                    owner.ports.configure((PortBlockEntity) host.overworld().getBlockEntity(signPort),
                            new PortBindings.Settings(controller.id(), 0, "audio", false, RedstoneIrqMode.DISABLED, 0),
                            host.getPlayerList().getPlayers().getFirst().getUUID());
                    for (int i = 0; i < 2; i++) {
                        var position = new BlockPos(9 + i * 2, 96, 4);
                        host.overworld().setBlockAndUpdate(position, ModContent.BLOCKS.get(i == 0 ? "punchcard_reader" : "punchcard_writer").defaultBlockState());
                        owner.ports.configure((PortBlockEntity) host.overworld().getBlockEntity(position),
                                new PortBindings.Settings(controller.id(), 0, "audio", false, RedstoneIrqMode.DISABLED, 0),
                                host.getPlayerList().getPlayers().getFirst().getUUID());
                    }
                });
                context.waitTicks(12);
                context.runOnClient(client -> heard.clear());
                server.runOnServer(host -> host.overworld().setBlockAndUpdate(power, Blocks.REDSTONE_BLOCK.defaultBlockState()));
                context.waitTicks(15);
                context.runOnClient(client -> {
                    if (!heard.contains("machine.start") || !heard.contains("machine.relay"))
                        throw new AssertionError("A real mainframe boot must play its relay and startup cue");
                    heard.clear();
                });
                server.runOnServer(host -> {
                    var controller = (ControllerBlockEntity) host.overworld().getBlockEntity(origin);
                    ServerMachines.get(host).machine(controller.id()).onWorker(cpu -> {
                        cpu.io().write8(0x8130, 'A');
                        cpu.io().write8(0x8150, 1);
                        cpu.beforeInstruction();
                        return null;
                    });
                });
                context.waitTicks(12);
                context.runOnClient(client -> {
                    if (!heard.contains("machine.terminal")) throw new AssertionError("New CPU terminal output was silent");
                    heard.clear();
                });
                server.runOnServer(host -> {
                    var controller = (ControllerBlockEntity) host.overworld().getBlockEntity(origin);
                    var owner = ServerMachines.get(host);
                    owner.machine(controller.id()).onWorker(cpu -> {
                        cpu.io().writeGuest8(0x100, 0x10);
                        cpu.io().writeGuest8(0x101, 'Z');
                        cpu.beforeInstruction();
                        return null;
                    });
                });
                context.waitTicks(12);
                context.runOnClient(client -> {
                    if (!heard.contains("machine.sign")) throw new AssertionError("New I/O sign text was silent");
                    heard.clear();
                });
                server.runOnServer(host -> {
                    var controller = (ControllerBlockEntity) host.overworld().getBlockEntity(origin);
                    ServerMachines.get(host).machine(controller.id()).onWorker(cpu -> {
                        cpu.io().write8(0x8164, 1);
                        cpu.beforeInstruction();
                        return null;
                    });
                });
                context.waitTicks(12);
                context.runOnClient(client -> {
                    if (!heard.contains("machine.disk")) throw new AssertionError("Real disk access was silent");
                    heard.clear();
                });
                server.runOnServer(host -> {
                    for (int i = 0; i < 100; i++) for (int device = 0; device < 2; device++)
                        ServerMachines.get(host).indicators.pulse(host.overworld(), new BlockPos(9 + device * 2, 96, 4), 1);
                });
                context.waitTicks(8);
                context.runOnClient(client -> {
                    if (!heard.contains("machine.card_read") || !heard.contains("machine.card_write") || !heard.contains("machine.relay"))
                        throw new AssertionError("Batched punchcard row bursts did not play distinct layered scan/punch cues");
                    if (Collections.frequency(heard, "machine.card_read") > 2 || Collections.frequency(heard, "machine.card_write") > 2)
                        throw new AssertionError("A punchcard burst replayed its per-event backlog");
                    heard.clear();
                });
                server.runOnServer(host -> {
                    for (int device = 0; device < 2; device++)
                        ServerMachines.get(host).indicators.pulse(host.overworld(), new BlockPos(9 + device * 2, 96, 4), 4);
                });
                context.waitTicks(8);
                context.runOnClient(client -> {
                    if (!heard.contains("machine.card_feed") || !heard.contains("machine.paper"))
                        throw new AssertionError("Card advance/eject must layer the mechanism and paper sounds");
                    if (layers.values().stream().anyMatch(count -> count > 6)) throw new AssertionError("Layered machine cues exceeded the local sound budget");
                    heard.clear();
                });
                server.runOnServer(host -> host.overworld().setBlockAndUpdate(power, Blocks.AIR.defaultBlockState()));
                context.waitTicks(12);
                context.runOnClient(client -> {
                    if (!heard.contains("machine.stop")) throw new AssertionError("Mainframe shutdown was silent");
                });
            }
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        } finally {
            context.runOnClient(client -> {
                client.getSoundManager().removeListener(listener);
                client.options.getSoundSourceOptionInstance(SoundSource.BLOCKS).set(blocks);
                client.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(master);
                client.options.pauseOnLostFocus = pause;
            });
        }
        context.runOnClient(client -> {
            if (!map("FANS").isEmpty() || !map("STATES").isEmpty()) throw new AssertionError("Disconnect did not clear machine audio");
        });
        System.out.println("I86_MACHINE_SOUNDS_OK: bundled resources, profiles, boot/terminal/sign/disk/shutdown, layered punchcard bursts, layer/fan caps, freeze/mute/reload/removal/power/range/disconnect cleanup");
    }

    private static void send(MinecraftServer server, List<BlockPos> positions, int flags) {
        var player = server.getPlayerList().getPlayers().getFirst();
        ServerPlayNetworking.send(player, new IndicatorPackets.Update(player.level().dimension().identifier().toString(),
                positions.stream().map(position -> new IndicatorPackets.Entry(position, flags)).toList()));
    }

    private static Map<?, ?> map(String name) {
        try {
            var field = ClientMachineSounds.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Map<?, ?>) field.get(null);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static long ticks() {
        try {
            var field = ClientMachineSounds.class.getDeclaredField("ticks");
            field.setAccessible(true);
            return field.getLong(null);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void assertFans(Minecraft client, int expected) {
        var fans = map("FANS");
        if (fans.size() != expected) throw new AssertionError("Expected " + expected + " fans, got " + fans.size());
        for (var value : fans.values()) {
            var fan = (SoundInstance) value;
            if (fan.getPitch() < .97f || fan.getPitch() > 1.06f) throw new AssertionError("Fan playback must stay near natural pitch");
            if (!fan.isLooping() || fan.getSource() != SoundSource.BLOCKS || fan.getVolume() > .025f
                    || !client.getSoundManager().isActive(fan)) throw new AssertionError("Quiet positional fan is not active");
        }
    }

    private static void assertStopped(List<?> sounds) {
        for (var sound : sounds) if (!((TickableSoundInstance) sound).isStopped()) throw new AssertionError("Orphaned fan loop");
    }
}

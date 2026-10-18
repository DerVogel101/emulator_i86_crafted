package dervogel101.de.emulator_i86_crafted.client;

import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.block.*;
import dervogel101.de.emulator_i86_crafted.network.DebuggerPackets;
import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.*;
import dervogel101.de.i86.runtime.*;
import dervogel101.de.launcher.DebuggerProtocol;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.*;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real client, integrated-server restart and the external helper; never part of the shipped mod. */
public final class LiveClientGameTest implements FabricClientGameTest {
    private record Fixture(long id, BlockPos controller, BlockPos power, BlockPos sign, BlockPos shardOnly) {}
    @Override public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("i86.client.artwork")) return;
        try { if (Boolean.getBoolean("i86.client.reopen")) reopenFreshProcess(context); else check(context); }
        catch (Exception failure) { throw new IllegalStateException("Live i86 client check failed", failure); }
    }
    private static void check(ClientGameTestContext context) throws Exception {
        boolean pauseOnFocusLoss = context.computeOnClient(client -> client.options.pauseOnLostFocus);
        boolean hideGui = context.computeOnClient(client -> client.gui.hud.isHidden());
        int guiScale = context.computeOnClient(client -> client.options.guiScale().get());
        context.runOnClient(client -> { client.options.pauseOnLostFocus = false; client.options.guiScale().set(2); client.resizeGui(); if (!client.gui.hud.isHidden()) client.gui.hud.toggle(); });
        TestWorldSave save;
        Fixture fixture;
        var savedRecord = new AtomicReference<WorldRecords.Mainframe>();
        try {
            try (var world = context.worldBuilder().create()) {
                var connection = world.getConnection(); var server = world.getServer();
                fixture = server.computeOnServer(LiveClientGameTest::build);
                checkShardTooltips(context, world);
                server.runCommand("tp @a 5.5 96 -10 0 3"); connection.waitForClientboundPackets(); connection.waitForChunksRender(); context.waitTicks(10);
                System.out.println("I86_CLIENT_SCREEN_OFF=" + context.takeScreenshot(TestScreenshotOptions.of("i86-mainframe-off").withSize(1280, 720)));
                checkControllerDiagnostics(context, world, fixture);
                checkCustomScreens(context, world, fixture);
                server.runOnServer(host -> host.overworld().setBlock(fixture.power(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL));
                server.waitFor(host -> ServerMachines.get(host).records.mainframe(fixture.id()).orElseThrow().powered);
                context.waitTicks(15); connection.waitForClientboundPackets();
                context.waitFor(client -> client.level != null && client.level.getBlockState(fixture.controller()).getValue(MachineBlock.POWERED));
            checkLiveTerminalAndLockedSign(context, world, fixture);
                System.out.println("I86_CLIENT_SCREEN_ON=" + context.takeScreenshot(TestScreenshotOptions.of("i86-mainframe-on").withSize(1280, 720)));
                context.waitTicks(3); System.out.println("I86_CLIENT_SCREEN_ANIMATION=" + context.takeScreenshot(TestScreenshotOptions.of("i86-mainframe-animation").withSize(1280, 720)));

                server.runCommand("tp @a 1.5 96 -4 0 0"); connection.waitForClientboundPackets();
                server.runOnServer(host -> MachineDialogs.open(host.getPlayerList().getPlayers().getFirst(), fixture.controller()));
                context.waitFor(client -> client.gui.screen() instanceof dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen);
                System.out.println("I86_CLIENT_CONTROLLER=" + context.takeScreenshot(TestScreenshotOptions.of("i86-controller-dialog").withSize(1280, 720)));
                context.runOnClient(client -> {
                    if (!press(client.gui.screen(), "Open debugger")) throw new AssertionError("Native controller dialog has no debugger button");
                });
                context.waitFor(client -> child() != null && child().isAlive());
                context.runOnClient(client -> client.options.pauseOnLostFocus = true);
                context.waitTicks(15);
                context.runOnClient(client -> {
                    if (!ClientDebugger.isOpen() || !client.options.pauseOnLostFocus || client.gui.screen() != null)
                        throw new AssertionError("External debugger triggered automatic focus pause or changed the player's option");
                });
                server.waitFor(host -> ServerMachines.get(host).leases.owner(fixture.id()).isPresent());
                UUID token = context.computeOnClient(client -> token());
                request(context, token, DebuggerProtocol.Action.PAUSE, 0, 0, 0, ""); connection.waitForServerboundPackets();
                server.waitFor(host -> ServerMachines.get(host).records.mainframe(fixture.id()).orElseThrow().paused);
                request(context, token, DebuggerProtocol.Action.WRITE_MEMORY, 0x2345, 0x5A, 0, "");
                request(context, token, DebuggerProtocol.Action.WRITE_MEMORY, 0xA0007, 0x4B, 0, "");
                request(context, token, DebuggerProtocol.Action.WRITE_MEMORY, 0xB0009, 0x6C, 0, "");
                request(context, token, DebuggerProtocol.Action.SEND_IRQ, 0x33, 0, 0, "");
                request(context, token, DebuggerProtocol.Action.WRITE_IO, 0x100, 0x1F, 0, "");
                request(context, token, DebuggerProtocol.Action.WRITE_IO, 0x140, 0x1F, 0, "");
                request(context, token, DebuggerProtocol.Action.WRITE_SIGN_LINE, 0, 0, 0, "€ Grüße");
                request(context, token, DebuggerProtocol.Action.WRITE_SIGN_LINE, 0, 4, 0, "Back äÖü ß");
                request(context, token, DebuggerProtocol.Action.WRITE_IO, 1, 12, 0, "");
                connection.waitForServerboundPackets(); context.waitTicks(3); connection.waitForClientboundPackets();
                server.runOnServer(host -> {
                    var machine = ServerMachines.get(host).machine(fixture.id());
                    machine.onWorker(cpu -> {
                        if (cpu.memory().read8(0, 0x2345) != 0x5A || cpu.memory().read8(0xA000, 7) != 0x4B || cpu.memory().read8(0xB000, 9) != 0x6C)
                            throw new AssertionError("Authenticated client edits did not reach server memory");
                        cpu.registers().setAx(0x2468);
                        byte[] disk = cpu.io().storageDevice().snapshot().bytes(); disk[137] = 99; cpu.io().storageDevice().load(disk);
                        return null;
                    });
                });
                context.waitFor(client -> {
                    if (!(client.level.getBlockEntity(fixture.sign()) instanceof SignBlockEntity sign)) return false;
                    return sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString().equals("€ Grüße");
                });
                context.runOnClient(client -> {
                    var sign = (SignBlockEntity) client.level.getBlockEntity(fixture.sign());
                    var font = new FontDescription.Resource(ModContent.id("monocraft"));
                    if (!sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getStyle().getFont().equals(font)
                            || !sign.getText(SignTextSlot.BACK).getMessages(false).getFirst().getStyle().getFont().equals(font))
                        throw new AssertionError("Mod sign font did not reach the actual client");
                    "MWi .ÄÖÜäöüß€ƒˆ˜ŒŠŸ¬¶—…™".codePoints().forEach(cp -> {
                        var glyph = Component.literal(Character.toString(cp)).withStyle(style -> style.withFont(font));
                        if (client.font.width(glyph) != 6)
                            throw new AssertionError("Bitmap sign glyph must retain six-pixel advance: U+" + Integer.toHexString(cp));
                    });
                });
                server.runOnServer(host -> host.overworld().setBlock(fixture.sign().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL));
                server.runCommand("tp @a 10.5 95.1 -2 0 0"); connection.waitForClientboundPackets(); context.waitTicks(5);
                System.out.println("I86_CLIENT_SIGN=" + context.takeScreenshot(TestScreenshotOptions.of("i86-sign-font").withSize(1280, 720)));
                Process firstWindow = context.computeOnClient(client -> child());
                long secondId = server.computeOnServer(host -> {
                    var pos = new BlockPos(12, 96, 1);
                    host.overworld().setBlock(pos, ModContent.BLOCKS.get("mainframe_controller").defaultBlockState(), Block.UPDATE_ALL);
                    var controller = (ControllerBlockEntity) host.overworld().getBlockEntity(pos);
                    var owner = ServerMachines.get(host); owner.attach(controller);
                    owner.debugger.open(host.getPlayerList().getPlayers().getFirst(), controller);
                    return controller.id();
                });
                context.waitFor(client -> sessions().size() == 2 && sessions().values().stream().allMatch(value -> {
                    Process helper = (Process) field(value, "process"); return helper != null && helper.isAlive();
                }));
                context.runOnClient(client -> {
                    if (!firstWindow.isAlive()) throw new AssertionError("Second debugger closed first helper");
                    for (var value : sessions().values()) if (!token.equals(field(value, "token"))) ((Process) field(value, "process")).destroy();
                });
                context.waitFor(client -> sessions().size() == 1 && token.equals(token()));
                server.waitFor(host -> ServerMachines.get(host).leases.owner(secondId).isEmpty() && ServerMachines.get(host).leases.owner(fixture.id()).isPresent());
                request(context, token, DebuggerProtocol.Action.RUN, 0, 0, 0, ""); connection.waitForServerboundPackets();
                context.runOnClient(client -> { Objects.requireNonNull(child()).destroy(); client.options.pauseOnLostFocus = false; });
                context.waitFor(client -> session() == null);
                server.waitFor(host -> ServerMachines.get(host).leases.owner(fixture.id()).isEmpty());
                server.runOnServer(host -> {
                    var record = ServerMachines.get(host).records.mainframe(fixture.id()).orElseThrow();
                    if (!record.powered || record.paused) throw new AssertionError("Closing helper changed the running CPU's power state");
                    savedRecord.set(record);
                });
                save = world.getWorldSave();
            }
            assertShardTooltips(context, List.of(dervogel101.de.i86.runtime.ShardSpeed.TIER_1,
                    dervogel101.de.i86.runtime.ShardSpeed.TIER_2, dervogel101.de.i86.runtime.ShardSpeed.TIER_3));
            long stoppedCount = savedRecord.get().instructions;
            if (!savedRecord.get().powered) throw new AssertionError("Server shutdown power-cycled the mainframe");
            try (var reopened = save.open()) {
                reopened.getConnection().waitForChunksDownload();
                checkShardTooltips(context, reopened);
            reopened.getServer().runOnServer(host -> {
                var controller = (ControllerBlockEntity) host.overworld().getBlockEntity(fixture.shardOnly());
                if (controller == null || !controller.inventory.getItem(0).isEmpty() || controller.shardTier() != 2)
                    throw new AssertionError("Save/reopen moved a lone speed shard into the ROM slot");
            });
                reopened.getServer().waitFor(host -> {
                    var machine = ServerMachines.get(host).machine(fixture.id());
                    return machine != null && machine.record.powered && !machine.record.faulted && machine.instructions() > stoppedCount;
                });
                reopened.getServer().runOnServer(host -> {
                    var owner = ServerMachines.get(host); var machine = owner.machine(fixture.id());
                    if (!owner.leases.owner(fixture.id()).isEmpty()) throw new AssertionError("Debugger lease survived disconnection");
                    machine.pause(true);
                    machine.onWorker(cpu -> {
                        if (cpu.registers().getAx() != 0x2468 || cpu.memory().read8(0, 0x2345) != 0x5A || cpu.memory().read8(0xA000, 7) != 0x4B
                                || cpu.memory().read8(0xB000, 9) != 0x6C || cpu.io().storageDevice().snapshot().bytes()[137] != (byte) 99
                                || cpu.io().read16(0xFF00) != 0x33)
                            throw new AssertionError("World reopen lost registers, RAM, NVRAM, shared data, disk or IRQ state");
                        return null;
                    });
                });
                reopened.getConnection().waitForClientboundPackets(); context.waitTicks(3);
                System.out.println("I86_CLIENT_REOPEN=" + context.takeScreenshot(TestScreenshotOptions.of("i86-world-reopened").withSize(1280, 720)));
            var shardPos = fixture.shardOnly();
            reopened.getServer().runCommand("tp @a " + (shardPos.getX() + 0.5) + " " + shardPos.getY() + " " + (shardPos.getZ() - 3) + " 0 0");
            reopened.getConnection().waitForClientboundPackets();
            reopened.getServer().runOnServer(host -> ((ControllerBlockEntity) host.overworld().getBlockEntity(shardPos)).open(host.getPlayerList().getPlayers().getFirst()));
            context.waitFor(client -> client.gui.screen() instanceof dervogel101.de.emulator_i86_crafted.client.screen.ControllerScreen);
            context.waitFor(client -> {
                var screen = (dervogel101.de.emulator_i86_crafted.client.screen.ControllerScreen) client.gui.screen();
                return screen.getMenu().getSlot(0).getItem().isEmpty()
                        && screen.getMenu().getSlot(1).getItem().is(ModContent.ITEMS.get("speed_shard_2"));
            });
            context.takeScreenshot(TestScreenshotOptions.of("i86-shard-only-reopened").withSize(1280, 720));
            closeScreen(context);
            reopened.getConnection().waitForServerboundPackets();
            System.out.println("I86_SHARD_ONLY_REOPEN_OK: empty ROM slot and speed shard survive world reopen and client inventory sync");

                reopened.getServer().runOnServer(host -> {
                    var machine = ServerMachines.get(host).machine(fixture.id());
                    machine.onWorker(cpu -> { cpu.io().write16(0x8102, 1000); cpu.io().write8(0x8104, 1); cpu.beforeInstruction(); return null; });
                    machine.pause(false);
                });
            }
            var checkpoint = new Properties();
            checkpoint.setProperty("world", save.getSaveDirectory().toAbsolutePath().toString());
            checkpoint.setProperty("id", Long.toString(fixture.id()));
            checkpoint.setProperty("steps", Long.toString(savedRecord.get().instructions));
            try (var out = java.nio.file.Files.newOutputStream(java.nio.file.Path.of("i86-reopen.properties"))) { checkpoint.store(out, "Fresh-JVM powered world continuation check"); }
            System.out.println("I86_LIVE_CLIENT_OK: custom screens, chip-icon creative tab, translated names, simultaneous external helpers, authenticated edits, sign font, powered save/reopen and exact CPU/device continuation");
        } finally { context.runOnClient(client -> { client.options.pauseOnLostFocus = pauseOnFocusLoss; client.options.guiScale().set(guiScale); client.resizeGui(); if (client.gui.hud.isHidden() != hideGui) client.gui.hud.toggle(); }); }
    }
    private static void reopenFreshProcess(ClientGameTestContext context) throws Exception {
        var checkpoint = new Properties();
        try (var in = java.nio.file.Files.newInputStream(java.nio.file.Path.of("i86-reopen.properties"))) { checkpoint.load(in); }
        long id = Long.parseLong(checkpoint.getProperty("id")), steps = Long.parseLong(checkpoint.getProperty("steps"));
        var save = new net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl(context, java.nio.file.Path.of(checkpoint.getProperty("world")));
        try (var world = save.open()) {
            world.getConnection().waitForChunksDownload();
            world.getServer().waitFor(host -> {
                var machine = ServerMachines.get(host).machine(id);
                return machine != null && machine.record.powered && !machine.record.faulted && machine.instructions() > steps;
            });
            world.getServer().runOnServer(host -> {
                var owner = ServerMachines.get(host); var machine = owner.machine(id); machine.pause(true);
                if (!owner.leases.owner(id).isEmpty()) throw new AssertionError("Fresh process restored a disconnected debugger lease");
                machine.onWorker(cpu -> {
                    if (cpu.registers().getAx() != 0x2468 || cpu.memory().read8(0, 0x2345) != 0x5A || cpu.memory().read8(0xA000, 7) != 0x4B
                            || cpu.memory().read8(0xB000, 9) != 0x6C || cpu.io().storageDevice().snapshot().bytes()[137] != (byte) 99 || cpu.io().read16(0xFF00) != 0x33)
                        throw new AssertionError("Fresh process lost CPU/device continuation");
                    if (cpu.io().read16(0x8100) != 0) throw new AssertionError("Real-time timer failed to age while JVM was stopped");
                    return null;
                });
            });
        }
        System.out.println("I86_FRESH_PROCESS_OK: powered world, registers, RAM, NVRAM, shared memory, disk, IRQ and elapsed real-time timer");
    }

    private static void checkShardTooltips(ClientGameTestContext context, TestSingleplayerContext world) throws Exception {
        var expected = world.getServer().computeOnServer(host -> {
            var machines = ServerMachines.get(host);
            return List.of(machines.shardSpeed(1), machines.shardSpeed(2), machines.shardSpeed(3));
        });
        assertShardTooltips(context, expected);
        System.out.println("I86_SHARD_TOOLTIPS_OK: server-configured steps/batches on all tiers: " + expected);
    }

    private static void assertShardTooltips(ClientGameTestContext context, List<dervogel101.de.i86.runtime.ShardSpeed> expected) {
        context.waitFor(client -> {
            for (int tier = 1; tier <= 3; tier++) {
                var stack = new ItemStack(ModContent.ITEMS.get("speed_shard_" + tier));
                var tooltipContext = client.level == null ? net.minecraft.world.item.Item.TooltipContext.EMPTY
                        : net.minecraft.world.item.Item.TooltipContext.of(client.level);
                var lines = stack.getTooltipLines(tooltipContext, client.player, net.minecraft.world.item.TooltipFlag.NORMAL)
                        .stream().map(net.minecraft.network.chat.Component::getString).toList();
                var speed = expected.get(tier - 1);
                if (!lines.contains("Steps per tick: " + speed.stepsPerTick())
                        || !lines.contains("Batches per tick: " + speed.batchesPerTick())) return false;
            }
            return true;
        });
    }

    private static void checkLiveTerminalAndLockedSign(ClientGameTestContext context, TestSingleplayerContext world, Fixture fixture) throws Exception {
        var server = world.getServer(); var connection = world.getConnection();
        int index = Hardware.TERMINAL.ordinal();
        var terminal = fixture.controller().offset(index % 7, index / 7, 0);
        server.runCommand("tp @a " + (terminal.getX() + 0.5) + " " + terminal.getY() + " " + (terminal.getZ() - 3) + " 0 0");
        connection.waitForClientboundPackets();
        server.runOnServer(host -> ServerMachines.get(host).machine(fixture.id()).bridge().reportHostError("Terminal initial marker\n"));
        openPanel(context, world, terminal);
        var original = context.computeOnClient(client -> (dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen) client.gui.screen());
        UUID token = context.computeOnClient(client -> original.model().token());
        server.runOnServer(host -> ServerMachines.get(host).machine(fixture.id()).bridge().reportHostError("Terminal line\n".repeat(60) + "Terminal live marker\n"));
        context.waitFor(client -> client.gui.screen() == original && original.model().body().contains("Terminal live marker"));
        int scrolled = context.computeOnClient(client -> {
            if (!original.model().token().equals(token) || (int) field(original, "scroll") <= 0)
                throw new AssertionError("Live terminal lost its token or failed to follow new output");
            original.mouseScrolled((int) field(original, "x") + 30, (int) field(original, "contentTop") + 20, 0, 5);
            return (int) field(original, "scroll");
        });
        server.runOnServer(host -> ServerMachines.get(host).machine(fixture.id()).bridge().reportHostError("Terminal appended marker\n"));
        context.waitFor(client -> original.model().body().contains("Terminal appended marker"));
        context.runOnClient(client -> {
            if (client.gui.screen() != original || (int) field(original, "scroll") != scrolled)
                throw new AssertionError("Terminal update replaced the screen or reset manual scroll");
        });
        var wrong = new dervogel101.de.emulator_i86_crafted.network.ControlPanel(
                dervogel101.de.emulator_i86_crafted.network.ControlPanel.Kind.MACHINE, UUID.randomUUID(),
                "access_terminal", "Access Terminal", "Wrong-session update", List.of(), List.of());
        server.runOnServer(host -> dervogel101.de.emulator_i86_crafted.network.PanelPackets.update(host.getPlayerList().getPlayers().getFirst(), wrong));
        connection.waitForClientboundPackets(); context.waitTicks(3);
        context.runOnClient(client -> { if (!original.model().token().equals(token) || original.model().body().contains("Wrong-session")) throw new AssertionError("Unrelated update changed the terminal"); });
        var stale = context.computeOnClient(client -> original.model());
        closeScreen(context); connection.waitForServerboundPackets();
        server.runOnServer(host -> dervogel101.de.emulator_i86_crafted.network.PanelPackets.update(host.getPlayerList().getPlayers().getFirst(), stale));
        connection.waitForClientboundPackets(); context.waitTicks(3);
        context.runOnClient(client -> { if (client.gui.screen() != null) throw new AssertionError("Late update reopened a closed terminal"); });
        System.out.println("I86_TERMINAL_LIVE_OK: fresh output, same screen/token, scroll preservation, stale-update rejection and close");

        var sign = fixture.sign();
        server.runCommand("tp @a " + (sign.getX() + 0.5) + " " + sign.getY() + " " + (sign.getZ() - 3) + " 0 0");
        connection.waitForClientboundPackets();
        context.waitFor(client -> client.level.getBlockEntity(sign) instanceof net.minecraft.world.level.block.entity.SignBlockEntity target && target.isWaxed());
        context.runOnClient(client -> client.gameMode.useItemOn(client.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(sign), Direction.NORTH, sign, false)));
        connection.waitForServerboundPackets(); context.waitTicks(5); connection.waitForClientboundPackets();
        context.runOnClient(client -> { if (client.gui.screen() != null) throw new AssertionError("CPU display sign opened an editor"); });
        server.runOnServer(host -> ServerMachines.get(host).machine(fixture.id()).onWorker(cpu -> {
            cpu.beforeInstruction();
            if (cpu.io().read16(0xFF00) != 0x0100) throw new AssertionError("Locked sign did not deliver its click IRQ");
            cpu.io().write8(0xFF02, 1); cpu.io().write8(0x0381, 0); cpu.beforeInstruction();
            return null;
        }));
        System.out.println("I86_SIGN_LOCK_OK: real click keeps editor closed and delivers the sign IRQ");
        server.runCommand("tp @a 5.5 96 -10 0 3"); connection.waitForClientboundPackets();
    }

    private static void checkControllerDiagnostics(ClientGameTestContext context, TestSingleplayerContext world, Fixture fixture) throws Exception {
        var original = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        var power = fixture.controller().offset(Hardware.POWER.ordinal() % 7, Hardware.POWER.ordinal() / 7, 0);
        var gpio = fixture.controller().offset(Hardware.GPIO.ordinal() % 7, Hardware.GPIO.ordinal() / 7, 0);
        var wrong = fixture.controller().offset(Hardware.NMI.ordinal() % 7, Hardware.NMI.ordinal() / 7, 0);
        var extra = fixture.controller().offset(6, 2, 0); var invalid = fixture.controller().offset(5, 2, 0);
        world.getServer().runCommand("tp @a 1.5 96 -3 0 0"); world.getConnection().waitForClientboundPackets();
        world.getServer().runOnServer(host -> {
            var level = host.overworld();
            for (var pos : List.of(power, gpio, wrong, extra, invalid)) original.put(pos, level.getBlockState(pos));
            level.setBlock(power, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(gpio, ModContent.BLOCKS.get("super_structure").defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(extra, ModContent.BLOCKS.get("atomic_clock").defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(wrong, level.getBlockState(wrong).setValue(MachineBlock.FACING, Direction.SOUTH), Block.UPDATE_ALL);
            level.setBlock(invalid, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        });
        try {
            openPanel(context, world, fixture.controller());
            UUID token = context.computeOnClient(client -> {
                var panel = (dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen) client.gui.screen();
                String body = panel.model().body();
                if (!body.contains("Structure: INVALID") || !body.contains("Missing Power Supply") || !body.contains("Missing GPIO Controller")
                        || !body.contains("Too many Atomic Clock: found 2, maximum 1") || !body.contains("Wrong facing: expected NORTH, found SOUTH")
                        || !body.contains("Invalid wall block: Stone") || !body.contains("Atomic Clock × 2") || body.contains("Installed:") || !body.contains("(" + power.getX() + ", " + power.getY() + ", " + power.getZ() + ")"))
                    throw new AssertionError("Controller screen did not receive complete structural diagnostics: " + body);
                return panel.model().token();
            });
            context.takeScreenshot(TestScreenshotOptions.of("i86-invalid-mainframe").withSize(1280, 720));
            context.runOnClient(client -> { if (!press(client.gui.screen(), "Refresh")) throw new AssertionError("Invalid controller cannot refresh"); });
            context.waitFor(client -> client.gui.screen() instanceof dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen panel && !panel.model().token().equals(token));
        } finally {
            closeScreen(context); world.getConnection().waitForServerboundPackets();
            openPanel(context, world, extra);
        context.runOnClient(client -> {
            var panel = (dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen) client.gui.screen();
            String body = panel.model().body();
            if (!body.contains("Invalid assembly.") || body.contains("Detected components:")
                    || body.contains("Installed:") || body.contains("Missing Power Supply")
                    || body.contains("Too many Atomic Clock"))
                throw new AssertionError("Component screen must show only generic assembly status: " + body);
        });
        context.takeScreenshot(TestScreenshotOptions.of("i86-component-invalid").withSize(1280, 720));
        closeScreen(context);
        world.getConnection().waitForServerboundPackets();
        world.getServer().runOnServer(host -> original.forEach((pos, state) -> host.overworld().setBlock(pos, state, Block.UPDATE_ALL)));
        }
        context.waitTicks(2);
        openPanel(context, world, fixture.controller());
        context.runOnClient(client -> {
            var panel = (dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen) client.gui.screen();
            if (!panel.model().body().contains("Structure: VALID") || panel.model().body().contains("Powered off: invalid mainframe"))
                throw new AssertionError("Repaired controller retained stale structural faults");
        });
        closeScreen(context); world.getConnection().waitForServerboundPackets();
        System.out.println("I86_DIAGNOSTICS_UI_OK: missing/excess counts, invalid block type/coordinates, wrong facing, Refresh and repaired-state clearing");
    }

    private static void checkCustomScreens(ClientGameTestContext context, TestSingleplayerContext world, Fixture fixture) throws Exception {
        var server = world.getServer(); var connection = world.getConnection();
        server.runCommand("tp @a 1.5 96 -3 0 0"); connection.waitForClientboundPackets();
        context.runOnClient(client -> {
            for (var entry : ModContent.ITEMS.entrySet()) {
                String name = new ItemStack(entry.getValue()).getHoverName().getString();
                if (name.startsWith("item.") || name.startsWith("block.") || name.isBlank()) throw new AssertionError("Untranslated item: " + entry.getKey());
            }
            var tab = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.getValue(ModContent.id("mainframes"));
            if (tab == null || !tab.getIconItem().is(ModContent.ITEMS.get("rom_chip")) || !tab.getDisplayName().getString().equals("i86 Mainframes"))
                throw new AssertionError("Missing custom creative tab or ROM-chip icon");
            var creative = new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(client.player, client.player.connection.enabledFeatures(), true);
            client.gui.setScreen(creative);
            if (!creative.setSelectedTab(tab)) throw new AssertionError("Custom tab cannot be selected");
            if (tab.getSearchTabDisplayItems().size() != ModContent.ITEMS.size()) throw new AssertionError("Creative tab does not contain every mod item");
        });
        context.waitTicks(3);
        context.runOnClient(client -> {
            var creative = (net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) client.gui.screen();
            if (!creative.getSelectedTab().getDisplayName().getString().equals("i86 Mainframes")) throw new AssertionError("Creative screen changed away from custom tab");
        });
        context.takeScreenshot(TestScreenshotOptions.of("i86-creative-tab").withSize(1280, 720));
        closeScreen(context); connection.waitForServerboundPackets();
        openPanel(context, world, fixture.controller());
        context.runOnClient(client -> {
            for (var child : client.gui.screen().children()) if (child instanceof net.minecraft.client.gui.components.EditBox edit && edit.getValue().equals("Mainframe")) edit.setValue("UI Mainframe");
            if (!press(client.gui.screen(), "Rename")) throw new AssertionError("Custom rename button missing");
        });
        server.waitFor(host -> ServerMachines.get(host).records.mainframe(fixture.id()).orElseThrow().name.equals("UI Mainframe"));
        context.waitFor(client -> client.gui.screen() instanceof dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen);
        context.runOnClient(client -> { if (!press(client.gui.screen(), "ROM / speed shard")) throw new AssertionError("Installation action missing"); });
        context.waitFor(client -> client.gui.screen() instanceof dervogel101.de.emulator_i86_crafted.client.screen.ControllerScreen);
        context.waitFor(client -> ((dervogel101.de.emulator_i86_crafted.client.screen.ControllerScreen) client.gui.screen()).getMenu().editable());
        context.takeScreenshot(TestScreenshotOptions.of("i86-installation-bay").withSize(1280, 720));
        closeScreen(context); connection.waitForServerboundPackets();
        for (var hardware : new Hardware[]{Hardware.SHARED_MEMORY, Hardware.TERMINAL, Hardware.INTERRUPTS, Hardware.POWER, Hardware.RESET, Hardware.NMI, Hardware.DISK}) {
            int index = hardware.ordinal(); var pos = fixture.controller().offset(index % 7, index / 7, 0);
            openPanel(context, world, pos);
            context.runOnClient(client -> {
                var panel = (dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen) client.gui.screen();
                String body = panel.model().body();
                if (body.contains("Installed:") || body.contains("Detected components:"))
                    throw new AssertionError("Only the controller lists components: " + body);
            });
            context.takeScreenshot(TestScreenshotOptions.of("i86-panel-" + hardware.id).withSize(1280, 720));
            closeScreen(context); connection.waitForServerboundPackets();
        }
        server.runCommand("tp @a 9.5 96 0 0 0"); connection.waitForClientboundPackets();
        server.runOnServer(host -> PortDialogs.open(host.getPlayerList().getPlayers().getFirst(), (PortBlockEntity) host.overworld().getBlockEntity(fixture.sign().west())));
        context.waitFor(client -> client.gui.screen() instanceof dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen);
        context.takeScreenshot(TestScreenshotOptions.of("i86-sign-port-settings").withSize(1280, 720));
        context.runOnClient(client -> {
            for (var child : client.gui.screen().children()) if (child instanceof net.minecraft.client.gui.components.EditBox edit && edit.getValue().equals("Font port")) edit.setValue("Front display");
            if (!press(client.gui.screen(), "Apply")) throw new AssertionError("Port Apply missing");
        });
        server.waitFor(host -> ((PortBlockEntity) host.overworld().getBlockEntity(fixture.sign().west())).settings().name().equals("Front display"));
        server.runOnServer(host -> {
            var player = host.getPlayerList().getPlayers().getFirst(); player.getInventory().add(new ItemStack(ModContent.ITEMS.get("rom_chip")));
            ServerMachines.get(host).burner.open(player, fixture.sign().west().above(2));
        });
        context.waitFor(client -> client.gui.screen() instanceof dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen);
        context.takeScreenshot(TestScreenshotOptions.of("i86-rom-burner").withSize(1280, 720));
        context.runOnClient(client -> { if (!press(client.gui.screen(), "Browse ROM library")) throw new AssertionError("Library action missing"); });
        context.waitFor(client -> client.gui.screen() instanceof dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen panel && panel.model().title().equals("ROM library"));
        context.takeScreenshot(TestScreenshotOptions.of("i86-rom-library").withSize(1280, 720));
        context.runOnClient(client -> {
            var panel = (dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen) client.gui.screen();
            String label = panel.model().actions().stream().filter(action -> action.action().equals("select")).findFirst().orElseThrow().label();
            if (!press(panel, label)) throw new AssertionError("ROM selection button missing");
        });
        context.waitFor(client -> client.gui.screen() instanceof dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen panel && panel.model().actions().stream().anyMatch(action -> action.action().equals("burn")));
        context.runOnClient(client -> { if (!press(client.gui.screen(), "Burn blank chip")) throw new AssertionError("Burn action missing"); });
        server.waitFor(host -> host.getPlayerList().getPlayers().getFirst().getInventory().getNonEquipmentItems().stream().anyMatch(stack -> stack.has(ModComponents.ROM_ID)));
        context.waitFor(client -> client.gui.screen() instanceof dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen);
        context.runOnClient(client -> { client.options.guiScale().set(3); client.resizeGui(); });
        context.waitTicks(3); context.takeScreenshot(TestScreenshotOptions.of("i86-small-screen").withSize(1280, 720));
        context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
        closeScreen(context); connection.waitForServerboundPackets();
        server.runCommand("tp @a 5.5 96 -10 0 3"); connection.waitForClientboundPackets();
        System.out.println("I86_CUSTOM_UI_OK: all item names, chip-icon tab, controller rename, installation bay, component panels, port apply and ROM library/burn");
    }
    private static void openPanel(ClientGameTestContext context, TestSingleplayerContext world, BlockPos pos) {
        world.getServer().runOnServer(host -> MachineDialogs.open(host.getPlayerList().getPlayers().getFirst(), pos));
        world.getConnection().waitForClientboundPackets();
        context.waitFor(client -> client.gui.screen() instanceof dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen);
    }
    private static void closeScreen(ClientGameTestContext context) { context.runOnClient(client -> { if (client.gui.screen() != null) client.gui.screen().onClose(); }); }

    private static Fixture build(MinecraftServer server) throws Exception {
        var level = server.overworld(); var owner = ServerMachines.get(server); var origin = new BlockPos(1, 96, 1);
        for (int i = 0; i < 21; i++) {
            var block = ModContent.BLOCKS.get(i < Hardware.values().length ? Hardware.values()[i].id : "super_structure");
            level.setBlock(origin.offset(i % 7, i / 7, 0), block.defaultBlockState().setValue(MachineBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
        }
        var controller = (ControllerBlockEntity) level.getBlockEntity(origin); owner.attach(controller);
        var player = server.getPlayerList().getPlayers().getFirst(); player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        player.getAbilities().flying = true; player.onUpdateAbilities();
        int shared = Hardware.SHARED_MEMORY.ordinal();
        ((ArrayBlockEntity) level.getBlockEntity(origin.offset(shared % 7, shared / 7, 0))).memoryId("live_client", player.getUUID());
        byte[] image = new byte[256 * 1024]; int reset = image.length - 16;
        image[reset] = (byte) 0xB8; image[reset + 1] = 0x57; image[reset + 2] = 0x13;
        image[reset + 3] = 15; image[reset + 4] = 1; image[reset + 5] = (byte) 0xEB; image[reset + 6] = (byte) 0xFC;
        UUID rom;
        try { rom = owner.roms.upload(image, "Live client ROM", "MOV AX; yield tick; loop").id(); }
        catch (RomLibrary.DuplicateRom duplicate) { rom = duplicate.existing.id(); }
        controller.inventory.setItem(0, RomChips.create(owner.roms.entry(rom).orElseThrow(), false));
        controller.inventory.setItem(1, new ItemStack(ModContent.ITEMS.get("speed_shard_1")));
        var signPortPos = new BlockPos(9, 96, 1); var signPos = signPortPos.east();
        level.setBlock(signPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(signPos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8), Block.UPDATE_ALL);
        level.setBlock(signPortPos, ModContent.BLOCKS.get("sign_port").defaultBlockState().setValue(PortBlock.PORT_FACING, Direction.EAST), Block.UPDATE_ALL);
        owner.ports.configure((PortBlockEntity) level.getBlockEntity(signPortPos), new PortBindings.Settings(controller.id(), 0, "Font port", true, RedstoneIrqMode.DISABLED, 3), player.getUUID());
        var redstonePos = signPortPos.above();
        level.setBlock(redstonePos.east(), Blocks.REDSTONE_LAMP.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(redstonePos, ModContent.BLOCKS.get("redstone_port").defaultBlockState().setValue(PortBlock.PORT_FACING, Direction.EAST), Block.UPDATE_ALL);
        owner.ports.configure((PortBlockEntity) level.getBlockEntity(redstonePos), new PortBindings.Settings(controller.id(), 0, "Lamp", false, RedstoneIrqMode.DISABLED, 0), player.getUUID());
        level.setBlock(signPortPos.above(2), ModContent.BLOCKS.get("rom_burner").defaultBlockState(), Block.UPDATE_ALL);
        int psu = Hardware.POWER.ordinal();
                var shardOnly = origin.offset(0, 0, 5);
        level.setBlock(shardOnly, ModContent.BLOCKS.get("mainframe_controller").defaultBlockState(), Block.UPDATE_ALL);
        ((ControllerBlockEntity) level.getBlockEntity(shardOnly)).inventory.setItem(1, new ItemStack(ModContent.ITEMS.get("speed_shard_2")));
        return new Fixture(controller.id(), origin, origin.offset(psu % 7, psu / 7, 0).south(), signPos, shardOnly);
    }
    private static void request(ClientGameTestContext context, UUID token, DebuggerProtocol.Action action, int a, int b, int c, String text) throws Exception {
        byte[] bytes = DebuggerProtocol.encode(new DebuggerProtocol.Request(action, a, b, c, text));
        context.runOnClient(client -> ClientPlayNetworking.send(new DebuggerPackets.Request(token, bytes)));
    }
    private static Map<?, ?> sessions() {
        try { Field sessions = ClientDebugger.class.getDeclaredField("SESSIONS"); sessions.setAccessible(true); return (Map<?, ?>) sessions.get(null); }
        catch (ReflectiveOperationException invalid) { throw new IllegalStateException(invalid); }
    }
    private static Object session() { return sessions().values().stream().findFirst().orElse(null); }
    private static Object field(String name) { var session = session(); return session == null ? null : field(session, name); }
    private static Object field(Object session, String name) {
        try { Field field = session.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(session); }
        catch (ReflectiveOperationException invalid) { throw new IllegalStateException(invalid); }
    }
    private static Process child() { return (Process) field("process"); }
    private static UUID token() { return (UUID) Objects.requireNonNull(field("token")); }
    private static boolean press(ContainerEventHandler container, String label) {
        for (var child : container.children()) {
            if (child instanceof Button button && button.active && button.visible && button.getMessage().getString().equals(label)) {
                button.onPress(new MouseButtonInfo(0, 0)); return true;
            }
            if (child instanceof ContainerEventHandler nested && press(nested, label)) return true;
        }
        return false;
    }
}

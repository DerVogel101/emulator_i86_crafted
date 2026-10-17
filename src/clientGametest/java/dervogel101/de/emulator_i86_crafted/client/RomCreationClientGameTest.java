package dervogel101.de.emulator_i86_crafted.client;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.client.screen.ControlPanelScreen;
import dervogel101.de.emulator_i86_crafted.network.*;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import dervogel101.de.emulator_i86_crafted.server.storage.RomUpload;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import java.lang.reflect.Field;
import java.util.*;

public final class RomCreationClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("i86.client.artwork") || Boolean.getBoolean("i86.client.reopen")) return;
        int scale = context.computeOnClient(client -> client.options.guiScale().get());
        context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeGui(); });
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer(); var connection = world.getConnection(); var pos = new BlockPos(0, 100, 0);
            server.runCommand("tp @a 0.5 100 -2 0 0"); connection.waitForClientboundPackets();
            server.runOnServer(host -> {
                host.overworld().setBlockAndUpdate(pos, ModContent.BLOCKS.get("rom_burner").defaultBlockState());
                ServerMachines.get(host).burner.open(host.getPlayerList().getPlayers().getFirst(), pos);
            });
            context.waitFor(client -> client.gui.screen() instanceof ControlPanelScreen);
            context.runOnClient(client -> press((ControlPanelScreen) client.gui.screen(), "Create ROM"));
            context.waitFor(client -> client.gui.screen() instanceof ControlPanelScreen panel && panel.model().title().equals("Create ROM"));
            context.runOnClient(client -> {
                var panel = (ControlPanelScreen) client.gui.screen();
                edit(panel, "Before upload €", "Description before upload\nSecond line"); press(panel, "Done");
            });
            context.waitFor(client -> client.gui.screen() instanceof ControlPanelScreen panel && panel.model().body().contains("Upload a ROM file before"));
            context.runOnClient(client -> assertEdits((ControlPanelScreen) client.gui.screen(), "Before upload €", "Description before upload\nSecond line"));
            byte[] image = new byte[256 * 1024]; new Random().nextBytes(image);
            server.runOnServer(host -> {
                var burner = ServerMachines.get(host).burner; var player = host.getPlayerList().getPlayers().getFirst();
                UUID token = burner.prepareUpload(player, "Before upload €", "Description before upload\nSecond line");
                for (int i = 0; i < RomUpload.CHUNKS; i++) burner.chunk(player, new RomPackets.Chunk(token, i,
                        Arrays.copyOfRange(image, i * RomUpload.CHUNK_BYTES, (i + 1) * RomUpload.CHUNK_BYTES)));
            });
            context.waitFor(client -> client.gui.screen() instanceof ControlPanelScreen panel && panel.model().body().contains("ROM file uploaded"));
            context.runOnClient(client -> {
                var panel = (ControlPanelScreen) client.gui.screen();
                assertEdits(panel, "Before upload €", "Description before upload\nSecond line");
                edit(panel, "Final ROM €", "Final description\nAfter upload");
            });
            context.takeScreenshot(TestScreenshotOptions.of("i86-create-rom").withSize(1280, 720));
            context.runOnClient(client -> press((ControlPanelScreen) client.gui.screen(), "Done"));
            server.waitFor(host -> ServerMachines.get(host).roms.search("Final ROM €").stream()
                    .anyMatch(entry -> entry.description().equals("Final description\nAfter upload")));
            context.waitFor(client -> client.gui.screen() instanceof ControlPanelScreen panel && panel.model().title().equals("ROM burner"));
            context.runOnClient(client -> press((ControlPanelScreen) client.gui.screen(), "Create ROM"));
            context.waitFor(client -> client.gui.screen() instanceof ControlPanelScreen panel && panel.model().title().equals("Create ROM"));
            context.runOnClient(client -> { var panel = (ControlPanelScreen) client.gui.screen(); edit(panel, "Discarded", "Discarded description"); press(panel, "Cancel"); });
            context.waitFor(client -> client.gui.screen() instanceof ControlPanelScreen panel && panel.model().title().equals("ROM burner"));
            server.runOnServer(host -> { if (!ServerMachines.get(host).roms.search("Discarded").isEmpty()) throw new AssertionError("Cancel saved a ROM"); });
            context.runOnClient(client -> client.gui.screen().onClose()); connection.waitForServerboundPackets();
            context.runOnClient(client -> {
                var model = new ControlPanel(ControlPanel.Kind.BURNER, UUID.randomUUID(), "rom_burner", "Scroll regression",
                        "Long body\n".repeat(80), List.of(ControlPanel.text("description", "Description", "Short text", 4096)),
                        List.of(new ControlPanel.Action("Test", "test", Map.of())));
                var panel = new ControlPanelScreen(model); client.gui.setScreen(panel);
                var description = (MultiLineEditBox) panel.children().stream().filter(MultiLineEditBox.class::isInstance).findFirst().orElseThrow();
                panel.mouseScrolled(description.getX() + 10, description.getY() + 10, 0, -1);
                if (number(panel, "scroll") == 0) throw new AssertionError("Short description swallowed panel scrolling");
                int before = number(panel, "scroll");
                panel.mouseScrolled(number(panel, "x") + 30, number(panel, "contentTop") + 20, 0, -1);
                if (number(panel, "scroll") <= before) throw new AssertionError("Body scrolling requires the scrollbar");
                panel.mouseScrolled(number(panel, "x") + 30, number(panel, "contentTop") + 20, 0, 2);
                description.setValue("Long description\n".repeat(40)); description.setScrollAmount(0);
                before = number(panel, "scroll");
                panel.mouseScrolled(description.getX() + 10, Math.max(number(panel, "contentTop") + 1, description.getY() + 10), 0, -1);
                if (description.scrollAmount() == 0 || number(panel, "scroll") != before) throw new AssertionError("Long description must scroll internally first");
                description.setScrollAmount(description.maxScrollAmount());
                panel.mouseScrolled(description.getX() + 10, Math.max(number(panel, "contentTop") + 1, description.getY() + 10), 0, -1);
                if (number(panel, "scroll") <= before) throw new AssertionError("Description boundary swallowed panel scrolling");
                panel.onClose();
            });
            System.out.println("I86_ROM_CREATION_OK: current metadata, multiline text, staging, Done, Cancel, body/editor wheel scrolling");
        } catch (Exception failure) { throw new IllegalStateException("ROM creation client check failed", failure); }
        finally { context.runOnClient(client -> { client.options.guiScale().set(scale); client.resizeGui(); }); }
    }
    private static void edit(ControlPanelScreen panel, String name, String description) {
        for (var child : panel.children()) {
            if (child instanceof EditBox edit) edit.setValue(name);
            if (child instanceof MultiLineEditBox edit) edit.setValue(description);
        }
    }
    private static void assertEdits(ControlPanelScreen panel, String name, String description) {
        for (var child : panel.children()) {
            if (child instanceof EditBox edit && !edit.getValue().equals(name)) throw new AssertionError("Name was lost");
            if (child instanceof MultiLineEditBox edit && !edit.getValue().equals(description)) throw new AssertionError("Description was lost");
        }
    }
    private static void press(ControlPanelScreen panel, String label) {
        for (var child : panel.children()) if (child instanceof Button button && button.getMessage().getString().equals(label)) {
            button.onPress(new MouseButtonInfo(0, 0)); return;
        }
        throw new AssertionError("Missing button: " + label);
    }
    private static int number(ControlPanelScreen panel, String name) {
        try { Field field = ControlPanelScreen.class.getDeclaredField(name); field.setAccessible(true); return field.getInt(panel); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}

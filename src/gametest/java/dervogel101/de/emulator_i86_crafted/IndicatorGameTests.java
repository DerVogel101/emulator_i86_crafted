package dervogel101.de.emulator_i86_crafted;

import dervogel101.de.emulator_i86_crafted.network.IndicatorPackets;
import dervogel101.de.emulator_i86_crafted.server.ServerIndicators;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import dervogel101.de.emulator_i86_crafted.server.storage.WorldRecords;
import dervogel101.de.i86.runtime.*;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import java.util.List;

public final class IndicatorGameTests {
    @GameTest(maxTicks = 200) public void saveIndicatorsFollowActualPersistenceSuccessAndFailure(GameTestHelper test) throws Exception {
        var wall = new MachineGameTests.Wall(test, 38912);
        var owner = wall.machines; var server = test.getLevel().getServer();
        test.runAfterDelay(110, () -> {
            Object original = null;
            java.lang.reflect.Field store = null;
            try {
                store = ServerMachines.class.getDeclaredField("store"); store.setAccessible(true); original = store.get(owner);
                var save = ServerMachines.class.getDeclaredMethod("save"); save.setAccessible(true);
                var publish = ServerMachines.class.getDeclaredMethod("publishIndicators", net.minecraft.server.MinecraftServer.class); publish.setAccessible(true);
                var good = java.nio.file.Files.createTempDirectory(owner.folder, "led-save-success-");
                var goodStore = new dervogel101.de.emulator_i86_crafted.server.storage.WorldStore(good);
                wall.record().nvram[0] = 0x5a;
                store.set(owner, goodStore); save.invoke(owner);
                owner.indicators.begin(); publish.invoke(owner, server);
                var nvram = wall.part(Hardware.NVRAM); var disk = wall.part(Hardware.DISK);
                test.assertTrue((owner.indicators.state(test.getLevel(), nvram) & 16) != 0, "Successful world write lights NVRAM save segment");
                test.assertTrue((owner.indicators.state(test.getLevel(), disk) & 16) != 0, "Successful world write lights disk save segment");
                test.assertValueEqual(goodStore.load().mainframe(wall.record().id).orElseThrow().nvram[0], (byte) 0x5a, "NVRAM reaches the saved snapshot");
                owner.indicators.begin(); publish.invoke(owner, server);
                test.assertValueEqual(owner.indicators.state(test.getLevel(), nvram) & 16, 0, "Save indication expires without another save");

                var bad = java.nio.file.Files.createTempDirectory(owner.folder, "led-save-failure-");
                var badStore = new dervogel101.de.emulator_i86_crafted.server.storage.WorldStore(bad);
                java.nio.file.Files.delete(bad); // This fixture directory is empty and owned by this test.
                java.nio.file.Files.writeString(bad, "blocked save directory");
                store.set(owner, badStore); save.invoke(owner);
                owner.indicators.begin(); publish.invoke(owner, server);
                test.assertValueEqual(owner.indicators.state(test.getLevel(), nvram) & 3, IndicatorState.FAILURE, "An actual failed write latches red status");
                test.assertValueEqual(owner.indicators.state(test.getLevel(), disk) & 3, IndicatorState.FAILURE, "Disk also reports failed persistence");
                test.assertValueEqual(owner.indicators.state(test.getLevel(), nvram) & 16, 0, "Failed write must not claim successful persistence");
                test.assertValueEqual(goodStore.load().mainframe(wall.record().id).orElseThrow().nvram[0], (byte) 0x5a, "Failure preserves the previous saved snapshot");

                store.set(owner, goodStore); save.invoke(owner);
                owner.indicators.begin(); publish.invoke(owner, server);
                test.assertTrue((owner.indicators.state(test.getLevel(), nvram) & 3) != IndicatorState.FAILURE, "Successful retry clears NVRAM failure");
                test.assertTrue((owner.indicators.state(test.getLevel(), disk) & 3) != IndicatorState.FAILURE, "Successful retry clears disk failure");
                test.assertTrue((owner.indicators.state(test.getLevel(), nvram) & 16) != 0, "Successful retry reports save completion");
            } catch (ReflectiveOperationException | java.io.IOException failure) {
                throw new AssertionError("Persistence indicator fixture failed", failure);
            } finally {
                try { if (store != null && original != null) store.set(owner, original); }
                catch (IllegalAccessException failure) { throw new AssertionError(failure); }
                wall.close();
            }
            test.succeed();
        });
    }

    @GameTest public void fourLampPacketsAreBoundedAndRoundTrip(GameTestHelper test) {
        var packet = new IndicatorPackets.Update("minecraft:overworld", List.of(
                new IndicatorPackets.Entry(new BlockPos(-20, 96, 100), IndicatorState.pack(3, 7) | IndicatorState.SIGN_WRITE),
                new IndicatorPackets.Entry(BlockPos.ZERO, IndicatorState.REMOVE)));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), test.getLevel().registryAccess());
        try {
            IndicatorPackets.Update.CODEC.encode(buffer, packet);
            test.assertValueEqual(IndicatorPackets.Update.CODEC.decode(buffer), packet, "LED batch codec round-trips");
            try { new IndicatorPackets.Entry(BlockPos.ZERO, 64); test.fail("Reserved flags accepted"); }
            catch (IllegalArgumentException expected) { }
            try { new IndicatorPackets.Update("x", java.util.Collections.nCopies(IndicatorPackets.LIMIT + 1, packet.entries().getFirst())); test.fail("Unbounded batch accepted"); }
            catch (IllegalArgumentException expected) { }
        } finally { buffer.release(); }
        test.succeed();
    }

    @GameTest public void failuresOverrideOffAndSuccessfulPersistenceCanClearThem(GameTestHelper test) {
        var record = new WorldRecords().allocate();
        test.assertValueEqual(ServerMachines.indicatorStatus(record, CpuActivity.Snapshot.EMPTY), IndicatorState.OFF, "Default is dark");
        record.faulted = true;
        test.assertValueEqual(ServerMachines.indicatorStatus(record, CpuActivity.Snapshot.EMPTY), IndicatorState.FAILURE, "Fault remains red after shutdown");
        var pos = test.absolutePos(new BlockPos(2, 2, 2));
        test.setBlock(new BlockPos(2, 2, 2), ModContent.BLOCKS.get("integrated_storage"));
        var lights = new ServerIndicators();
        lights.failure(test.getLevel(), pos, true); lights.flush(test.getLevel().getServer());
        test.assertValueEqual(lights.state(test.getLevel(), pos) & 3, IndicatorState.FAILURE, "Persistence failure is latched");
        lights.begin(); lights.failure(test.getLevel(), pos, false); lights.set(test.getLevel(), pos, 1, 0); lights.pulse(test.getLevel(), pos, 4);
        lights.flush(test.getLevel().getServer());
        test.assertValueEqual(lights.state(test.getLevel(), pos), IndicatorState.pack(1, 4), "Only successful persistence gets a save pulse");
        lights.begin(); lights.set(test.getLevel(), pos, 1, 0); lights.flush(test.getLevel().getServer());
        test.assertValueEqual(lights.state(test.getLevel(), pos), 1, "Save completion is transient");
        test.succeed();
    }
}

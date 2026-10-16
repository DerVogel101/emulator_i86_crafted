package dervogel101.de.emulator_i86_crafted.server;

import dervogel101.de.i86.runtime.ShardSpeed;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public record MachineConfig(List<ShardSpeed> tiers) {
    public MachineConfig { tiers = List.copyOf(tiers); if (tiers.size() != 3) throw new IllegalArgumentException("Three shard tiers required"); }
    public ShardSpeed speed(int tier) { return tiers.get(tier - 1); }
    public static MachineConfig load(Path path) throws IOException {
        var defaults = List.of(ShardSpeed.TIER_1, ShardSpeed.TIER_2, ShardSpeed.TIER_3);
        var properties = new Properties();
        if (Files.exists(path)) try (var reader = Files.newBufferedReader(path)) { properties.load(reader); }
        var tiers = new ArrayList<ShardSpeed>();
        try {
            for (int tier = 1; tier <= 3; tier++) {
                ShardSpeed fallback = defaults.get(tier - 1);
                String steps = "shard" + tier + ".stepsPerTick", batches = "shard" + tier + ".batchesPerTick";
                var speed = new ShardSpeed(Integer.parseInt(properties.getProperty(steps, Integer.toString(fallback.stepsPerTick()))),
                        Integer.parseInt(properties.getProperty(batches, Integer.toString(fallback.batchesPerTick()))));
                tiers.add(speed); properties.setProperty(steps, Integer.toString(speed.stepsPerTick())); properties.setProperty(batches, Integer.toString(speed.batchesPerTick()));
            }
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid mainframe shard settings: " + invalid.getMessage(), invalid); }
        if (!Files.exists(path)) {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (var writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW)) { properties.store(writer, "CPU step budgets expire at each server tick; 1 <= batchesPerTick <= stepsPerTick"); }
        }
        return new MachineConfig(tiers);
    }
}

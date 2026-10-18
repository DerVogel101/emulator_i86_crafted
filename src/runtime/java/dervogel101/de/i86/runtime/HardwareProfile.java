package dervogel101.de.i86.runtime;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record HardwareProfile(Map<Hardware, Integer> counts) {
    public static HardwareProfile complete() {
        var counts = new EnumMap<Hardware, Integer>(Hardware.class);
        // Keep the legacy 21-position default; keyboards need an explicitly installed controller.
        for (var hardware : Hardware.values()) if (hardware != Hardware.KEYBOARD) counts.put(hardware, hardware.maximum);
        return new HardwareProfile(counts);
    }

    public HardwareProfile {
        Objects.requireNonNull(counts, "counts");
        var copy = new EnumMap<Hardware, Integer>(Hardware.class);
        int total = 0;
        for (var entry : counts.entrySet()) {
            var hardware = Objects.requireNonNull(entry.getKey(), "hardware");
            int count = Objects.requireNonNull(entry.getValue(), "count");
            if (count < 0 || count > hardware.maximum) {
                throw new IllegalArgumentException(hardware.displayName + ": maximum " + hardware.maximum);
            }
            if (count != 0) copy.put(hardware, count);
            total += count;
        }
        if (total > 21) throw new IllegalArgumentException("The mainframe has only 21 positions");
        counts = Map.copyOf(copy);
    }

    public int count(Hardware hardware) {
        return counts.getOrDefault(hardware, 0);
    }

    public boolean has(Hardware hardware) {
        return count(hardware) != 0;
    }

    public int redstoneCapacity() {
        return 32 * count(Hardware.GPIO);
    }

    public int signCapacity() {
        return 32 * count(Hardware.GPU);
    }

    public int keyboardCapacity() { return 8 * count(Hardware.KEYBOARD); }

    public Optional<String> validationError() {
        for (var hardware : Hardware.values()) {
            if (hardware.required && !has(hardware)) {
                return Optional.of("Missing " + hardware.displayName);
            }
        }
        return Optional.empty();
    }
}

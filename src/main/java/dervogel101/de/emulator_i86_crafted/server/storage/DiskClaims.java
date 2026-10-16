package dervogel101.de.emulator_i86_crafted.server.storage;

import java.util.*;

/** Powered machines, including unloaded machines, exclusively own their connected disk. */
public final class DiskClaims {
    public record Use(long mainframe, String location) {}
    private final Map<UUID, Use> active = new HashMap<>();

    public Optional<Use> claim(UUID disk, long mainframe, String location) {
        Use current = active.get(disk);
        if (current != null && current.mainframe() != mainframe) return Optional.of(current);
        active.entrySet().removeIf(entry -> entry.getValue().mainframe() == mainframe && !entry.getKey().equals(disk));
        active.put(disk, new Use(mainframe, location)); return Optional.empty();
    }
    public void release(long mainframe) { active.entrySet().removeIf(entry -> entry.getValue().mainframe() == mainframe); }
}

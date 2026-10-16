package dervogel101.de.emulator_i86_crafted.server;

import java.util.*;

/** Opening order is the handover queue; a viewer never gains a second queue entry. */
public final class DebuggerLeases {
    private final Map<Long, LinkedHashSet<UUID>> viewers = new HashMap<>();
    public void open(long mainframe, UUID player) { viewers.computeIfAbsent(mainframe, ignored -> new LinkedHashSet<>()).add(player); }
    public void close(long mainframe, UUID player) {
        var queue = viewers.get(mainframe);
        if (queue != null) { queue.remove(player); if (queue.isEmpty()) viewers.remove(mainframe); }
    }
    public void disconnect(UUID player) { for (long mainframe : List.copyOf(viewers.keySet())) close(mainframe, player); }
    public Optional<UUID> owner(long mainframe) {
        var queue = viewers.get(mainframe); return queue == null || queue.isEmpty() ? Optional.empty() : Optional.of(queue.getFirst());
    }
    public boolean canControl(long mainframe, UUID player) { return owner(mainframe).map(player::equals).orElse(true); }
}

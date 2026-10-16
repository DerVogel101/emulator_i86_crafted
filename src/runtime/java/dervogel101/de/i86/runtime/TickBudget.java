package dervogel101.de.i86.runtime;

/** Server-tick grants are consumed by one worker, never accumulated or replayed. */
public final class TickBudget {
    public enum Outcome { CONTINUE, YIELD_BATCH, YIELD_TICK }

    public record Batch(long epoch, int index, int steps, long deadlineNanos) {}

    private long epoch;
    private long startNanos;
    private long durationNanos;
    private ShardSpeed speed = ShardSpeed.TIER_1;
    private int nextBatch;
    private int activeBatch = -1;
    private boolean stopped = true;

    public synchronized void begin(long startNanos, long durationNanos, ShardSpeed speed) {
        if (durationNanos < speed.batchesPerTick()) {
            throw new IllegalArgumentException("Tick duration must allow at least one nanosecond per batch");
        }
        // A ratio multiply below is bounded even for extreme supplied settings.
        Math.multiplyExact(durationNanos, speed.batchesPerTick());
        Math.addExact(startNanos, durationNanos);
        epoch++;
        this.startNanos = startNanos;
        this.durationNanos = durationNanos;
        this.speed = speed;
        nextBatch = 0;
        activeBatch = -1;
        stopped = false;
    }

    public synchronized Batch take(long nowNanos) {
        if (stopped) return null;
        long elapsed = nowNanos - startNanos;
        if (elapsed < 0 || elapsed >= durationNanos) return null;
        int due = (int) (elapsed * speed.batchesPerTick() / durationNanos);
        if (due < nextBatch) return null;
        // Missed opportunities expire; a late worker receives only the current slot.
        nextBatch = due + 1;
        activeBatch = due;
        long deadline = startNanos + durationNanos * (due + 1) / speed.batchesPerTick();
        return new Batch(epoch, due, speed.batchSteps(due), deadline);
    }

    public synchronized boolean valid(Batch batch, long nowNanos) {
        return !stopped && batch.epoch() == epoch && batch.index() == activeBatch
                && nowNanos < batch.deadlineNanos()
                && nowNanos >= startNanos;
    }

    public synchronized void yielded(Batch batch, Outcome outcome) {
        if (batch.epoch() != epoch || batch.index() != activeBatch) return;
        if (outcome == Outcome.YIELD_BATCH) activeBatch = -1;
        if (outcome == Outcome.YIELD_TICK) stopped = true;
    }

    public synchronized void stop() {
        stopped = true;
        epoch++;
    }
}

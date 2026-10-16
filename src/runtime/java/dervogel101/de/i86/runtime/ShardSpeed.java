package dervogel101.de.i86.runtime;

public record ShardSpeed(int stepsPerTick, int batchesPerTick) {
    public static final ShardSpeed TIER_1 = new ShardSpeed(1_000, 10);
    public static final ShardSpeed TIER_2 = new ShardSpeed(5_000, 10);
    public static final ShardSpeed TIER_3 = new ShardSpeed(10_000, 10);

    public ShardSpeed {
        if (stepsPerTick < 1 || batchesPerTick < 1 || batchesPerTick > stepsPerTick) {
            throw new IllegalArgumentException("Require 1 <= batchesPerTick <= stepsPerTick");
        }
    }

    public int batchSteps(int index) {
        if (index < 0 || index >= batchesPerTick) throw new IndexOutOfBoundsException(index);
        return stepsPerTick / batchesPerTick + (index < stepsPerTick % batchesPerTick ? 1 : 0);
    }
}

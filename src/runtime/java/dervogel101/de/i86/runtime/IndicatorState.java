package dervogel101.de.i86.runtime;

/** Wire format and client decay for four lamps; no Minecraft or persistent state. */
public final class IndicatorState {
    public static final int OFF = 0, RUNNING = 1, IDLE = 2, FAILURE = 3, REMOVE = 255;
    public static final long FADE_NANOS = 75_000_000L;
    private IndicatorState() {}
    public static int pack(int status, int activity) { return status | ((activity & 7) << 2); }

    public static final class Lamps {
        private int flags;
        private final long[] released = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        public void update(int value, long now) {
            for (int i = 0; i < 3; i++) {
                int bit = 1 << (i + 2);
                if ((value & bit) != 0) released[i] = Long.MIN_VALUE;
                else if ((flags & bit) != 0) released[i] = now;
            }
            flags = value;
        }
        public int status() { return flags & 3; }
        public float brightness(int slot, long now) {
            if (slot == 0) return status() == OFF ? 0 : 1;
            if ((flags & (1 << (slot + 1))) != 0) return 1;
            long start = released[slot - 1];
            if (start == Long.MIN_VALUE) return 0;
            float t = Math.clamp((float) (now - start) / FADE_NANOS, 0, 1);
            return 1 - t * t * (3 - 2 * t);
        }
    }
}

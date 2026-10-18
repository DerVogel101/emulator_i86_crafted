package dervogel101.de.i86.runtime;

/** Coalesces the existing indicator stream; sound playback never needs its own packets. */
public final class SoundActivity {
    public static final int START = 16, STOP = 32, FAULT = 64;
    private int flags = -1, pending, transition;
    private long nextActivity, nextTransition;

    public void update(int value) {
        int status = value & 3;
        if (flags != -1 && status != (flags & 3)) {
            if (status == IndicatorState.OFF) transition = STOP;
            else if (status == IndicatorState.FAILURE) transition = FAULT;
            else if ((flags & 3) == IndicatorState.OFF || (flags & 3) == IndicatorState.FAILURE) transition = START;
        }
        flags = value;
        if (status == IndicatorState.OFF || status == IndicatorState.FAILURE) pending = 0;
        else pending |= value >>> 2;
    }

    public boolean powered() {
        return flags != -1 && ((flags & 3) == IndicatorState.RUNNING || (flags & 3) == IndicatorState.IDLE);
    }

    public int poll(long tick, int interval, int mask) {
        int result = 0;
        if (transition != 0 && tick >= nextTransition) {
            result = transition;
            transition = 0;
            nextTransition = tick + 10;
        }
        if (tick >= nextActivity) {
            if (powered()) result |= (pending | flags >>> 2) & mask;
            pending = 0;
            nextActivity = tick + interval;
        }
        return result;
    }
}

package dervogel101.de.i86.runtime;

import java.util.function.LongSupplier;

/** A shared simulated server clock; pausing an individual CPU does not pause it. */
public final class VirtualGameClock {
    private final LongSupplier nanos;
    private long last;
    private long ticks;
    private long fraction;
    private int rate;

    public VirtualGameClock(int rate) {
        this(System::nanoTime, rate);
    }

    public VirtualGameClock(LongSupplier nanos, int rate) {
        this.nanos = nanos;
        this.last = nanos.getAsLong();
        setRate(rate);
    }

    public synchronized void setRate(int rate) {
        if (rate < 1 || rate > 1_000) throw new IllegalArgumentException("Require 1 to 1000 ticks/s");
        advance();
        this.rate = rate;
    }

    public synchronized long ticks() {
        advance();
        return ticks;
    }

    public synchronized void restoreTicks(long savedTicks) {
        if (savedTicks < 0) throw new IllegalArgumentException("Negative world tick");
        ticks = savedTicks;
        fraction = 0;
        last = nanos.getAsLong();
    }

    private void advance() {
        long now = nanos.getAsLong();
        long elapsed = Math.max(0, now - last);
        last = now;
        ticks += elapsed / 1_000_000_000L * rate;
        fraction += elapsed % 1_000_000_000L * rate;
        ticks += fraction / 1_000_000_000L;
        fraction %= 1_000_000_000L;
    }
}

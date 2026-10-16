package dervogel101.de.i86.runtime;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One CPU worker and one pending wake-up; all step budgets come from host ticks. */
public final class BatchedCpuWorker implements AutoCloseable {
    private final ScheduledThreadPoolExecutor executor;
    private final Runnable beforeBatch;
    private final Supplier<TickBudget.Outcome> step;
    private final Consumer<Throwable> fault;
    private final TickBudget budget = new TickBudget();
    private final int phaseSeed;
    private ScheduledFuture<?> wakeup;
    private long generation;
    private long start;
    private long duration;
    private long phase;
    private ShardSpeed speed;
    private boolean running;
    private boolean failed;

    public BatchedCpuWorker(String name, int phaseSeed, Runnable beforeBatch,
                            Supplier<TickBudget.Outcome> step, Consumer<Throwable> fault) {
        this.beforeBatch = beforeBatch;
        this.step = step;
        this.fault = fault;
        this.phaseSeed = phaseSeed;
        executor = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
    }

    public synchronized void beginTick(long startNanos, long durationNanos, ShardSpeed speed) {
        if (failed) return;
        budget.begin(startNanos, durationNanos, speed);
        if (wakeup != null) wakeup.cancel(false);
        generation++;
        start = startNanos;
        duration = durationNanos;
        this.speed = speed;
        phase = durationNanos / speed.batchesPerTick() * Math.floorMod(phaseSeed, 1_024) / 1_024;
        running = true;
        schedule(generation, start + phase);
    }

    private void schedule(long expectedGeneration, long when) {
        wakeup = executor.schedule(() -> runBatch(expectedGeneration),
                Math.max(0, when - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    private void runBatch(long expectedGeneration) {
        TickBudget.Batch batch;
        synchronized (this) {
            if (!running || generation != expectedGeneration) return;
            wakeup = null;
            batch = budget.take(System.nanoTime());
        }
        if (batch == null) return;
        try {
            beforeBatch.run();
            for (int remaining = batch.steps(); remaining > 0 && budget.valid(batch, System.nanoTime()); remaining--) {
                var outcome = step.get();
                if (outcome != TickBudget.Outcome.CONTINUE) {
                    budget.yielded(batch, outcome);
                    if (outcome == TickBudget.Outcome.YIELD_TICK) {
                        synchronized (this) {
                            if (generation == expectedGeneration) running = false;
                        }
                        return;
                    }
                    break;
                }
            }
        } catch (Throwable failure) {
            synchronized (this) {
                failed = true;
                stop();
            }
            fault.accept(failure);
            return;
        }
        synchronized (this) {
            if (!running || generation != expectedGeneration) return;
            long now = System.nanoTime();
            long elapsed = now - start;
            if (elapsed >= duration) return;
            int next = Math.max(batch.index() + 1, (int) (elapsed * speed.batchesPerTick() / duration));
            if (next < speed.batchesPerTick()) {
                schedule(expectedGeneration, start + duration * next / speed.batchesPerTick() + phase);
            }
        }
    }

    public synchronized void stop() {
        running = false;
        generation++;
        budget.stop();
        if (wakeup != null) {
            wakeup.cancel(false);
            wakeup = null;
        }
    }

    /** A snapshot/control action queued after the currently executing instruction. */
    public <T> Future<T> submit(Callable<T> action) {
        return executor.submit(action);
    }

    @Override
    public void close() {
        stop();
        executor.shutdown();
    }
}

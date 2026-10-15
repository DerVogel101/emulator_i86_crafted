# Batched CPU scheduling

This is the planned mod scheduler contract. No mod CPU scheduler is implemented
yet. See [mod_behavior.md](mod_behavior.md) for the host lifecycle and bridge.

## Confirmed requirements

- A speed shard (Clock Crystal) specifies the maximum number of `step()` calls
  granted per server tick, and configurable subdivision into smaller batches.
- Pace batches across the tick interval to distribute CPU work over time. Running
  all smaller batches immediately in one loop would not meet this requirement.
- Execute CPU work on its worker, not the server thread. Server ticks submit input
  state; worker-side instruction boundaries apply it; server ticks apply outputs.
- Never carry unfinished work into the next tick. Late, skipped, yielded, or
  otherwise unused step budget expires with its originating tick.
- Paused, powered-off, unloaded, or faulted CPUs do not accumulate execution credit.
- Check run state, tick validity, and remaining budget between `step()` calls.
  Discard work from expired ticks rather than allowing stale queued batches to run.
- Batching and yielding do not freeze timers: real-time timers use elapsed real
  time, and tick timers use elapsed world/server ticks.

## Per-tier configuration

The base configuration uses ten batches per tick for each tier. Both the total
step budget and batch count remain configurable per speed shard:

| Tier | `stepsPerTick` default | `batchesPerTick` default | Steps per batch at base settings |
| --- | ---: | ---: | ---: |
| 1 | 1,000 | 10 | 100 |
| 2 | 5,000 | 10 | 500 |
| 3 | 10,000 | 10 | 1,000 |

Each tier has its own batch setting. Use positive integer values with
`1 <= batchesPerTick <= stepsPerTick`. If division is uneven, spread the remainder
across batches so their budgets differ by at most one step and sum to the exact
tick budget. This avoids accidentally granting extra steps through rounding.

For ten batches during a target 50 ms tick, batch opportunities are approximately
5 ms apart. Use the server's configured tick duration rather than assuming every
server is permanently at 20 ticks/second. Different mainframes should be staggered
so their workers do not all wake at the same instant.

## Deadline and overload behavior

A tick grants at most its configured step count; it does not guarantee that the
worker has enough time to complete it. A late tick or slow instruction must not
produce an unbounded catch-up queue. Steps execute sequentially on that CPU's
worker; batches for one CPU must never execute concurrently.

The host needs a tick identity/deadline for each scheduled batch. Once the tick
expires or a new tick supersedes it, stop admitting old steps. A step already in
progress completes at its normal instruction boundary; no instruction is torn
apart halfway through execution. Skip missed batch opportunities rather than
replaying a burst of overdue batches.

## Yield effects

- **Batch yield:** finish the yield instruction, discard this batch's remaining
  steps, and wait for a later scheduled batch in the same tick. It does not move
  discarded steps into that later batch.
- **Tick yield:** finish the yield instruction and discard every remaining batch
  opportunity for that CPU in the current tick. Resume no earlier than its next
  eligible tick.
- A yield instruction itself consumes one `step()` call. Yielding the last batch
  naturally leaves no further work until the next eligible tick.
- Yield is a scheduling request, not `HLT`, CPU Reset, or Power Cycle. Device and
  memory state remain intact. It must return promptly rather than sleeping or
  busy-waiting inside the emulator core.

See [yield_instruction.md](yield_instruction.md) for the confirmed encoding and
architectural effects. The standalone launcher must also handle both yield modes
and discard the corresponding unused batch/tick budget. It must not treat a yield
as a no-op or a halted CPU. Precise worker dispatch and staggering are implementation
details that must satisfy the pacing and deadline rules above.

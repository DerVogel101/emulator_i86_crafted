package dervogel101.de.i86.runtime;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class RuntimeSelfTest {
    public static void main(String[] args) throws Exception {
        hardwareRules();
        unevenBudgets();
        pacedSlots();
        yieldsAndExpiry();
        wallFormation();
        wallDiagnostics();
        workerTickYield();
        workerFault();
        virtualClock();
        clockRestoreAndPortIrqs();
        dervogel101.de.launcher.DebuggerProtocolSelfTest.run();
        dervogel101.de.launcher.RemoteDebuggerSelfTest.run();
        StorageSelfTest.run();
        PunchcardSelfTest.run();
        PortSelfTest.run();
        RomUploadSelfTest.run();
        dervogel101.de.emulator_i86_crafted.client.RomChooserSelfTest.run();
        MachineSelfTest.run();
        DebuggerMachineSelfTest.run();
        IndicatorSelfTest.run();
        System.out.println("Runtime checks passed: hardware, capacity, pacing, yields, tick expiry, wall formation, worker");
    }

    private static void hardwareRules() {
        var counts = new EnumMap<Hardware, Integer>(Hardware.class);
        for (var part : Hardware.values()) if (part.required) counts.put(part, 1);
        var minimum = new HardwareProfile(counts);
        assert minimum.validationError().isEmpty();
        assert minimum.redstoneCapacity() == 32;
        assert minimum.signCapacity() == 0;
        var explicitZero = new EnumMap<Hardware, Integer>(counts);
        explicitZero.put(Hardware.GPU, 0);
        assert minimum.equals(new HardwareProfile(explicitZero)) : "Absent modules and zero-count modules are the same profile";
        counts.put(Hardware.GPIO, 4);
        counts.put(Hardware.GPU, 4);
        var expanded = new HardwareProfile(counts);
        assert expanded.redstoneCapacity() == 128;
        assert expanded.signCapacity() == 128;
        assert minimum.redstoneCapacity() == 32 : "Profiles must not share mutable maps";
        counts.put(Hardware.CONTROLLER, 2);
        expectFailure(() -> new HardwareProfile(counts));
        counts.put(Hardware.CONTROLLER, 0);
        assert new HardwareProfile(counts).validationError().orElseThrow().contains("Controller");
    }

    private static void unevenBudgets() {
        var speed = new ShardSpeed(103, 10);
        int total = 0;
        for (int i = 0; i < 10; i++) {
            int steps = speed.batchSteps(i);
            assert steps == (i < 3 ? 11 : 10);
            total += steps;
        }
        assert total == 103;
        expectFailure(() -> new ShardSpeed(10, 11));
        expectFailure(() -> new ShardSpeed(0, 1));
    }

    private static void pacedSlots() {
        var budget = new TickBudget();
        budget.begin(1_000, 100, new ShardSpeed(100, 10));
        assert budget.take(999) == null;
        var first = budget.take(1_000);
        assert first.index() == 0 && first.steps() == 10;
        assert budget.take(1_001) == null : "A slot can only be granted once";
        assert budget.valid(first, 1_009);
        assert !budget.valid(first, 1_010) : "Old batches cannot consume the next slot";
        var late = budget.take(1_055);
        assert late.index() == 5 && late.steps() == 10 : "Missed batches must not be replayed";
        assert budget.take(1_100) == null;
    }

    private static void yieldsAndExpiry() {
        var budget = new TickBudget();
        budget.begin(0, 100, new ShardSpeed(100, 10));
        var first = budget.take(0);
        budget.yielded(first, TickBudget.Outcome.YIELD_BATCH);
        assert !budget.valid(first, 1);
        assert budget.take(1) == null;
        var second = budget.take(10);
        assert second.steps() == 10 : "Yielded work does not grow the next batch";
        budget.yielded(second, TickBudget.Outcome.YIELD_TICK);
        assert budget.take(20) == null;
        assert !budget.valid(second, 11);
        budget.begin(100, 100, new ShardSpeed(100, 10));
        assert !budget.valid(first, 100) : "New ticks invalidate old work";
        budget.yielded(first, TickBudget.Outcome.YIELD_TICK);
        assert budget.take(100) != null : "Old completions cannot cancel a new tick";
        budget.stop();
        assert budget.take(110) == null;
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected invalid input to fail");
        } catch (IllegalArgumentException expected) {
            // The public configuration boundary rejects invalid values.
        }
    }

    private static void wallFormation() {
        for (var facing : WallLayout.Facing.values()) {
            var origin = new WallLayout.Pos(8, 60, 8);
            var grid = wall(origin, facing);
            var controller = origin.offset(facing, 4, 1);
            var layout = WallLayout.find(controller, facing, grid::get).orElseThrow();
            assert layout.positions().size() == 21;
            assert layout.origin().equals(origin);
            assert layout.hardware().redstoneCapacity() == 32;
            var gpio = origin.offset(facing, 0, 0);
            grid.put(gpio, new WallLayout.Cell(null, facing));
            assert WallLayout.find(controller, facing, grid::get).isEmpty() : "GPIO is required";
            grid.put(gpio, new WallLayout.Cell(Hardware.GPIO, facing));
            grid.remove(origin.offset(facing, 1, 0));
            assert WallLayout.find(controller, facing, grid::get).isEmpty() : "No holes in the wall";
        }
        var crossing = new WallLayout.Pos(15, 60, 8);
        var grid = wall(crossing, WallLayout.Facing.NORTH);
        assert WallLayout.find(crossing.offset(WallLayout.Facing.NORTH, 4, 1),
                WallLayout.Facing.NORTH, grid::get).isEmpty() : "One mainframe must fit one chunk";
    }

    private static void wallDiagnostics() {
        for (var facing : WallLayout.Facing.values()) {
            var origin = new WallLayout.Pos(8, 60, 8);
            var grid = wall(origin, facing); var controller = origin.offset(facing, 4, 1);
            var valid = WallLayout.diagnose(controller, facing, grid::get);
            assert valid.layout().equals(WallLayout.find(controller, facing, grid::get)) && valid.issues().isEmpty();
            WallLayout.Pos gpio = origin.offset(facing, 0, 0), firstClock = origin.offset(facing, 1, 0), secondClock = origin.offset(facing, 2, 0);
            WallLayout.Pos wrong = origin.offset(facing, 3, 0), hole = origin.offset(facing, 4, 0);
            grid.put(gpio, new WallLayout.Cell(null, facing));
            grid.put(firstClock, new WallLayout.Cell(Hardware.CLOCK, facing)); grid.put(secondClock, new WallLayout.Cell(Hardware.CLOCK, facing));
            grid.put(wrong, new WallLayout.Cell(null, facing == WallLayout.Facing.NORTH ? WallLayout.Facing.SOUTH : WallLayout.Facing.NORTH)); grid.remove(hole);
            var report = WallLayout.diagnose(controller, facing, grid::get);
            assert report.layout().isEmpty() && WallLayout.find(controller, facing, grid::get).isEmpty();
            assert report.origin().equals(origin) : "Report the existing wall, not an arbitrary shifted rectangle";
            assert report.counts().get(Hardware.CLOCK) == 2 && report.counts().getOrDefault(Hardware.GPIO, 0) == 0 : "Detected counts reflect the current wall";
            assert report.issues().stream().anyMatch(issue -> issue.message().contains("Missing GPIO Controller") && issue.message().contains("found 0"));
            assert report.issues().stream().anyMatch(issue -> issue.message().contains("Too many Atomic Clock") && issue.message().contains("found 2")
                    && issue.positions().containsAll(java.util.List.of(firstClock, secondClock)));
            assert report.issues().stream().anyMatch(issue -> issue.kind() == WallLayout.IssueKind.FACING && issue.positions().contains(wrong));
            assert report.issues().stream().anyMatch(issue -> issue.kind() == WallLayout.IssueKind.BLOCK && issue.positions().contains(hole));
            assert report.issues().stream().noneMatch(issue -> issue.message().startsWith("Missing GPU")) : "Optional hardware is not required";
        }
        var facing = WallLayout.Facing.NORTH;
        var crossing = new WallLayout.Pos(15, 60, 8); var grid = wall(crossing, facing);
        assert WallLayout.diagnose(crossing.offset(facing, 4, 1), facing, grid::get).issues().stream().anyMatch(issue -> issue.kind() == WallLayout.IssueKind.CHUNK);
        var origin = new WallLayout.Pos(8, 60, 8); grid = wall(origin, facing);
        grid.put(origin, new WallLayout.Cell(null, facing)); grid.put(origin.offset(facing, 1, 0), new WallLayout.Cell(Hardware.GPIO, facing));
        for (int y = 0; y < 3; y++) grid.put(origin.offset(facing, 7, y), new WallLayout.Cell(null, facing));
        var controller = origin.offset(facing, 4, 1);
        var ambiguous = WallLayout.diagnose(controller, facing, grid::get);
        assert WallLayout.find(controller, facing, grid::get).isEmpty() && ambiguous.layout().isEmpty();
        assert ambiguous.issues().stream().anyMatch(issue -> issue.kind() == WallLayout.IssueKind.AMBIGUOUS && issue.positions().size() == 2);
        System.out.println("Wall diagnostics passed: all facings, closest area, missing/excess counts, block coordinates, chunk boundary and overlapping walls");
    }

    private static Map<WallLayout.Pos, WallLayout.Cell> wall(WallLayout.Pos origin, WallLayout.Facing facing) {
        var grid = new HashMap<WallLayout.Pos, WallLayout.Cell>();
        for (int y = 0; y < 3; y++) for (int x = 0; x < 7; x++) {
            grid.put(origin.offset(facing, x, y), new WallLayout.Cell(null, facing));
        }
        var required = new Hardware[] { Hardware.GPIO, Hardware.POWER, Hardware.RESET,
                Hardware.NMI, Hardware.TEST, Hardware.CONTROLLER };
        int[] cells = { 0, 6, 13, 15, 20, 11 };
        for (int i = 0; i < cells.length; i++) {
            grid.put(origin.offset(facing, cells[i] % 7, cells[i] / 7), new WallLayout.Cell(required[i], facing));
        }
        return grid;
    }

    private static void workerTickYield() throws Exception {
        var steps = new AtomicInteger();
        var batches = new AtomicInteger();
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        var failure = new CompletableFuture<Throwable>();
        try (var worker = new BatchedCpuWorker("i86-runtime-check", 0, () -> {
            assert Thread.currentThread().getName().equals("i86-runtime-check");
            batches.incrementAndGet();
        }, () -> {
            assert batches.get() == steps.get() + 1 : "One batch hook before each yielded batch";
            int count = steps.incrementAndGet();
            if (count == 1) first.complete(null);
            if (count == 2) second.complete(null);
            return TickBudget.Outcome.YIELD_TICK;
        }, failure::complete)) {
            worker.beginTick(System.nanoTime(), TimeUnit.SECONDS.toNanos(1), ShardSpeed.TIER_3);
            first.get(3, TimeUnit.SECONDS);
            worker.submit(() -> null).get(3, TimeUnit.SECONDS);
            assert steps.get() == 1 : "Tick yield must discard remaining steps and batches";
            worker.beginTick(System.nanoTime(), TimeUnit.SECONDS.toNanos(1), ShardSpeed.TIER_3);
            second.get(3, TimeUnit.SECONDS);
            worker.stop();
            worker.submit(() -> null).get(3, TimeUnit.SECONDS);
            assert steps.get() == 2;
            assert !failure.isDone();
        }
    }

    private static void workerFault() throws Exception {
        var count = new AtomicInteger();
        var fault = new CompletableFuture<Throwable>();
        try (var worker = new BatchedCpuWorker("i86-runtime-fault-check", 0, () -> {}, () -> {
            count.incrementAndGet();
            throw new IllegalStateException("unsupported opcode");
        }, fault::complete)) {
            worker.beginTick(System.nanoTime(), TimeUnit.SECONDS.toNanos(1), ShardSpeed.TIER_1);
            assert fault.get(3, TimeUnit.SECONDS).getMessage().equals("unsupported opcode");
            worker.beginTick(System.nanoTime(), TimeUnit.SECONDS.toNanos(1), ShardSpeed.TIER_1);
            worker.submit(() -> null).get(3, TimeUnit.SECONDS);
            assert count.get() == 1 : "A fault cannot restart on the next host tick";
        }
    }

    private static void virtualClock() {
        var time = new AtomicLong();
        var clock = new VirtualGameClock(time::get, 20);
        time.set(500_000_000L);
        assert clock.ticks() == 10;
        clock.setRate(40);
        time.set(1_000_000_000L);
        assert clock.ticks() == 30;
        time.set(11_000_000_000L);
        assert clock.ticks() == 430 : "World ticks continue without CPU steps";
        expectFailure(() -> clock.setRate(0));
    }

    private static void clockRestoreAndPortIrqs() {
        var nanos = new AtomicLong();
        var clock = new VirtualGameClock(nanos::get, 20);
        nanos.set(75_000_000); assert clock.ticks() == 1;
        clock.restoreTicks(123); assert clock.ticks() == 123;
        nanos.addAndGet(100_000_000); assert clock.ticks() == 125;
        expectFailure(() -> clock.restoreTicks(-1));
        assert HardwareProfile.complete().signCapacity() == 128;
        assert RedstoneIrqMode.HIGH.triggers(0, 5);
        assert !RedstoneIrqMode.HIGH.triggers(5, 6);
        assert RedstoneIrqMode.LOW.triggers(5, 0);
        assert RedstoneIrqMode.BOTH.triggers(0, 1);
        assert RedstoneIrqMode.BOTH.triggers(1, 0);
        assert !RedstoneIrqMode.BOTH.triggers(1, 2);
        assert RedstoneIrqMode.ANALOG.triggers(5, 6);
        assert !RedstoneIrqMode.ANALOG.triggers(5, 5);
        assert !RedstoneIrqMode.DISABLED.triggers(0, 15);
    }
}

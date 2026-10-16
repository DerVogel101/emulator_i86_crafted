package dervogel101.de.i86.runtime;

import java.util.*;
import java.util.function.Function;

public record WallLayout(Pos origin, Facing facing, HardwareProfile hardware, List<Pos> positions) {
    public enum Facing {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);
        private final int dx, dz;
        Facing(int dx, int dz) { this.dx = dx; this.dz = dz; }
    }
    public record Pos(int x, int y, int z) {
        public Pos offset(Facing facing, int across, int up) { return new Pos(x - facing.dz * across, y + up, z + facing.dx * across); }
        public boolean sameChunk(Pos other) { return (x >> 4) == (other.x >> 4) && (z >> 4) == (other.z >> 4); }
    }
    /** A null hardware value is structural filler; a null Cell is not a wall block. */
    public record Cell(Hardware hardware, Facing facing) {}
    public enum IssueKind { BLOCK, FACING, CHUNK, COMPONENT, AMBIGUOUS }
    public record Issue(IssueKind kind, String message, List<Pos> positions) {
        public Issue { positions = List.copyOf(positions); }
    }
    public record Inspection(Optional<WallLayout> layout, Pos origin, Facing facing, List<Issue> issues, Map<Hardware, Integer> counts) {
        public Inspection { issues = List.copyOf(issues); counts = Map.copyOf(counts); }
    }
    private record Candidate(Pos origin, WallLayout layout, List<Issue> issues, int present, int matching, Map<Hardware, Integer> counts) {}

    public WallLayout { positions = List.copyOf(positions); }

    public static Optional<WallLayout> find(Pos controller, Facing facing, Function<Pos, Cell> grid) {
        var anchor = grid.apply(controller);
        if (anchor == null || anchor.hardware() != Hardware.CONTROLLER || anchor.facing() != facing) return Optional.empty();
        WallLayout found = null;
        for (int x = 0; x < 7; x++) for (int y = 0; y < 3; y++) {
            var candidate = inspect(controller.offset(facing, -x, -y), facing, grid, false);
            if (candidate == null) continue;
            // Overlapping valid rectangles must not silently share hardware.
            if (found != null) return Optional.empty();
            found = candidate.layout();
        }
        return Optional.ofNullable(found);
    }

    /** Invalid walls have no fixed origin; report the rectangle with the most existing wall cells. */
    public static Inspection diagnose(Pos controller, Facing facing, Function<Pos, Cell> grid) {
        var anchor = grid.apply(controller);
        if (anchor == null || anchor.hardware() != Hardware.CONTROLLER || anchor.facing() != facing)
            return new Inspection(Optional.empty(), controller, facing, List.of(new Issue(IssueKind.BLOCK,
                    "Expected a Mainframe Controller facing " + facing, List.of(controller))), Map.of());
        Candidate best = null;
        var valid = new ArrayList<Candidate>();
        for (int x = 0; x < 7; x++) for (int y = 0; y < 3; y++) {
            var candidate = inspect(controller.offset(facing, -x, -y), facing, grid, true);
            if (candidate.layout() != null) valid.add(candidate);
            if (best == null || candidate.present() > best.present()
                    || candidate.present() == best.present() && candidate.matching() > best.matching()
                    || candidate.present() == best.present() && candidate.matching() == best.matching() && candidate.issues().size() < best.issues().size())
                best = candidate;
        }
        if (valid.size() == 1) {
            var candidate = valid.getFirst();
            return new Inspection(Optional.of(candidate.layout()), candidate.origin(), facing, List.of(), candidate.counts());
        }
        if (valid.size() > 1)
            return new Inspection(Optional.empty(), best.origin(), facing, List.of(new Issue(IssueKind.AMBIGUOUS,
                    "Ambiguous mainframe: " + valid.size() + " overlapping valid walls. Separate their blocks. Wall origins",
                    valid.stream().map(Candidate::origin).toList())), best.counts());
        return new Inspection(Optional.empty(), best.origin(), facing, best.issues(), best.counts());
    }

    private static Candidate inspect(Pos origin, Facing facing, Function<Pos, Cell> grid, boolean details) {
        var counts = new EnumMap<Hardware, Integer>(Hardware.class);
        var components = new EnumMap<Hardware, List<Pos>>(Hardware.class);
        var positions = new ArrayList<Pos>(21);
        var issues = new ArrayList<Issue>();
        var outsideChunk = new ArrayList<Pos>();
        int present = 0, matching = 0;
        for (int y = 0; y < 3; y++) for (int x = 0; x < 7; x++) {
            var position = origin.offset(facing, x, y); positions.add(position);
            if (!origin.sameChunk(position)) {
                if (!details) return null;
                outsideChunk.add(position);
            }
            var cell = grid.apply(position);
            if (cell == null) {
                if (!details) return null;
                issues.add(new Issue(IssueKind.BLOCK, "Missing or invalid wall block", List.of(position)));
                continue;
            }
            present++;
            if (cell.facing() != facing) {
                if (!details) return null;
                issues.add(new Issue(IssueKind.FACING, "Wrong facing: expected " + facing + ", found " + cell.facing(), List.of(position)));
            } else matching++;
            if (cell.hardware() != null) {
                counts.merge(cell.hardware(), 1, Integer::sum);
                components.computeIfAbsent(cell.hardware(), ignored -> new ArrayList<>()).add(position);
            }
        }
        if (!outsideChunk.isEmpty()) issues.add(new Issue(IssueKind.CHUNK,
                "Wall crosses a chunk boundary; all 21 blocks must fit in one chunk. Positions outside the origin chunk", outsideChunk));
        for (var hardware : Hardware.values()) {
            int count = counts.getOrDefault(hardware, 0);
            if (hardware.required && count == 0) {
                if (!details) return null;
                issues.add(new Issue(IssueKind.COMPONENT, "Missing " + hardware.displayName + ": found 0, required at least 1", List.of()));
            }
            if (count > hardware.maximum) {
                if (!details) return null;
                issues.add(new Issue(IssueKind.COMPONENT, "Too many " + hardware.displayName + ": found " + count
                        + ", maximum " + hardware.maximum + " (remove " + (count - hardware.maximum) + ")", components.get(hardware)));
            }
        }
        var layout = issues.isEmpty() ? new WallLayout(origin, facing, new HardwareProfile(counts), positions) : null;
        return new Candidate(origin, layout, issues, present, matching, Map.copyOf(counts));
    }
}

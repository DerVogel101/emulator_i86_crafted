package dervogel101.de.i86.runtime;

public enum RedstoneIrqMode {
    DISABLED, HIGH, LOW, BOTH, ANALOG;

    public boolean triggers(int previous, int current) {
        return switch (this) {
            case DISABLED -> false;
            case HIGH -> previous == 0 && current != 0;
            case LOW -> previous != 0 && current == 0;
            case BOTH -> (previous == 0) != (current == 0);
            case ANALOG -> previous != current;
        };
    }
}

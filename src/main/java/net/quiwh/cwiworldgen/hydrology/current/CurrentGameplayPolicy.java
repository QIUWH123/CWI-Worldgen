package net.quiwh.cwiworldgen.hydrology.current;

/** Connection-wide gameplay values; client presentation preferences remain local. */
public record CurrentGameplayPolicy(boolean enabled, double playerStrength, double boatStrength,
        double itemDriftSpeed, boolean aquaticHold, boolean boatSurface) {
    public static final CurrentGameplayPolicy WAITING = new CurrentGameplayPolicy(false, 0, 0, 0, true, false);

    public CurrentGameplayPolicy {
        if (!within(playerStrength, 2) || !within(boatStrength, 2) || !within(itemDriftSpeed, 4))
            throw new IllegalArgumentException("Invalid current gameplay policy");
    }
    private static boolean within(double value, double max) { return Double.isFinite(value) && value >= 0 && value <= max; }
}

package net.quiwh.cwiworldgen.hydrology.watershed;

/** Optional immutable source geometry, admitted only against already-fixed river water levels. */
public record SourceMouthProfile(double radius, double transition, double depth, double phase) {
    public static final double MAX_RADIUS = 5.5;
    public static final double MAX_TRANSITION = 104;

    public SourceMouthProfile {
        if (!Double.isFinite(radius + transition + depth + phase) || radius < 3.5 || radius > MAX_RADIUS
                || transition < 64 || transition > MAX_TRANSITION || depth < .65 || depth > .80)
            throw new IllegalArgumentException("Invalid source mouth profile");
    }

    public static SourceMouthProfile make(long seed, double x, double z) {
        long key = mix(seed ^ Double.doubleToRawLongBits(x) ^ Long.rotateLeft(Double.doubleToRawLongBits(z), 27)
                ^ 0x534f555243454d54L);
        return new SourceMouthProfile(3.5 + unit(key) * 2, 64 + unit(mix(key + 1)) * 40,
                .65 + unit(mix(key + 2)) * .15, unit(mix(key + 3)) * Math.PI * 2);
    }

    public double width(double ordinary, double distance) {
        if (distance >= transition) return ordinary;
        if (distance <= 0) return radius;
        double weight = smooth(Math.clamp(distance / transition, 0, 1));
        return radius + (ordinary - radius) * weight;
    }

    public double depthScale(double distance) {
        if (distance >= transition) return 1;
        if (distance <= 0) return depth;
        return depth + (1 - depth) * smooth(Math.clamp(distance / transition, 0, 1));
    }

    /** The back of the cap is unequal; its side joins the ordinary channel with zero angular slope. */
    public double capWidth(double width, double along, double across) {
        if (along >= 0) return width;
        double distance = Math.hypot(along, across);
        if (distance == 0) return width;
        double upstream = -along / distance;
        double angle = Math.atan2(across, -along);
        double recess = .12 + .07 * Math.sin(2 * angle + phase) + .035 * Math.cos(3 * angle - phase);
        return Math.max(3.5, width * (1 - upstream * upstream * recess));
    }

    private static double smooth(double value) { return value * value * (3 - 2 * value); }
    private static double unit(long value) { return (value >>> 11) * 0x1.0p-53; }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}

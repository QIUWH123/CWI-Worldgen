package net.quiwh.cwiworldgen.hydrology;

/** An immutable world-space centerline point, when a provider supports reconstruction. */
public record ReachPoint(double x, double z) {
    public ReachPoint {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Reach coordinates must be finite");
        }
    }
}

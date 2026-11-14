package net.quiwh.cwiworldgen.hydrology;

/** Bounds in the coordinate space explicitly identified by the snapshot provider. */
public record HydrologyBounds(double minX, double minZ, double maxX, double maxZ) {
    public HydrologyBounds {
        if (!Double.isFinite(minX) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxZ)
                || minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("Hydrology bounds must be finite and ordered");
        }
    }
}

package net.quiwh.cwiworldgen.hydrology;

/**
 * Local downstream direction; this metadata alone does not authorize entity movement.
 * Signed distance is measured in the provider's native routing domain, not world blocks.
 */
public record HydrologyQuery(boolean validDirection, long reachId, double progress,
                             double tangentX, double tangentZ, double signedDistance,
                             String reason) {
    public HydrologyQuery {
        if (!Double.isFinite(progress) || !Double.isFinite(tangentX)
                || !Double.isFinite(tangentZ) || !Double.isFinite(signedDistance)
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Hydrology queries must contain finite numeric data");
        }
        if (validDirection && (progress < 0 || progress > 1
                || Math.abs(Math.hypot(tangentX, tangentZ) - 1) > 1E-6)) {
            throw new IllegalArgumentException("A valid direction requires normalized tangent and progress");
        }
    }

    public static HydrologyQuery invalid(String reason) {
        return new HydrologyQuery(false, 0, 0, 0, 0, 0, reason);
    }
}

package net.quiwh.cwiworldgen.hydrology.current;

/** One small downstream acceleration; faster paddling and lateral motion remain available. */
public final class CurrentMotion {
    private CurrentMotion() {}
    public record Horizontal(double x, double z) {}

    public static Horizontal push(double x, double z, CurrentColumn current, double multiplier) {
        if (!current.active() || !Double.isFinite(multiplier) || multiplier <= 0) return new Horizontal(x, z);
        double along = x * current.directionX() + z * current.directionZ();
        double addition = Math.max(0, Math.min(.0035 * Math.min(2, multiplier), current.speed() * multiplier - along));
        return new Horizontal(x + current.directionX() * addition, z + current.directionZ() * addition);
    }

    /** Drift converges gently to a capped along-current speed; sideways impulses are retained. */
    public static Horizontal drift(double x, double z, CurrentColumn current, double multiplier,
            double maxBlocksPerSecond) {
        if (!current.active() || !Double.isFinite(multiplier) || multiplier <= 0
                || !Double.isFinite(maxBlocksPerSecond) || maxBlocksPerSecond <= 0) return new Horizontal(x, z);
        double along = x * current.directionX() + z * current.directionZ();
        double target = Math.min(maxBlocksPerSecond / 20., current.speed() * multiplier);
        double change = Math.clamp(target - along, -.0035, .0035);
        return new Horizontal(x + current.directionX() * change, z + current.directionZ() * change);
    }

    /** A target is the boat bottom's resting Y, derived from actual loaded water. */
    public static double surfaceVelocity(double bottomY, double velocityY, double targetY) {
        if (!Double.isFinite(targetY) || !Double.isFinite(bottomY) || bottomY > targetY + .04) return velocityY;
        double difference = targetY - bottomY;
        if (difference <= .04) return 0;
        return Math.min(.15, difference * .3);
    }

    public static boolean owns(boolean client, boolean player, boolean localPlayer, boolean boat) {
        if (boat) return false;
        return player ? client && localPlayer : !client;
    }
}

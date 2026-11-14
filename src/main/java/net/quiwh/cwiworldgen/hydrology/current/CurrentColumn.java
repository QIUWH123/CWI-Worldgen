package net.quiwh.cwiworldgen.hydrology.current;

/** Compact immutable planned current. Actual loaded water must still be checked by consumers. */
public record CurrentColumn(int minY, int maxY, int heading, int strength, int drop, int impactDirection, boolean plannedWater) {
    public static final double MAX_SPEED = .08;
    public static final CurrentColumn EMPTY = new CurrentColumn(0, 0, 0, 0, 0, 0, false);

    public CurrentColumn {
        if (minY < Short.MIN_VALUE || maxY > Short.MAX_VALUE || minY > maxY
                || heading < 0 || heading > 255 || strength < 0 || strength > 255
                || drop < 0 || drop > 255 || impactDirection < 0 || impactDirection > 3) {
            throw new IllegalArgumentException("Invalid bounded current column");
        }
    }

    public static CurrentColumn of(int min, int max, double x, double z, double speed, int drop, int direction) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(speed)
                || Math.hypot(x, z) < .5 || speed <= 0 || min > max) return EMPTY;
        int heading = (int) Math.round(Math.atan2(z, x) * 256 / (2 * Math.PI)) & 255;
        int strength = Math.max(1, Math.min(255, (int) Math.round(speed / MAX_SPEED * 255)));
        return new CurrentColumn(min, max, heading, strength, Math.min(255, Math.max(0, drop)), direction, true);
    }

    public static CurrentColumn still(int min, int max, int drop, int direction) {
        return min > max ? EMPTY : new CurrentColumn(min, max, 0, 0, Math.min(255, Math.max(0, drop)), direction, true);
    }
    public boolean active() { return plannedWater && strength > 0; }
    public boolean contains(int y) { return plannedWater && y >= minY && y <= maxY; }
    public double speed() { return strength * (MAX_SPEED / 255); }
    public double directionX() { return Math.cos(heading * (2 * Math.PI / 256)); }
    public double directionZ() { return Math.sin(heading * (2 * Math.PI / 256)); }
    public double velocityX() { return directionX() * speed(); }
    public double velocityZ() { return directionZ() * speed(); }

    public long pack() {
        if (!plannedWater) return 0;
        return (minY & 65535L) | ((maxY & 65535L) << 16) | ((long) heading << 32)
                | ((long) strength << 40) | ((long) drop << 48) | ((long) impactDirection << 56) | (1L << 58);
    }

    public static CurrentColumn unpack(long packed) {
        if (packed == 0) return EMPTY;
        if ((packed >>> 59) != 0 || (packed & (1L << 58)) == 0) throw new IllegalArgumentException("Invalid current flags");
        return new CurrentColumn((short) packed, (short) (packed >>> 16), (int) (packed >>> 32) & 255,
                (int) (packed >>> 40) & 255, (int) (packed >>> 48) & 255, (int) (packed >>> 56) & 3, true);
    }
}

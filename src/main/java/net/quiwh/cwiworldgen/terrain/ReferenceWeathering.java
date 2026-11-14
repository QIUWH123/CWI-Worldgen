package net.quiwh.cwiworldgen.terrain;

/** Smooth, world-seeded weathering districts shared by soil retreat and native rock relief. */
public final class ReferenceWeathering {
    private ReferenceWeathering() {}

    /** Pure coordinate sampling keeps adjacent chunks independent of generation order. */
    public static double sample(long worldSeed, int x, int z) {
        return .72 * value(worldSeed ^ 0x4357495745415448L, x, z, 96)
                + .28 * value(worldSeed ^ 0x45524F53494F4E53L, x, z, 28);
    }

    private static double value(long seed, int x, int z, int scale) {
        int cellX = Math.floorDiv(x, scale);
        int cellZ = Math.floorDiv(z, scale);
        double dx = smooth((x - (long) cellX * scale) / (double) scale);
        double dz = smooth((z - (long) cellZ * scale) / (double) scale);
        double north = lerp(unit(seed, cellX, cellZ), unit(seed, cellX + 1, cellZ), dx);
        double south = lerp(unit(seed, cellX, cellZ + 1), unit(seed, cellX + 1, cellZ + 1), dx);
        return lerp(north, south, dz);
    }

    private static double smooth(double value) { return value * value * value * (value * (value * 6 - 15) + 10); }
    private static double lerp(double a, double b, double alpha) { return a + (b - a) * alpha; }

    private static double unit(long seed, int x, int z) {
        long value = seed ^ mix((long) x * 0x632BE59BD9B4E019L) ^ mix((long) z * 0x9E3779B97F4A7C15L);
        return (mix(value) >>> 11) * 0x1.0p-53;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}

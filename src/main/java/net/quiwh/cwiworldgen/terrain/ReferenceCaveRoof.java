package net.quiwh.cwiworldgen.terrain;

/** Keeps original noodle tunnels below a weathered roof, without filling scenic openings. */
public final class ReferenceCaveRoof {
    private ReferenceCaveRoof() {}

    public static double density(int generationVersion, long seed, int x, int y, int z,
            double surfaceHeight) {
        // Registry settings can be rebound to a saved profile. This value is below
        // the original noodle field's complete range, making max an exact no-op.
        if (generationVersion < 8) return -64.;
        return Math.max(-1., Math.min(1., (y - surfaceHeight + roofDepth(seed, x, z)) / 12.));
    }

    public static double roofDepth(long seed, int x, int z) {
        int gx = Math.floorDiv(x, 128), gz = Math.floorDiv(z, 128);
        double tx = smooth(Math.floorMod(x, 128) / 128.);
        double tz = smooth(Math.floorMod(z, 128) / 128.);
        double a = value(seed, gx, gz), b = value(seed, gx + 1, gz);
        double c = value(seed, gx, gz + 1), d = value(seed, gx + 1, gz + 1);
        return 18. + 8. * ((a + (b - a) * tx) * (1. - tz) + (c + (d - c) * tx) * tz);
    }

    private static double smooth(double t) { return t * t * (3. - 2. * t); }
    private static double value(long seed, int x, int z) {
        long v = seed ^ (long) x * 0x632be59bd9b4e019L ^ (long) z * 0x9e3779b97f4a7c15L
                ^ 0x5B4D19E8A37C62F1L;
        v = (v ^ v >>> 30) * 0xBF58476D1CE4E5B9L;
        v = (v ^ v >>> 27) * 0x94D049BB133111EBL;
        v ^= v >>> 31;
        return (v >>> 11) * 0x1.0p-53;
    }
}

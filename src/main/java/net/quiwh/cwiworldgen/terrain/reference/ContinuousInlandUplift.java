package net.quiwh.cwiworldgen.terrain.reference;

/** Continuous profile-7 continental relief, shared by terrain and drainage planning. */
public final class ContinuousInlandUplift {
    private static final double MAX_UPLIFT_BLOCKS = 30;

    private ContinuousInlandUplift() {}

    /** Returns normalized height; unlike the legacy policy, no fixed terrace levels exist. */
    public static float sample(int seed, double x, double z, float waterTable,
                               float continentSizeModifier, int worldHeight) {
        if (worldHeight < 1) throw new IllegalArgumentException("World height must be positive");
        double inland = smooth(clamp(waterTable));
        if (inland == 0) return 0;
        // Unequal scales and differently rotated fields avoid following continental rings
        // or imposing a visible common period on different continental cells.
        double broad = noise(seed ^ 0x6a09e667, (.8 * x + .6 * z) / 1900,
                (-.6 * x + .8 * z) / 1900);
        double detail = noise(seed ^ 0xbb67ae85, (.6 * x - .8 * z) / 770,
                (.8 * x + .6 * z) / 770);
        double modulation = .65 + .35 * (.7 * broad + .3 * detail);
        double size = .8 + .2 * clamp(continentSizeModifier / 1.25);
        return (float) (MAX_UPLIFT_BLOCKS * inland * modulation * size / worldHeight);
    }

    private static double noise(int seed, double x, double z) {
        long ix = (long) Math.floor(x);
        long iz = (long) Math.floor(z);
        double tx = smooth(x - ix);
        double tz = smooth(z - iz);
        double lower = lerp(hash(seed, ix, iz), hash(seed, ix + 1, iz), tx);
        double upper = lerp(hash(seed, ix, iz + 1), hash(seed, ix + 1, iz + 1), tx);
        return lerp(lower, upper, tz);
    }

    private static double hash(int seed, long x, long z) {
        long value = (seed & 0xffffffffL) ^ x * 0x9e3779b97f4a7c15L ^ z * 0xd1b54a32d192ed03L;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double smooth(double value) {
        return value * value * value * (value * (value * 6 - 15) + 10);
    }

    private static double lerp(double lower, double upper, double t) {
        return lower + (upper - lower) * t;
    }
}

package net.quiwh.cwiworldgen.terrain;

/** A bounded, seed-local sediment lens with unequal joined lobes and weathered margins. */
public final class SedimentPatchShape {
    private final long seed;
    private final int originX, originZ, bound;
    private final double radius, cos, sin, aspect;
    private final double lobeX, lobeZ, lobeRadius, oppositeX, oppositeZ, oppositeRadius;

    public SedimentPatchShape(long seed, int requestedRadius, int originX, int originZ) {
        this.seed = seed;
        this.originX = originX;
        this.originZ = originZ;
        int configuredRadius = Math.max(1, Math.min(8, requestedRadius));
        bound = Math.min(15, (int) Math.ceil(configuredRadius * 1.6) + 2);
        radius = configuredRadius * (.78 + unit(seed) * .34);
        double angle = unit(seed ^ 0x414E474C45L) * Math.PI * 2;
        cos = Math.cos(angle);
        sin = Math.sin(angle);
        aspect = 1.08 + unit(seed ^ 0x415350454354L) * .36;
        lobeX = radius * (.4 + unit(seed ^ 0x584C4F4245L) * .2);
        lobeZ = radius * (.3 + unit(seed ^ 0x5A4C4F4245L) * .25)
                * (unit(seed ^ 0x5349474EL) < .5 ? -1 : 1);
        lobeRadius = radius * (.48 + unit(seed ^ 0x524C4F4245L) * .24);
        oppositeX = -radius * (.38 + unit(seed ^ 0x584F50504FL) * .28);
        oppositeZ = radius * (unit(seed ^ 0x5A4F50504FL) - .5) * .8;
        oppositeRadius = radius * (.38 + unit(seed ^ 0x524F50504FL) * .23);
    }

    /** At most a 31x31 column scan, including the maximum configured radius. */
    public int boundRadius() { return bound; }

    public boolean contains(int x, int z) {
        if (Math.abs((long) x - originX) > bound || Math.abs((long) z - originZ) > bound) return false;
        double score = score(x, z);
        // Fine coherent erosion thins the outer margin, leaving joined material instead
        // of independent coin-flip blocks throughout the core.
        double edge = .92 + noise(x * .38, z * .38, seed ^ 0x45444745L) * .16;
        return score <= edge;
    }

    /** Patch cores are slightly deeper; the outer ragged edge remains a single block. */
    public int depth(int x, int z, int maximumDepth) {
        int limit = Math.max(1, Math.min(5, maximumDepth));
        double core = Math.max(0, 1 - score(x, z));
        return Math.min(limit, 1 + (int) (core * limit));
    }

    private double score(int x, int z) {
        double scale = Math.max(2, radius * .7);
        double dx = (long) x - originX;
        double dz = (long) z - originZ;
        // Domain warping is evaluated in world coordinates, so chunk boundaries do
        // not reset the weathering field or truncate an otherwise shared outline.
        dx += (noise(x / scale, z / scale, seed ^ 0x5741525058L) - .5) * radius * .4;
        dz += (noise(x / scale, z / scale, seed ^ 0x574152505AL) - .5) * radius * .4;
        double u = dx * cos + dz * sin;
        double v = -dx * sin + dz * cos;
        double main = squared(u / (radius * aspect)) + squared(v / (radius * .72));
        double side = squared((u - lobeX) / lobeRadius) + squared((v - lobeZ) / (lobeRadius * .86));
        double opposite = squared((u - oppositeX) / oppositeRadius)
                + squared((v - oppositeZ) / (oppositeRadius * 1.05));
        return Math.min(main, Math.min(side, opposite));
    }

    private static double noise(double x, double z, long salt) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double fx = smooth(x - ix), fz = smooth(z - iz);
        return lerp(fz, lerp(fx, hash(ix, iz, salt), hash(ix + 1, iz, salt)),
                lerp(fx, hash(ix, iz + 1, salt), hash(ix + 1, iz + 1, salt)));
    }

    private static double hash(int x, int z, long salt) {
        return unit(salt ^ x * 0x632BE59BD9B4E019L ^ z * 0x9E3779B97F4A7C15L);
    }

    private static double unit(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return ((value ^ (value >>> 31)) >>> 11) * 0x1.0p-53;
    }

    private static double squared(double value) { return value * value; }
    private static double smooth(double value) { return value * value * (3 - 2 * value); }
    private static double lerp(double amount, double a, double b) { return a + amount * (b - a); }
}

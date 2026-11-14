package net.quiwh.cwiworldgen.terrain;

/** Seeded material districts; no chunk grid, independent block dice or circular stamps. */
public final class WaterbedMaterials {
    public static final int SOIL = 0, ROCK = 1, GRAVEL = 2, SAND = 3, CLAY = 4;

    private WaterbedMaterials() {}

    public static int material(long seed, int x, int z, boolean ocean, double energy) {
        double e = Double.isFinite(energy) ? Math.clamp(energy, 0., 1.) : .3;
        double wx = x + (value(seed ^ 0x4348414E4E454C58L, x, z, 47) - .5) * 13;
        double wz = z + (value(seed ^ 0x534544494D454E54L, x, z, 39) - .5) * 11;
        // Each fine material has its own field. Clay can sit on soil, gravel or
        // host rock without relying on a sand district having formed first.
        if (value(seed ^ 0x434C41594C454E53L, wx, wz, 4) > .74 + e * .12) return CLAY;
        if (value(seed ^ 0x53414E444C454E53L, wx, wz, 7) > (ocean ? .52 : .54) + e * .30) return SAND;
        // Gravel spans wider connected lenses; strong water removes fines and
        // exposes more of these coarse deposits and the local host beneath them.
        if (value(seed ^ 0x47524156454C4244L, wx, wz, 12) > .46) return GRAVEL;
        return value(seed ^ 0x4245444D41535345L, wx, wz, 18) < .25 ? SOIL : ROCK;
    }

    public static int material(long seed, int x, int z, boolean ocean, double energy, int generationVersion) {
        if (generationVersion < 13) return material(seed, x, z, ocean, energy);
        double e = Double.isFinite(energy) ? Math.clamp(energy, 0., 1.) : .3;
        // Thresholded tensor-product value noise leaves long flat lattice sides.
        // A triangular gradient field has no constant-value lattice corners;
        // world-space warp and a second octave bend and break up its contours.
        double wx = x + simplex(seed ^ 0x4348414E4E454C58L, x / 43., z / 43.) * 4.;
        double wz = z + simplex(seed ^ 0x534544494D454E54L, x / 37., z / 37.) * 4.;
        // Larger districts keep fines in recognizable patches. Gravel retains the
        // broadest scale; coverage thresholds and the existing depth policy stay put.
        if (lens(seed ^ 0x434C41594C454E53L, wx, wz, generationVersion >= 14 ? 14. : 9.) > .72 + e * .08) return CLAY;
        if (lens(seed ^ 0x53414E444C454E53L, wx, wz, generationVersion >= 14 ? 24. : 17.) > (ocean ? .52 : .54) + e * .27) return SAND;
        if (lens(seed ^ 0x47524156454C4244L, wx, wz, generationVersion >= 14 ? 44. : 31.) > .46) return GRAVEL;
        return lens(seed ^ 0x4245444D41535345L, wx, wz, 43.) < .25 ? SOIL : ROCK;
    }

    private static double lens(long seed, double x, double z, double scale) {
        return .5 + .5 * (.78 * simplex(seed, x / scale, z / scale)
                + .22 * simplex(seed ^ 0x4F435441564532L, x / scale * 2.13 + 19.3, z / scale * 2.13 - 7.1));
    }

    /** Stateless 2D simplex-gradient noise, independent of generation neighbors and RNG state. */
    private static double simplex(long seed, double x, double z) {
        double skew = (x + z) * .3660254037844386;
        int ix = (int) Math.floor(x + skew), iz = (int) Math.floor(z + skew);
        double unskew = (ix + (double) iz) * .21132486540518713;
        double dx = x - ix + unskew, dz = z - iz + unskew;
        int stepX = dx > dz ? 1 : 0, stepZ = dx > dz ? 0 : 1;
        return 70. * (corner(seed, ix, iz, dx, dz)
                + corner(seed, ix + stepX, iz + stepZ, dx - stepX + .21132486540518713,
                        dz - stepZ + .21132486540518713)
                + corner(seed, ix + 1, iz + 1, dx - 1. + .42264973081037427, dz - 1. + .42264973081037427));
    }

    private static double corner(long seed, int x, int z, double dx, double dz) {
        double falloff = .5 - dx * dx - dz * dz;
        if (falloff <= 0) return 0.;
        int gradient = (int) mix(seed ^ mix(x * 0x632BE59BD9B4E019L) ^ mix(z * 0x9E3779B97F4A7C15L)) & 7;
        double dot = switch (gradient) {
            case 0 -> dx + dz;
            case 1 -> -dx + dz;
            case 2 -> dx - dz;
            case 3 -> -dx - dz;
            case 4 -> dx;
            case 5 -> -dx;
            case 6 -> dz;
            default -> -dz;
        };
        falloff *= falloff;
        return falloff * falloff * dot;
    }

    /** Existing callers retain their shallow three-layer bound. */
    public static int depth(long seed, int x, int z) { return depth(seed, x, z, GRAVEL); }

    public static int depth(long seed, int x, int z, int material) {
        double thickness = value(seed ^ 0x424544544849434BL ^ material * 0x9E3779B97F4A7C15L, x, z, 5);
        return 1 + (int) (thickness * (material == SAND || material == CLAY ? 5 : 3));
    }

    public static double rockWear(long seed, int x, int z) {
        return value(seed ^ 0x5741544552574541L, x, z, 7);
    }

    private static double value(long seed, double x, double z, int scale) {
        int ix = (int) Math.floor(x / scale), iz = (int) Math.floor(z / scale);
        double fx = smooth(x / scale - ix), fz = smooth(z / scale - iz);
        return lerp(lerp(unit(seed, ix, iz), unit(seed, ix + 1, iz), fx),
                lerp(unit(seed, ix, iz + 1), unit(seed, ix + 1, iz + 1), fx), fz);
    }

    private static double smooth(double x) { return x * x * x * (x * (x * 6 - 15) + 10); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double unit(long seed, int x, int z) {
        return (mix(seed ^ mix(x * 0x632BE59BD9B4E019L) ^ mix(z * 0x9E3779B97F4A7C15L)) >>> 11) * 0x1.0p-53;
    }
    private static long mix(long n) {
        n = (n ^ (n >>> 30)) * 0xBF58476D1CE4E5B9L;
        n = (n ^ (n >>> 27)) * 0x94D049BB133111EBL;
        return n ^ (n >>> 31);
    }
}

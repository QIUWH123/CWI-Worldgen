package net.quiwh.cwiworldgen.feature;

import java.util.Random;

/** Local mineral geometry; score ranking spends the caller's fixed ore budget. */
final class IndustrialAggregateShape {
    private final String family;
    private final int x, y, z;
    private final Frame frame;
    private final double length, width, thickness, branch, phase;
    private final long seed;

    IndustrialAggregateShape(String family, int x, int y, int z, long seed) {
        this.family = family;
        this.x = x;
        this.y = y;
        this.z = z;
        this.seed = seed;
        Random random = new Random(seed);
        double azimuth = random.nextDouble() * Math.PI * 2;
        double elevation = family.equals("bedded") ? (random.nextDouble() - .5) * .16
            : Math.asin(random.nextDouble() * 1.7 - .85);
        double roll = family.equals("bedded") ? (random.nextDouble() - .5) * .16 : random.nextDouble() * Math.PI;
        frame = Frame.oriented(azimuth, elevation, roll);
        double a = random.nextDouble(), b = random.nextDouble(), c = random.nextDouble();
        length = family.equals("crystalline") ? 3.5 + a * 2
            : family.equals("bedded") ? 3.8 + a * 1.4 : 1.8 + a * .9;
        width = family.equals("bedded") ? 2.6 + b * 1.2 : 1.5 + b * .7;
        thickness = family.equals("bedded") ? .5 + c * .2 : 1.4 + c * .7;
        branch = random.nextBoolean() ? 1 : -1;
        phase = random.nextDouble() * Math.PI * 2;
    }

    double score(int dx, int dy, int dz) {
        double ox = dx - x, oy = dy - y, oz = dz - z;
        double a = frame.ux * ox + frame.uy * oy + frame.uz * oz;
        double b = frame.vx * ox + frame.vy * oy + frame.vz * oz;
        double c = frame.wx * ox + frame.wy * oy + frame.wz * oz;
        double shape = switch (family) {
            // Cubic minerals grow interlocking blocky faces, with a smaller attached cube.
            case "cubic" -> Math.min(box(a, b, c, length, width, thickness),
                .16 + box(a - length * .65, b - branch * width * .45, c + thickness * .25,
                    length * .7, width * .8, thickness * .7));
            // A gently dipping, warped lens preserves the sedimentary bed's broad form.
            case "bedded" -> Math.sqrt(square(a / length) + square(b / width))
                + Math.abs(c + .16 * Math.sin(a * .7 + phase) * Math.sin(b * .6)) / thickness;
            // Hexagonal prisms with broken terminations and an oblique attached branch.
            case "crystalline" -> Math.min(prism(a, b, c, length, width, thickness),
                .25 + prism((a + branch * b) * .7071 - length * .22,
                    (b - branch * a) * .7071, c, length * .65, width * .8, thickness * .8));
            // Rounded concretions have distinct lobes rather than planar crystal faces.
            case "nodular" -> Math.min(ellipsoid(a, b, c, length, width, thickness),
                .12 + ellipsoid(a - length * .75, b + branch * .45, c - .35,
                    length * .72, width * .8, thickness * .8));
            // Massive sulfides and oxides form asymmetric, fused, irregular blebs.
            default -> Math.min(ellipsoid(a + .35 * Math.sin(b + phase), b, c, length, width, thickness),
                .14 + ellipsoid(a + length * .65, b - branch * width * .7, c + .3,
                    length * .8, width * .85, thickness * .75))
                + .07 * Math.sin(a * 1.3 + phase) * Math.cos(c * 1.1 - phase);
        };
        // Small coherent chips break otherwise perfect faces without scattering isolated ore.
        return shape + .055 * unit(seed, Math.floorDiv(dx, 2), Math.floorDiv(dy, 2), Math.floorDiv(dz, 2));
    }

    private static double box(double a, double b, double c, double length, double width, double thickness) {
        return Math.max(Math.abs(a) / length, Math.max(Math.abs(b) / width, Math.abs(c) / thickness));
    }

    private static double ellipsoid(double a, double b, double c, double length, double width, double thickness) {
        return Math.sqrt(square(a / length) + square(b / width) + square(c / thickness));
    }

    private static double prism(double a, double b, double c, double length, double width, double thickness) {
        double radial = Math.max(Math.abs(b) / width,
            Math.max(Math.abs(.5 * b / width + .8660254 * c / thickness),
                Math.abs(.5 * b / width - .8660254 * c / thickness)));
        return Math.max(Math.abs(a) / length, radial) + .3 * Math.max(0, Math.abs(a) / length - .5);
    }

    private static double square(double value) { return value * value; }

    static double unit(long seed, int x, int y, int z) {
        long mixed = seed ^ (x * 0x632BE59BD9B4E019L) ^ (y * 0x9E3779B97F4A7C15L) ^ (z * 0x85157AF5D66D3E8BL);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return ((mixed ^ (mixed >>> 31)) >>> 11) * 0x1.0p-53;
    }

    private record Frame(double ux, double uy, double uz, double vx, double vy, double vz,
                         double wx, double wy, double wz) {
        static Frame oriented(double azimuth, double elevation, double roll) {
            double ca = Math.cos(azimuth), sa = Math.sin(azimuth), ce = Math.cos(elevation), se = Math.sin(elevation);
            double cr = Math.cos(roll), sr = Math.sin(roll);
            // u follows the aggregate, v crosses it, and w is its thickness/bed normal.
            double ux = ce * ca, uy = se, uz = ce * sa;
            double vx = -sa, vy = 0, vz = ca;
            double wx = -se * ca, wy = ce, wz = -se * sa;
            return new Frame(ux, uy, uz, vx * cr + wx * sr, vy * cr + wy * sr, vz * cr + wz * sr,
                wx * cr - vx * sr, wy * cr - vy * sr, wz * cr - vz * sr);
        }
    }
}

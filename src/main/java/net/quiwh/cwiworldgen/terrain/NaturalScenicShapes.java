package net.quiwh.cwiworldgen.terrain;

import net.quiwh.cwiworldgen.terrain.ReferenceLandscapeProfiles.Kind;

/** Profile 8 erosion corridors. Negative values only remove rock inside the saved descriptor. */
public final class NaturalScenicShapes {
    public static double density(Kind kind, double dx, double dy, double dz, double rx, double ry,
            double rz, double angle, long seed) {
        double bound = Math.max(rx, rz) * 1.2 + 2;
        double vertical = ry * 1.25 + 4;
        if (Math.abs(dx) >= bound || Math.abs(dz) >= bound || Math.abs(dy) >= vertical) return 64;
        // u follows the sampled host slope; v runs across it. Joined erosion cuts have their
        // own floors and ceilings, so an opening never starts from a sphere or ellipsoid.
        double u = dx * Math.cos(angle) + dz * Math.sin(angle);
        double v = -dx * Math.sin(angle) + dz * Math.cos(angle);
        seed = mix(seed ^ (kind.ordinal() + 1L) * 0x632be59bd9b4e019L);
        double q;
        if (kind == Kind.SPIRE) {
            // Open flank channels expose a buttress. Their ends remain open to the host;
            // a winding rock neck deliberately prevents the old closed moat footprint.
            q = corridor(u, dy, v, rx * .80, ry, rz * .46, seed + 211,
                    -rx * .08, rz * .82, .13, -.02, .86, -.84, .07);
            q = Math.min(q, corridor(u, dy, v, rx * .64, ry, rz * .50, seed + 233,
                    rx * .13, -rz * .87, -.23, -.12, .74, -.74, .10));
            q = Math.min(q, corridor(u, dy, v, rx * .45, ry, rz * .46, seed + 257,
                    -rx * .56, rz * .12, 1.06, -.14, .61, -.68, .04));
            double neckCenter = rz * .12 * noise(u / rx * 2.1, 0, seed + 277);
            double neckWidth = rz * (.26 + .06 * noise(u / rx * 2.7, 0, seed + 281));
            double neck = Math.min((u + rx * .12) / rx, (neckWidth - Math.abs(v - neckCenter)) / rz);
            double core = .17 - Math.hypot(u / rx, v / rx);
            q = Math.max(q, Math.max(neck, core));
        } else {
            double slope = kind == Kind.CAVERN ? .025 : .11;
            double mainRoof = kind == Kind.OVERHANG ? .28 : .31;
            double mainFloor = kind == Kind.OVERHANG ? -.66 : -.42;
            q = corridor(u, dy, v, rx * (.80 + .14 * unit(seed + 401)), ry,
                    rz * (.67 + .16 * unit(seed + 403)), seed + 311,
                    rx * (.18 * unit(seed + 409) - .09), rz * (.24 * unit(seed + 419) - .12),
                    .12 * unit(seed + 421) - .06, -.08, mainRoof, mainFloor, slope);
            // A high retreating cut meets a deeper, offset channel. Different ledges and
            // fracture directions make connected apertures with unequal crown and floor.
            double handed = unit(seed + 307) < .5 ? -1 : 1;
            q = Math.min(q, corridor(u, dy, v, rx * (.38 + .22 * unit(seed + 431)), ry, rz * .57, seed + 337,
                    rx * (-.42 + .34 * unit(seed + 433)), rz * (.05 + .25 * unit(seed + 439)) * handed,
                    (.10 + .35 * unit(seed + 443)) * handed, .28 + .10 * unit(seed + 449),
                    .37 + .10 * unit(seed + 457), -.31, slope * .65));
            q = Math.min(q, corridor(u, dy, v, rx * (.31 + .20 * unit(seed + 461)), ry, rz * .53, seed + 359,
                    rx * (.20 + .27 * unit(seed + 463)), -rz * (.12 + .27 * unit(seed + 467)) * handed,
                    -(.40 + .45 * unit(seed + 479)) * handed, -.24 - .10 * unit(seed + 487),
                    .38, -.45, slope));
            if (kind == Kind.CAVERN && unit(seed + 21) < .55) {
                // Supports are retained host, offset from the main chamber's joining neck.
                double px = rx * .24, pz = -rz * .32;
                double radius = 2.0 + .45 * noise(dy / 7, 0, seed + 379);
                double support = (radius - Math.hypot(u - px, v - pz)) / ry;
                q = Math.max(q, support);
            }
        }
        // The descriptor and structure screening stay conservative. Fade before their
        // edges so neither the rotated corridors nor roof/floor lobes can make a clip wall.
        double edge = Math.min(Math.min(bound - Math.abs(dx), bound - Math.abs(dz)), vertical - Math.abs(dy));
        q = Math.max(q, (1.5 - edge) / Math.min(rx, ry));
        if (kind == Kind.ARCH) q = Math.max(q, (dy - ry) / ry);
        return q < 0 ? Math.max(-1, q * .8) : 64;
    }

    /** A tapered, curving erosion ribbon with independently fractured upper and lower beds. */
    private static double corridor(double u, double y, double v, double length, double ry, double width,
            long seed, double centerU, double centerV, double turn, double centerY,
            double upper, double lower, double slope) {
        double du = u - centerU, dv = v - centerV;
        double s = (du * Math.cos(turn) + dv * Math.sin(turn)) / length;
        double t = (-du * Math.sin(turn) + dv * Math.cos(turn)) / width;
        double bend = .24 * noise(s * 2.4, 0, seed + 1) + .11 * noise(s * 5.3, 0, seed + 2);
        double sectionWidth = .85 + .18 * noise(s * 3.1, 0, seed + 3);
        double end = Math.abs(s) + .08 * noise(t * 1.8, s, seed + 4);
        double margin = Math.min(1 - end, sectionWidth - Math.abs(t - bend));
        if (margin <= 0) return -margin;
        double taper = smooth(Math.min(1, margin / .21));
        double bed = centerY * ry + slope * u;
        // Shared coarse beds and independent fine fractures vary the roof and floor at
        // different positions; their thickness does not obey a radial quadratic law.
        double roof = upper + .15 * noise(s * 3.5, t * 1.4, seed + 11)
                + .08 * noise(s * 7.4, t * 3.3, seed + 12);
        double floor = lower + .17 * noise(s * 3.1, t * 1.7, seed + 13)
                + .09 * noise(s * 6.8, t * 3.7, seed + 14);
        double ceiling = bed + Math.max(.12, roof) * ry * taper;
        double bottom = bed + Math.min(-.12, floor) * ry * taper;
        return Math.max(-margin, Math.max(y - ceiling, bottom - y) / ry);
    }

    private static double noise(double x, double z, long seed) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double sx = smooth(x - ix), sz = smooth(z - iz);
        double a = unit(seed ^ mix(ix * 0x632be59bd9b4e019L) ^ mix(iz * 0x9e3779b97f4a7c15L));
        double b = unit(seed ^ mix((ix + 1L) * 0x632be59bd9b4e019L) ^ mix(iz * 0x9e3779b97f4a7c15L));
        double c = unit(seed ^ mix(ix * 0x632be59bd9b4e019L) ^ mix((iz + 1L) * 0x9e3779b97f4a7c15L));
        double d = unit(seed ^ mix((ix + 1L) * 0x632be59bd9b4e019L) ^ mix((iz + 1L) * 0x9e3779b97f4a7c15L));
        return ((a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sz) * 2 - 1;
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }
    private static double unit(long value) { return (mix(value) >>> 11) * 0x1.0p-53; }
    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private NaturalScenicShapes() {}
}

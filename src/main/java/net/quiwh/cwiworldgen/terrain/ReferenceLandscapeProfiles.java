package net.quiwh.cwiworldgen.terrain;

/** Conservative landscape tuning, independent of the imported heightfield engine. */
public final class ReferenceLandscapeProfiles {
    public static final int SURFACE_CELL = 192;
    public static final double SURFACE_CHANCE = .18;
    public static final double CAVERN_CHANCE = .28;
    public static final int MIN_CAVERN_ROOF = 24;
    public static final int DISTRICT_BUFFER = 8;
    public static final int SURFACE_WATER_BUFFER = 24;
    public static final int CACHE_SIZE = 128;

    public enum Kind { ARCH, OVERHANG, SPIRE, CAVERN }

    private static final ShapePolicy LEGACY = new ShapePolicy(false);
    private static final ShapePolicy ORGANIC = new ShapePolicy(true);
    private static final ShapePolicy NATURAL = new ShapePolicy(true, true);

    /** Shape changes are saved terrain policy; earlier worlds retain their original silhouettes. */
    public static ShapePolicy select(int generationVersion) {
        return generationVersion >= 8 ? NATURAL : generationVersion >= 5 ? ORGANIC : LEGACY;
    }

    /** Pure bounded shape field, independent of terrain sampling and Minecraft state. */
    public record ShapePolicy(boolean organic, boolean naturalGeometry) {
        public ShapePolicy(boolean organic) { this(organic, false); }
        public double density(Kind kind, double dx, double dy, double dz, double rx, double ry,
                double rz, double angle, long seed) {
            if (naturalGeometry) return NaturalScenicShapes.density(kind, dx, dy, dz, rx, ry, rz, angle, seed);
            if (!organic) throw new IllegalStateException("Legacy formations use their original density arithmetic");
            double bound = Math.max(rx, rz) * 1.2 + 2;
            double vertical = ry * 1.25 + 4;
            if (Math.abs(dx) >= bound || Math.abs(dz) >= bound || Math.abs(dy) >= vertical) return 64;
            double u = dx * Math.cos(angle) + dz * Math.sin(angle);
            double v = -dx * Math.sin(angle) + dz * Math.cos(angle);
            double a = u / rx, b = v / rz, c = dy / ry;
            double phaseA = unit(seed + 101) * Math.PI * 2;
            double phaseB = unit(seed + 102) * Math.PI * 2;
            double phaseC = unit(seed + 103) * Math.PI * 2;
            double support = Math.max(0, 1 - .35 * (a * a + b * b));
            // Warping the whole aperture varies its silhouette, rather than only roughening an ellipse.
            double amount = kind == Kind.SPIRE ? .045 : .085;
            double warpedU = u + rx * amount * Math.sin(1.7 * b + .65 * c + phaseA)
                    * Math.cos(.9 * a + phaseB) * support;
            double warpedV = v + rz * amount * 1.6 * Math.sin(1.8 * a - .6 * c + phaseB) * support;
            double shiftedY = dy + ry * (.10 * Math.sin(1.2 * a + phaseA)
                    + .055 * Math.sin(2.3 * b + .4 * a + phaseB)) * support;
            double rough = .045 * Math.sin(2.4 * a + 1.7 * c + phaseA)
                    * Math.sin(2.8 * b - .9 * c + phaseB)
                    + .025 * Math.sin(4.1 * a + 2.4 * b + phaseC);
            double q;
            if (kind == Kind.SPIRE) {
                double azimuth = Math.atan2(warpedV, warpedU);
                double radius = rx * (.62 + .025 * Math.sin(azimuth + phaseA)
                        + .015 * Math.cos(3 * azimuth + phaseB));
                double thickness = rx * .34 * (.87 + .08 * Math.sin(2 * azimuth + phaseC));
                double annulus = (Math.hypot(warpedU, warpedV) - radius) / thickness;
                q = annulus * annulus + Math.pow(shiftedY / ry, 4) + rough;
            } else {
                double tilt = kind == Kind.OVERHANG ? .28 * warpedU : 0;
                double lobe = 1 + .09 * Math.sin(1.4 * a + phaseC)
                        * Math.sin(1.7 * b + phaseB);
                q = warpedU * warpedU / (rx * rx) + warpedV * warpedV / (rz * rz)
                        + (shiftedY + tilt) * (shiftedY + tilt) / (ry * ry * lobe * lobe) + rough;
                if (kind == Kind.CAVERN) {
                    double pillarX = rx * .23 * Math.sin(unit(seed + 17) * Math.PI * 2);
                    double pillarZ = rz * .2 * Math.cos(unit(seed + 17) * Math.PI * 2);
                    if (unit(seed + 21) < .55 && Math.hypot(u - pillarX, v - pillarZ)
                            < 2.2 + .6 * Math.sin(dy * .3 + unit(seed + 17) * Math.PI * 2)) return 64;
                }
            }
            // A smooth enclosing field keeps every cut inside the existing conservative descriptor.
            q = Math.max(q, Math.pow(dx / bound, 8) + Math.pow(dz / bound, 8)
                    + Math.pow(dy / vertical, 8));
            // ARCH centers reserve five blocks beneath the host surface. Silhouette asymmetry
            // may vary the aperture below its nominal crown, but cannot spend that roof reserve.
            if (kind == Kind.ARCH) q = Math.max(q, Math.pow(Math.max(0, dy) / ry, 8));
            return q < 1 ? Math.max(-1, (q - 1) * .8) : 64;
        }
    }

    private static double unit(long value) {
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private ReferenceLandscapeProfiles() {}
}

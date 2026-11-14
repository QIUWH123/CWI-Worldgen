package net.quiwh.cwiworldgen.hydrology.watershed;

/** Pure local bed policy; routes, water planes, protected islands and managed lakes are caller-owned. */
public final class WatershedMorphology {
    private WatershedMorphology() {}

    public record Shape(double bedY, boolean shoal, boolean dryCrest, boolean plungePool, boolean gorge) {}

    public static Shape shape(long reachId, double progress, double signedDistance, double width,
            double originalGroundY, double waterY, double baseBedY,
            boolean tallIsland, boolean lake, boolean incomingDrop) {
        return shape(reachId, progress, signedDistance, width, originalGroundY, waterY, baseBedY,
                tallIsland, lake, incomingDrop, true);
    }

    /** Stable reach is a routing-level drop test, not a guess from one sampled ground column. */
    public static Shape shape(long reachId, double progress, double signedDistance, double width,
            double originalGroundY, double waterY, double baseBedY,
            boolean tallIsland, boolean lake, boolean incomingDrop, boolean stableReach) {
        if (tallIsland || lake || !Double.isFinite(progress + signedDistance + width
                + originalGroundY + waterY + baseBedY) || width <= 0 || progress < 0 || progress > 1)
            return unchanged(baseBedY);
        double lateral = signedDistance / width;
        if (Math.abs(lateral) >= 1.8 || baseBedY >= waterY) return unchanged(baseBedY);
        long shapeSeed = mix(reachId ^ 0x5741544552534844L);
        double bed = baseBedY;
        boolean shoal = false, crest = false, pool = false, gorge = false;
        double relief = originalGroundY - waterY;

        // A compact, off-center shoal leaves both side channels at their ordinary depth.
        // Only a minority of broad reaches carry one, and fewer retain a low dry crest.
        if ((shapeSeed & 3) == 0 && stableReach && !incomingDrop && width > 8 && relief > 2 && relief < 24) {
            double center = .34 + .32 * unit(shapeSeed + 1);
            double length = .14 + .08 * unit(shapeSeed + 2);
            double offset = (.28 * unit(shapeSeed + 3) - .14)
                    + .035 * Math.sin((progress - center) / length * Math.PI);
            double radius = .29 + .10 * unit(shapeSeed + 4);
            double weight = bump((progress - center) / length) * bump((lateral - offset) / radius)
                    * smooth(8, 12, width) * smooth(2, 5, relief) * (1 - smooth(16, 24, relief));
            double top = Math.floor(waterY) + ((shapeSeed & 12) == 0 ? .70 : -.55);
            top = Math.min(top, originalGroundY);
            double raised = baseBedY + Math.max(0, top - baseBedY) * weight;
            if (raised > bed + .001) {
                bed = raised;
                shoal = true;
                crest = bed >= Math.floor(waterY);
            }
        }

        // The caller derives this flag from a steep incoming reach meeting a flat receiver.
        // No height-only test can manufacture a drop or change the planned water level.
        if (incomingDrop && width >= 4) {
            double weight = bump((progress - .075) / .15) * smooth(0, .025, progress)
                    * bump(lateral / .9);
            double cut = (1.8 + 1.2 * unit(shapeSeed + 5)) * weight;
            if (cut > .001) { bed -= cut; pool = true; }
        }

        // A few high-relief reaches cut one wall more strongly; the existing bank fade
        // blends this bed target into the native slope without adding another valley.
        if ((shapeSeed & 7) == 1 && relief > 22) {
            double side = (shapeSeed & 8) == 0 ? -1 : 1;
            double skew = .55 + .45 * Math.tanh(lateral * side * 2);
            double weight = smooth(0, .12, progress) * (1 - smooth(.88, 1, progress))
                    * (1 - smooth(.45, 1.8, Math.abs(lateral))) * smooth(22, 55, relief);
            double cut = (2.8 + 2.2 * unit(shapeSeed + 6)) * skew * weight;
            if (cut > .001) { bed -= cut; gorge = true; }
        }
        return new Shape(Math.min(originalGroundY, bed), shoal, crest, pool, gorge);
    }

    private static Shape unchanged(double bed) { return new Shape(bed, false, false, false, false); }
    private static double bump(double value) {
        if (Math.abs(value) >= 1) return 0;
        double result = 1 - value * value;
        return result * result;
    }
    private static double smooth(double low, double high, double value) {
        double t = Math.clamp((value - low) / (high - low), 0, 1);
        return t * t * (3 - 2 * t);
    }
    private static double unit(long value) { return (mix(value) >>> 11) * 0x1.0p-53; }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}

package net.quiwh.cwiworldgen.hydrology.watershed;

/** One continuous cross-section from the submerged bowl to the uncarved valley sides. */
public final class NaturalValley {
    private NaturalValley() {}

    public static double radius(double width) { return width * 3.8 + 24; }

    public static double bed(double distance, double width, double depth, double water, double ground) {
        double d = Math.abs(distance);
        if (d >= radius(width)) return ground;
        if (d <= width) {
            double q = d / width;
            return Math.min(ground, water - .8 - (depth - .8) * Math.pow(1 - q * q, 1.15));
        }
        double relief = Math.max(0, ground - water);
        double slope = .24 + .20 * smooth(12, 64, relief);
        double bank = water - .8 + (d - width) * slope;
        double blend = smooth(width + 5, radius(width), d);
        return Math.min(ground, bank + (ground - bank) * blend);
    }

    /** Shared profile-eight bed and exposed banks, evaluated at the projected centerline. */
    public static double naturalBed(double distance, double width, double depth, double water,
            double ground, long seed, double x, double z, double curvature, double cohesion) {
        double d = Math.abs(distance);
        if (d >= radius(width)) return ground;
        double broad = field(seed, x / 137., z / 137.);
        double local = field(seed + 41, x / 47., z / 47.);
        double bend = Math.clamp(curvature, -1, 1);
        if (d <= width) {
            double lateral = distance / width;
            double shift = Math.clamp(-bend * .22 + local * .10, -.3, .3);
            double q = (lateral - shift) / (lateral < shift ? 1 + shift : 1 - shift);
            // A shifted thalweg needs a continuous exponent across the channel center.
            double exponent = 1.05 + .22 * broad + .13 * local * lateral;
            return Math.min(ground, water - .8 - Math.max(0, depth - .8)
                    * Math.pow(Math.max(0, 1 - q * q), exponent));
        }
        double side = Math.signum(distance);
        double resistance = Math.clamp(cohesion, 0, 1);
        double sideControl = field(seed + (side > 0 ? 73 : 157), x / 83., z / 83.);
        double relief = Math.max(0, ground - water);
        double outerBend = -side * bend;
        double slope = (.19 + .27 * resistance + .10 * smooth(12, 72, relief))
                * (1 + .65 * sideControl + .30 * outerBend);
        slope = Math.clamp(slope, .12, .88);
        double offset = d - width;
        double bank = water - .8 + offset * slope;
        // Unequal broad shoulders follow continuous local conditions; no per-reach reset.
        double shoulder = smooth(5, 13, offset) * (1 - smooth(18, 35, offset));
        bank -= (1.2 + 1.1 * resistance) * shoulder * Math.max(0, local + sideControl * .4);
        double extent = width + (radius(width) - width) * (.84 + .12 * (broad + 1) * .5);
        double blend = smooth(width + 3 + (sideControl + 1) * 2, extent, d);
        return Math.min(ground, bank + (ground - bank) * blend);
    }

    /** Profile twelve changes only exposed banks; the established submerged bowl stays exact. */
    public static double contextualBed(double distance, double width, double depth, double water,
            double ground, long seed, double x, double z, RiverBankContext context) {
        double original = naturalBed(distance,width,depth,water,ground,seed,x,z,
                context.curvature(),context.cohesion());
        double d = Math.abs(distance);
        if (d <= width || d >= radius(width) || context.gorgeWeight() >= 1) return original;
        double offset = d-width;
        double side = context.sideControl(distance);
        double outer = Math.clamp(-Math.signum(distance)*context.curvature(),-1,1);
        double rocky = context.rockiness();
        double slope = (.14 + .40*rocky) * (1 + .34*(side-.5) + .22*outer);
        double bank = water-.8 + offset*slope;
        double shoulder = smooth(4,11,offset)*(1-smooth(16,32,offset));
        bank -= (1-rocky)*shoulder*(.7+1.2*side);
        double extent = width+(radius(width)-width)*(.83+.13*side);
        double fade = smooth(width+4+side*3,extent,d);
        double shaped = Math.min(ground,bank+(ground-bank)*fade);
        // Established gorges retain their stronger asymmetric cut through a continuous blend.
        return Math.min(ground,shaped+(original-shaped)*context.gorgeWeight());
    }

    /** Profile sixteen varies coherent exposed rock facets; wet bowls and established collars stay exact. */
    public static double contextualBed(double distance, double width, double depth, double water,
            double ground, long seed, double x, double z, RiverBankContext context, int generationVersion) {
        double legacy = contextualBed(distance,width,depth,water,ground,seed,x,z,context);
        if (generationVersion < 16 || Math.abs(distance) <= width || Math.abs(distance) >= radius(width)
                || context.gorgeWeight() >= 1) return legacy;
        double rock = smooth(.42,.88,context.rockiness());
        if (rock == 0) return legacy;
        double side = context.sideControl(distance), offset = Math.abs(distance)-width;
        double family = .5+.5*field(seed^0x42414e4b46414345L,x/231.,z/231.);
        double facetKnee = 2.5+4*side;
        double steep = .23*offset+.75*Math.min(offset,facetKnee)
                + .24*Math.max(0,offset-14-6*family);
        double first = 2.5+2.5*side, bench = first+5+5*family, riser = bench+2.5+2*side;
        double shelf = .72*Math.min(offset,first)
                + .085*Math.clamp(offset-first,0,bench-first)
                + 1.05*Math.clamp(offset-bench,0,riser-bench)
                + .34*Math.max(0,offset-riser);
        double bank = water-.8+steep+(shelf-steep)*smooth(.2,.8,family);
        double fade = smooth(6+side*2,radius(width)-width,offset);
        double faceted = Math.min(ground,bank+(ground-bank)*fade);
        // Bound the variation of exposed ground; route widths, wet depths and outer support do not change.
        faceted = Math.clamp(faceted,legacy-2.5,legacy+2.5);
        return Math.min(ground,legacy+(faceted-legacy)*rock*(1-context.gorgeWeight()));
    }

    private static double field(long seed, double x, double z) {
        long ix = (long)Math.floor(x), iz = (long)Math.floor(z);
        double tx = x - ix, tz = z - iz;
        tx = tx * tx * (3 - 2 * tx); tz = tz * tz * (3 - 2 * tz);
        double a = value(seed, ix, iz), b = value(seed, ix + 1, iz);
        double c = value(seed, ix, iz + 1), d = value(seed, ix + 1, iz + 1);
        return (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
    }

    private static double value(long seed, long x, long z) {
        long n = seed ^ x * 0x632BE59BD9B4E019L ^ z * 0x9E3779B97F4A7C15L;
        n = (n ^ n >>> 30) * 0xBF58476D1CE4E5B9L;
        n = (n ^ n >>> 27) * 0x94D049BB133111EBL;
        n ^= n >>> 31;
        return (n >>> 11) * 0x1.0p-52 - 1;
    }

    private static double smooth(double lo, double hi, double x) {
        double t = Math.clamp((x - lo) / (hi - lo), 0, 1);
        return t * t * (3 - 2 * t);
    }
}

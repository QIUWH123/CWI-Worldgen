package net.quiwh.cwiworldgen.terrain;

/** Profile-10 local relief. Pure world-space fields keep neighboring columns and chunks coherent. */
public final class WaterLandscapeRelief {
    private WaterLandscapeRelief() {}

    /** Low rises and gullies break repeated long contour bands without raising the terrain envelope. */
    public static double land(long seed, double x, double z, double ground, double sea) {
        double coast = smooth(2, 13, Math.abs(ground - sea));
        double broad = field(seed ^ 0x629C3A17L, (.8*x+.6*z)/91, (-.6*x+.8*z)/91);
        double middle = field(seed ^ 0x73D5B901L, (.6*x-.8*z)/31, (.8*x+.6*z)/31);
        double fine = field(seed ^ 0x493F19A7L, x/13.7, z/13.7);
        return coast * (3.8*broad + 1.8*middle + .65*fine);
    }

    /** Pockets and low ridges on the sea floor fade out before the shallow shoreline. */
    public static double ocean(long seed, double x, double z, double ground, double sea) {
        double submerged = smooth(3, 14, sea-ground);
        double pockets = field(seed ^ 0x543E9D21L, x/67, z/67);
        double ridges = field(seed ^ 0x71CB309DL, (.8*x+.6*z)/27, (-.6*x+.8*z)/42);
        return submerged * (-2.0 + 3.0*pockets + 1.0*ridges);
    }

    /** Depth beneath a fixed lake surface: unequal shelves, offset pockets and submerged ridges. */
    public static double basinDepth(long seed, double u, double v, double radius, double maximumDepth) {
        double floor = 1 - smooth(.12, 1.02, radius);
        double weather = field(seed ^ 0x6617B393L, u*3.1, v*3.1);
        double shelves = field(seed ^ 0x795F31A9L, u*1.8+.37, v*1.8-.61);
        double pocket = smooth(-.15, .65, weather);
        double ridge = smooth(.05, .75, shelves);
        double interior = smooth(0, .22, radius) * (1-smooth(.68, 1, radius));
        double depth = 1.6 + (maximumDepth-1.6)*floor + interior*(3.0*pocket-2.6*ridge);
        return Math.clamp(depth, 1.5, maximumDepth+3);
    }

    /** Smooth value field; salts, scales and rotations differ between relief components. */
    public static double field(long seed, double x, double z) {
        long ix=(long)Math.floor(x), iz=(long)Math.floor(z);
        double tx=fade(x-ix), tz=fade(z-iz);
        double a=lerp(unit(seed,ix,iz),unit(seed,ix+1,iz),tx);
        double b=lerp(unit(seed,ix,iz+1),unit(seed,ix+1,iz+1),tx);
        return lerp(a,b,tz)*2-1;
    }
    private static double unit(long seed,long x,long z) {
        long value=seed ^ x*0x9E3779B97F4A7C15L ^ z*0xD1B54A32D192ED03L;
        value=(value ^ value>>>30)*0xBF58476D1CE4E5B9L;
        value=(value ^ value>>>27)*0x94D049BB133111EBL;
        return ((value ^ value>>>31)>>>11)*0x1.0p-53;
    }
    private static double smooth(double a,double b,double value){return fade(Math.clamp((value-a)/(b-a),0,1));}
    private static double fade(double t){return t*t*t*(t*(t*6-15)+10);}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
}

package net.quiwh.cwiworldgen.terrain;

/** Immutable surface context; slope is native normalized gradient, elevation is block Y, other fields are [0,1]. */
public record SurfaceHabitat(double slope,double elevation,double waterProximity,double groveRichness,double rockResistance) {
    public enum Cover { FALLEN, LEAVES, BUSH }
    public SurfaceHabitat {
        if(!Double.isFinite(slope+elevation+waterProximity+groveRichness+rockResistance))
            throw new IllegalArgumentException("Non-finite surface habitat");
        slope=clamp(slope);waterProximity=clamp(waterProximity);groveRichness=clamp(groveRichness);rockResistance=clamp(rockResistance);
    }
    public SurfaceHabitat withRockResistance(double resistance){return new SurfaceHabitat(slope,elevation,waterProximity,groveRichness,resistance);}
    /** Feature-independent coordinate hash preserves the feature RNG and makes dry cover share habitat. */
    public boolean admits(long seed,int x,int z,Cover kind,boolean nearbyStandingTrunk){
        double chance=switch(kind){
            case FALLEN -> .65+.35*groveRichness;
            case LEAVES -> .25+.5*groveRichness+(nearbyStandingTrunk?.25:0);
            case BUSH -> .7+.3*groveRichness;
        };
        chance*= (1-.2*slope)*(1-.3*waterProximity)*(1-.15*rockResistance)
                *(1-.1*clamp((elevation-180)/160));
        return unit(seed^0x5355524648414249L^mix(((long)x<<32)^(z&0xffffffffL)))<chance;
    }
    private static double clamp(double value){return Math.max(0,Math.min(1,value));}
    private static double unit(long value){return (mix(value)>>>11)*0x1.0p-53;}
    private static long mix(long value){value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;value=(value^(value>>>27))*0x94d049bb133111ebL;return value^(value>>>31);}
}

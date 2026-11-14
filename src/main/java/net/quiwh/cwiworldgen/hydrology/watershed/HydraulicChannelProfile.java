package net.quiwh.cwiworldgen.hydrology.watershed;

import java.util.ArrayList;
import java.util.List;

/** Bounded submerged cross-section; accepted routes, widths and water elevations remain authoritative. */
public final class HydraulicChannelProfile {
    private HydraulicChannelProfile() {}
    public record Control(double x,double z,double water) {}
    public record Context(double curvature,double grade,double distanceToEnd,double length) {}

    /** Built once from accepted geometry; no terrain or Minecraft state is retained. */
    public static final class Geometry {
        private final List<Context> points;
        public Geometry(List<Control> input) {
            List<Control> route=List.copyOf(input);
            double[] arc=new double[route.size()];
            for(int i=1;i<arc.length;i++)arc[i]=arc[i-1]+distance(route.get(i-1),route.get(i));
            double total=arc[arc.length-1];
            List<Context> made=new ArrayList<>();
            for(int i=0;i<route.size();i++) {
                int before=i,after=i;
                while(before>0&&arc[i]-arc[before]<24)before--;
                while(after<route.size()-1&&arc[after]-arc[i]<24)after++;
                Control a=route.get(before),b=route.get(i),c=route.get(after);
                double span=arc[after]-arc[before],bend=0;
                double ax=b.x-a.x,az=b.z-a.z,bx=c.x-b.x,bz=c.z-b.z;
                if(Math.hypot(ax,az)>1E-8&&Math.hypot(bx,bz)>1E-8)
                    bend=Math.atan2(ax*bz-az*bx,ax*bx+az*bz)/Math.max(1,span*.5);
                made.add(new Context(bend,span>0?Math.max(0,(a.water-c.water)/span):0,
                        Math.min(arc[i],total-arc[i]),total));
            }
            points=List.copyOf(made);
        }
        public Context at(int segment,double t,double width) {
            Context a=points.get(segment-1),b=points.get(segment);
            return new Context(Math.clamp(lerp(a.curvature,b.curvature,t)*width*2,-1,1),
                    lerp(a.grade,b.grade,t),lerp(a.distanceToEnd,b.distanceToEnd,t),a.length);
        }
        private static double distance(Control a,Control b){return Math.hypot(a.x-b.x,a.z-b.z);}
        private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    }

    public static double baseBed(double distance,double width,double depth,double water,double ground) {
        return NaturalValley.bed(distance,width,depth,water,ground);
    }

    /** Positive curvature turns left; positive signed distance is the inside bank of that bend. */
    public static double bed(double distance,double width,double depth,double water,double ground,
            double curvature,double grade,double cohesion,double progress,double junctionDistance,double length) {
        double original=baseBed(distance,width,depth,water,ground);
        if (!Double.isFinite(distance+width+depth+water+ground+curvature+grade+cohesion+progress+length)
                || width<=0 || depth<=.8 || Math.abs(distance)>=width) return original;
        double collar=smooth(0,.14,progress)*(1-smooth(.86,1,progress));
        if(collar==0)return original;
        double bend=Math.clamp(curvature,-1,1),lateral=distance/width;
        double soft=1-Math.clamp(cohesion,0,1);
        double gentle=1-smooth(.025,.14,Math.max(0,grade));
        double shift=-bend*.24*collar;
        double q=(lateral-shift)/(lateral<shift?1+shift:1-shift);
        double bowl=Math.pow(Math.max(0,1-q*q),1.15);
        double variedDepth=depth*(1+.08*collar*bend*bend);
        double shaped=water-.8-(variedDepth-.8)*bowl;
        // A submerged inner point bar is strongest in broad, soft, gently turning channels.
        // End collars, steep falls and firm host rock suppress it. It cannot close the wet center.
        double inside=Math.max(0,lateral*Math.signum(bend));
        double junctionClear=smooth(Math.max(10,width),Math.max(20,width*2),junctionDistance);
        double bar=smooth(4,10,width)*gentle*soft*Math.abs(bend)*collar*junctionClear
                * Math.pow(Math.max(0,1-Math.pow((inside-.47)/.42,2)),2);
        shaped+=Math.min(1.6,depth*.3)*bar;
        return Math.min(ground,Math.min(water-.8,shaped));
    }

    /** Profile-ten submerged pockets, riffles and side shelves. The accepted route,
     * water plane and wet width remain authoritative; shared joins keep their bed. */
    public static double landscapeBed(double distance,double width,double depth,double water,double ground,
            double baseBed,double grade,double progress,double junctionDistance,double length,long seed) {
        if(!Double.isFinite(distance+width+depth+water+ground+baseBed+grade+progress+junctionDistance+length)
                ||width<=0||depth<=.8||Math.abs(distance)>=width||progress<=0||progress>=1)return baseBed;
        double collar=smooth(0,.16,progress)*(1-smooth(.84,1,progress))
                *smooth(Math.max(6,width*.7),Math.max(14,width*1.6),junctionDistance);
        if(collar==0)return baseBed;
        long control=mix(seed^0x5355424D45524745L);
        double along=progress*length,lateral=distance/width;
        double bowl=Math.pow(Math.max(0,1-lateral*lateral),2);
        double wave=.70*field(control,along/27.3)+.30*field(control+1,along/63.7);
        double gentle=1-smooth(.04,.20,Math.max(0,grade));
        double pocket=smooth(.08,.72,-wave)*bowl*Math.min(2.4,depth*.48);
        double riffle=smooth(.04,.66,wave)*bowl*Math.min(1.6,depth*.34)*gentle;
        double side=(control&1)==0?-1:1;
        double shelf=Math.pow(Math.max(0,1-Math.pow((lateral-side*.56)/.30,2)),2)
                *smooth(-.5,.6,field(control+2,along/41.9))*Math.min(1.1,depth*.24)*gentle;
        double shaped=baseBed+(riffle+shelf-pocket)*collar;
        // Even a raised riffle remains submerged. A pocket cuts at most2.4 extra
        // blocks and cannot raise the native host or alter the exposed valley sides.
        return Math.min(ground,Math.min(water-.8,shaped));
    }

    private static long mix(long value) {
        value=(value^(value>>>30))*0xBF58476D1CE4E5B9L;
        value=(value^(value>>>27))*0x94D049BB133111EBL;
        return value^(value>>>31);
    }

    private static double field(long seed,double position){
        long index=(long)Math.floor(position);double t=position-index;t=t*t*(3-2*t);
        double a=(mix(seed^index*0x632BE59BD9B4E019L)>>>11)*0x1.0p-52-1;
        double b=(mix(seed^(index+1)*0x632BE59BD9B4E019L)>>>11)*0x1.0p-52-1;
        return a+(b-a)*t;
    }

    private static double smooth(double low,double high,double value) {
        double t=Math.clamp((value-low)/(high-low),0,1);return t*t*(3-2*t);
    }
}

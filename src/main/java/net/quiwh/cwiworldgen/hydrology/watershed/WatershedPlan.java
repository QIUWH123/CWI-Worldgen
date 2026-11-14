package net.quiwh.cwiworldgen.hydrology.watershed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Immutable terrain-derived drainage. Array indices are stable, world-space grid vertices. */
public final class WatershedPlan {
    public record Ground(double height, boolean ocean, boolean owned) {
        public Ground { if (!Double.isFinite(height)) throw new IllegalArgumentException("Non-finite ground"); }
    }
    @FunctionalInterface public interface Sampler { Ground sample(int x, int z); }
    public record Edge(int from, int to, double x1, double z1, double x2, double z2,
                       double water1, double water2, int area, boolean wet) {}
    private final int originX, originZ, size, spacing;
    private final double sea;
    private final double[] ground, filled;
    private final int[] receiver, area, rank;
    private final boolean[] owned, ocean, terminal, drainsToOcean;
    private final List<Edge> edges;
    private final int terminals, oceanOutlets;

    public WatershedPlan(int originX, int originZ, int size, int spacing, double sea, Sampler sampler,
                         int dryThreshold, int wetThreshold) {
        this(originX, originZ, size, spacing, sea, sampler, dryThreshold, wetThreshold, 5);
    }

    public WatershedPlan(int originX, int originZ, int size, int spacing, double sea, Sampler sampler,
                         int dryThreshold, int wetThreshold, int generationVersion) {
        if (size < 3 || size > 257 || spacing < 16 || dryThreshold < 2 || wetThreshold < dryThreshold)
            throw new IllegalArgumentException("Invalid bounded watershed grid");
        this.originX=originX; this.originZ=originZ; this.size=size; this.spacing=spacing; this.sea=sea;
        int count=size*size;
        ground=new double[count]; filled=new double[count]; receiver=new int[count]; area=new int[count];
        rank=new int[count]; owned=new boolean[count]; ocean=new boolean[count]; terminal=new boolean[count];
        drainsToOcean=new boolean[count];
        int[] floodOrder=new int[count];
        Arrays.fill(receiver,-1); Arrays.fill(rank,-1);
        for (int z=0;z<size;z++) for (int x=0;x<size;x++) {
            int i=z*size+x; Ground g=sampler.sample(originX+x*spacing,originZ+z*spacing);
            ground[i]=g.height; owned[i]=g.owned; ocean[i]=g.ocean && g.owned;
        }
        boolean[] visited=new boolean[count];
        PriorityQueue<Integer> frontier=new PriorityQueue<>(Comparator.<Integer>comparingDouble(i->filled[i]).thenComparingInt(i->i));
        int outlets=0;
        for (int i=0;i<count;i++) if (ocean[i]) { visited[i]=true; filled[i]=Math.max(sea,ground[i]); frontier.add(i); outlets++; }
        int order=0, sinks=0;
        while (true) {
            while (!frontier.isEmpty()) {
                int current=frontier.remove(); floodOrder[order]=current; rank[current]=order++;
                int x=current%size,z=current/size;
                for (int dz=-1;dz<=1;dz++) for (int dx=-1;dx<=1;dx++) {
                    if (dx==0 && dz==0) continue;
                    int nx=x+dx,nz=z+dz;
                    if (nx<0||nz<0||nx>=size||nz>=size) continue;
                    int next=nz*size+nx;
                    if (!owned[next]||visited[next]) continue;
                    visited[next]=true; receiver[next]=current;
                    filled[next]=Math.max(ground[next],filled[current]);
                    frontier.add(next);
                }
            }
            // A disconnected/inland or clipped component is explicitly terminal. Grid edges are never ocean seeds.
            int lowest=-1;
            for(int i=0;i<count;i++) if(owned[i]&&!visited[i]
                    &&(lowest<0||ground[i]<ground[lowest]||ground[i]==ground[lowest]&&i<lowest)) lowest=i;
            if(lowest<0) break;
            terminal[lowest]=true; sinks++; visited[lowest]=true; filled[lowest]=ground[lowest]; frontier.add(lowest);
        }
        terminals=sinks; oceanOutlets=outlets;
        if (generationVersion >= 6) physicalReceivers();
        // Flood ranks are already unique and increasing. Receivers retain a lower rank
        // after physical refinement, so both passes can reuse the original ordering.
        for(int step=0;step<order;step++){
            int i=floodOrder[step];
            area[i]=1;
            drainsToOcean[i]=receiver[i]<0?ocean[i]:drainsToOcean[receiver[i]];
        }
        for(int step=order-1;step>=0;step--){
            int i=floodOrder[step];
            if(receiver[i]>=0)area[receiver[i]]+=area[i];
        }
        ArrayList<Edge> selected=new ArrayList<>();
        for(int i=0;i<count;i++) if(receiver[i]>=0&&area[i]>=dryThreshold&&!ocean[i]) {
            int to=receiver[i];
            // Terminal drainage remains dry unless an explicit managed basin supplies its water separately.
            boolean reachesOcean=reachesOcean(i);
            selected.add(new Edge(i,to,x(i),z(i),x(to),z(to),water(i),water(to),area[i],area[i]>=wetThreshold&&reachesOcean));
        }
        edges=List.copyOf(selected);
        verify();
    }

    /** Steepest filled-terrain descent; the existing flood parent is the stable flat fallback. */
    private void physicalReceivers() {
        for (int i=0;i<count();i++) {
            if (!owned[i] || ocean[i] || terminal[i]) continue;
            int x=i%size,z=i/size,best=receiver[i];
            double steepest=0;
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
                if(dx==0&&dz==0)continue;
                int nx=x+dx,nz=z+dz;
                if(nx<0||nz<0||nx>=size||nz>=size)continue;
                int n=nz*size+nx;
                if(!owned[n]||rank[n]>=rank[i])continue;
                double fall=(filled[i]-filled[n])/(dx!=0&&dz!=0?Math.sqrt(2):1);
                if(fall>steepest+1E-10 || fall>0&&Math.abs(fall-steepest)<1E-10&&n<best) {
                    steepest=fall;best=n;
                }
            }
            receiver[i]=best;
        }
    }

    public double x(int index){return originX+(index%size)*spacing;}
    public double z(int index){return originZ+(index/size)*spacing;}
    public double ground(int index){return ground[index];}
    public double filled(int index){return filled[index];}
    public double water(int index){return Math.max(sea,filled[index]-2);}
    public int receiver(int index){return receiver[index];}
    public int area(int index){return area[index];}
    public int count(){return ground.length;}
    public int size(){return size;}
    public int spacing(){return spacing;}
    public int originX(){return originX;}
    public int originZ(){return originZ;}
    public int terminalCount(){return terminals;}
    public int oceanSeedCount(){return oceanOutlets;}
    public boolean ocean(int index){return ocean[index];}
    public boolean owned(int index){return owned[index];}
    public boolean terminal(int index){return terminal[index];}
    public List<Edge> edges(){return edges;}
    public boolean reachesOcean(int index){return drainsToOcean[index];}
    public void verify(){
        for(int i=0;i<count();i++) if(receiver[i]>=0){
            int r=receiver[i];
            if(!owned[r]||rank[r]>=rank[i]||filled[r]>filled[i]+1E-8||area[r]<area[i])
                throw new IllegalStateException("Invalid drainage edge "+i+" -> "+r);
        }
    }
    public long fingerprint(){
        long h=0xCBF29CE484222325L;
        for(int i=0;i<count();i++){h=(h^Double.doubleToLongBits(ground[i]))*0x100000001B3L;h=(h^receiver[i])*0x100000001B3L;h=(h^area[i])*0x100000001B3L;}
        return h;
    }
}

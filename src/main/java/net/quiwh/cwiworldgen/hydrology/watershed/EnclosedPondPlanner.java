package net.quiwh.cwiworldgen.hydrology.watershed;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.function.BiPredicate;

/** Profile12-only native depressions. No carving, random-stream draws, chunks, or outlet construction. */
public final class EnclosedPondPlanner {
    public static final int CELL_SIZE=512, WINDOW_SIZE=49, MAX_ATTEMPTS=4, CACHE_LIMIT=64;
    /** Inclusive jigsaw footprint: vanilla's maximum 128-block range on both sides. */
    public static final int MAX_RECTANGLE_SPAN=257;
    private static final int RADIUS=WINDOW_SIZE/2, COUNT=WINDOW_SIZE*WINDOW_SIZE, MAX_BUILDERS=4;
    public record Ground(double height, boolean eligible) {
        public Ground { if(!Double.isFinite(height))throw new IllegalArgumentException("Non-finite pond ground"); }
    }
    @FunctionalInterface public interface Sampler { Ground sample(int x,int z); }
    /** Protection preserves existing native material only; it does not prescribe replacement blocks. */
    public record Column(boolean protectedColumn, boolean wet, long id, double originalGroundY,
                         int groundY, int waterY, int protectionFloorY) {
        public static final Column NONE=new Column(false,false,0,0,0,0,0);
        public boolean protects(int y) { return protectedColumn&&y>=protectionFloorY&&y<=groundY; }
        public boolean containsWater(int y) { return wet&&y>groundY&&y<=waterY; }
    }
    public static final class Mask {
        private final long id;
        private final int waterY,size;
        private final Map<Long,Column> columns;
        private Mask(long id,int waterY,int size,Map<Long,Column> columns) {
            this.id=id;this.waterY=waterY;this.size=size;this.columns=Map.copyOf(columns);
        }
        public long id(){return id;}
        public int waterY(){return waterY;}
        public int size(){return size;}
        public Column column(int x,int z){return columns.getOrDefault(pack(x,z),Column.NONE);}
        public Map<Long,Column> columns(){return columns;}
    }
    private final long seed;
    private final Sampler sampler;
    private final BiPredicate<Integer,Integer> conflict;
    // Null is an intentional cached negative result, distinguished by containsKey.
    private final LinkedHashMap<Long,Mask> cells=new LinkedHashMap<>(16,.75F,true);
    // Queries outside candidate windows must remain lazy without rebuilding four center arrays.
    // This separate cache never admits, touches or evicts an authoritative mask entry.
    private final LinkedHashMap<Long,List<CandidateWindow>> candidateWindows=new LinkedHashMap<>(16,.75F,true);
    private final Map<Long,CellBuild> planning=new HashMap<>();
    private final Semaphore coldBuilders=new Semaphore(MAX_BUILDERS);
    // Retired builds remain guarded after clear removes their pending-key mappings.
    private final ThreadLocal<Boolean> constructing=new ThreadLocal<>();
    private long cacheEpoch;
    private record CandidateWindow(int x,int z) {}
    private record CellBuild(long epoch,CompletableFuture<Mask> result) {}
    public EnclosedPondPlanner(long seed,Sampler sampler){this(seed,sampler,(x,z)->false);}
    public EnclosedPondPlanner(long seed,Sampler sampler,BiPredicate<Integer,Integer> conflict){
        this.seed=seed;this.sampler=sampler;this.conflict=conflict;
    }
    public Column column(int x,int z){
        int cx=Math.floorDiv(x,CELL_SIZE),cz=Math.floorDiv(z,CELL_SIZE);
        Mask mask=cellMask(cx,cz,x,z,x,z);
        return mask==null?Column.NONE:mask.column(x,z);
    }
    /**
     * Intersects authoritative wet masks, never a per-column terrain/chunk survey. At most four
     * owning cells overlap a supported footprint; cells outside all candidate windows stay unbuilt.
     */
    public boolean rectangleIntersectsWet(int minX,int minZ,int maxX,int maxZ){
        long width=(long)maxX-minX+1,depth=(long)maxZ-minZ+1;
        if(width<1||depth<1||width>MAX_RECTANGLE_SPAN||depth>MAX_RECTANGLE_SPAN)
            throw new IllegalArgumentException("Pond rectangle exceeds bounded jigsaw footprint");
        int minCellX=Math.floorDiv(minX,CELL_SIZE),maxCellX=Math.floorDiv(maxX,CELL_SIZE);
        int minCellZ=Math.floorDiv(minZ,CELL_SIZE),maxCellZ=Math.floorDiv(maxZ,CELL_SIZE);
        for(int cz=minCellZ;cz<=maxCellZ;cz++)for(int cx=minCellX;cx<=maxCellX;cx++){
            if(!eligible(seed,cx,cz)||!intersectsCandidateWindow(cx,cz,minX,minZ,maxX,maxZ))continue;
            Mask mask=cellMask(cx,cz,minX,minZ,maxX,maxZ);
            if(mask==null)continue;
            for(var entry:mask.columns.entrySet()){
                if(!entry.getValue().wet())continue;
                long packed=entry.getKey();int x=(int)(packed>>32),z=(int)packed;
                if(x>=minX&&x<=maxX&&z>=minZ&&z<=maxZ)return true;
            }
        }
        return false;
    }
    private synchronized boolean intersectsCandidateWindow(int cx,int cz,int minX,int minZ,int maxX,int maxZ){
        for(var center:windows(cx,cz)){
            if(minX<=center.x+RADIUS&&maxX>=center.x-RADIUS
                    &&minZ<=center.z+RADIUS&&maxZ>=center.z-RADIUS)return true;
        }
        return false;
    }
    /** Caller owns this planner's monitor; all descriptors are immutable and bounded. */
    private List<CandidateWindow> windows(int cx,int cz){
        long key=pack(cx,cz);var cached=candidateWindows.get(key);if(cached!=null)return cached;
        CandidateWindow[] made=new CandidateWindow[MAX_ATTEMPTS];
        for(int attempt=0;attempt<MAX_ATTEMPTS;attempt++){
            int[] center=candidateCenter(seed,cx,cz,attempt);
            made[attempt]=new CandidateWindow(center[0],center[1]);
        }
        var result=List.of(made);candidateWindows.put(key,result);
        if(candidateWindows.size()>CACHE_LIMIT)candidateWindows.remove(candidateWindows.keySet().iterator().next());
        return result;
    }
    /** Query bounds control admission only; callbacks never run under this planner's monitor. */
    private Mask cellMask(int cx,int cz,int minX,int minZ,int maxX,int maxZ){
        long key=pack(cx,cz);
        CellBuild pending;
        List<CandidateWindow> centers=null;
        boolean owns=false;
        synchronized(this){
            boolean eligible=eligible(seed,cx,cz);
            // Even a completed mask must remain untouched by an outside-window sparse query.
            if(eligible&&!intersectsCandidateWindow(cx,cz,minX,minZ,maxX,maxZ))return null;
            Mask known=cells.get(key);if(known!=null||cells.containsKey(key))return known;
            if(!eligible){cache(key,null);return null;}
            requireNonrecursivePlanning();
            pending=planning.get(key);
            if(pending==null){
                centers=windows(cx,cz);
                pending=new CellBuild(cacheEpoch,new CompletableFuture<>());
                planning.put(key,pending);owns=true;
            }
        }
        if(!owns)return completed(pending);
        // Reserve the key and epoch before a slot wait. Same-key readers never consume slots,
        // and a pre-clear waiter cannot publish in the new epoch when it eventually starts.
        boolean holdsPermit=false;
        try{
            try{coldBuilders.acquire();holdsPermit=true;}
            catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to plan a pond",interrupted);
            }
            constructing.set(true);
            Mask mask=buildMask(key,centers);
            synchronized(this){
                if(pending.epoch==cacheEpoch)cache(key,mask);
                planning.remove(key,pending);
            }
            pending.result.complete(mask);
            return mask;
        }catch(RuntimeException|Error failure){
            synchronized(this){planning.remove(key,pending);}
            pending.result.completeExceptionally(failure);
            throw failure;
        }finally{
            constructing.remove();
            if(holdsPermit)coldBuilders.release();
        }
    }
    /** Captured immutable centers prevent retired builds from touching cleared descriptor state. */
    private Mask buildMask(long key,List<CandidateWindow> centers){
        for(int attempt=0;attempt<MAX_ATTEMPTS;attempt++){
            var center=centers.get(attempt);
            Mask candidate=inspect(mix(seed^key^((long)attempt<<40)),center.x,center.z,sampler);
            if(candidate==null)continue;
            boolean blocked=false;
            for(long column:candidate.columns.keySet())if(conflict.test((int)(column>>32),(int)column)){blocked=true;break;}
            if(!blocked)return candidate;
        }
        return null;
    }
    /** Caller owns this planner's monitor; null remains an authoritative negative result. */
    private void cache(long key,Mask mask){
        cells.put(key,mask);
        if(cells.size()>CACHE_LIMIT)cells.remove(cells.keySet().iterator().next());
    }
    private void requireNonrecursivePlanning(){
        if(Boolean.TRUE.equals(constructing.get()))throw new IllegalStateException("Recursive pond planning");
    }
    private static Mask completed(CellBuild pending){
        try{return pending.result.join();}
        catch(CompletionException failure){
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            if(failure.getCause() instanceof Error error)throw error;
            throw new IllegalStateException("Could not plan pond",failure.getCause());
        }
    }
    /** Immutable accepted mask for one owning cell; uses the same bounded planner as generation. */
    public Mask searchMask(int cellX, int cellZ) {
        long minX = (long) cellX * CELL_SIZE, minZ = (long) cellZ * CELL_SIZE;
        if (minX < Integer.MIN_VALUE || minZ < Integer.MIN_VALUE
                || minX + CELL_SIZE - 1 > Integer.MAX_VALUE || minZ + CELL_SIZE - 1 > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Pond search cell outside integer coordinates");
        return cellMask(cellX, cellZ, (int) minX, (int) minZ,
                (int) minX + CELL_SIZE - 1, (int) minZ + CELL_SIZE - 1);
    }
    public synchronized int cachedCells(){return cells.size();}
    public synchronized int cachedCandidateWindows(){return candidateWindows.size();}
    public synchronized void clear(){cacheEpoch++;cells.clear();candidateWindows.clear();planning.clear();}
    public static boolean eligible(long seed,int cx,int cz){return (mix(seed^pack(cx,cz)^0x504F4E4443454C4CL)&3)==0;}
    public static int[] candidateCenter(long seed,int cx,int cz,int attempt){
        if(attempt<0||attempt>=MAX_ATTEMPTS)throw new IllegalArgumentException("Pond attempt outside budget");
        long h=mix(seed^pack(cx,cz)^0x504F4E4457494E44L^((long)attempt*0x9E3779B97F4A7C15L));
        int span=CELL_SIZE-2*RADIUS;
        return new int[]{cx*CELL_SIZE+RADIUS+(int)Long.remainderUnsigned(h,span),
                cz*CELL_SIZE+RADIUS+(int)Long.remainderUnsigned(mix(h),span)};
    }
    /** Exactly one 49x49 inspection; spill is the minimax path to its observed perimeter. */
    public static Mask inspect(long id,int centerX,int centerZ,Sampler sampler){
        Ground[] nativeGround=new Ground[COUNT];int[] ground=new int[COUNT],spill=new int[COUNT];
        boolean[] visited=new boolean[COUNT];
        PriorityQueue<Integer> frontier=new PriorityQueue<>(Comparator.<Integer>comparingInt(i->spill[i]).thenComparingInt(i->i));
        for(int z=0;z<WINDOW_SIZE;z++)for(int x=0;x<WINDOW_SIZE;x++){
            int i=z*WINDOW_SIZE+x;
            nativeGround[i]=sampler.sample(centerX+x-RADIUS,centerZ+z-RADIUS);
            ground[i]=(int)Math.floor(nativeGround[i].height);
            if(x==0||z==0||x==WINDOW_SIZE-1||z==WINDOW_SIZE-1){visited[i]=true;spill[i]=ground[i];frontier.add(i);}
        }
        while(!frontier.isEmpty()){
            int i=frontier.remove(),x=i%WINDOW_SIZE,z=i/WINDOW_SIZE;
            // Diagonal escape paths also veto a basin, even though its wet mask must be cardinally connected.
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
                int nx=x+dx,nz=z+dz;if(nx<0||nz<0||nx>=WINDOW_SIZE||nz>=WINDOW_SIZE)continue;
                int n=nz*WINDOW_SIZE+nx;if(visited[n])continue;
                visited[n]=true;spill[n]=Math.max(ground[n],spill[i]);frontier.add(n);
            }
        }
        int lowest=-1,maxDepth=0;
        for(int i=0;i<COUNT;i++){
            int depth=spill[i]-1-ground[i];
            if(depth>maxDepth){maxDepth=depth;lowest=i;}
        }
        if(lowest<0||maxDepth<3||maxDepth>8)return null;
        int water=spill[lowest]-1,count=0;
        boolean[] wet=new boolean[COUNT];ArrayDeque<Integer> pending=new ArrayDeque<>();
        wet[lowest]=true;pending.add(lowest);
        while(!pending.isEmpty()){
            int i=pending.remove(),x=i%WINDOW_SIZE,z=i/WINDOW_SIZE;
            if(x==0||z==0||x==WINDOW_SIZE-1||z==WINDOW_SIZE-1||!nativeGround[i].eligible||++count>768)return null;
            if(water-ground[i]>8)return null;
            for(int n:new int[]{i-1,i+1,i-WINDOW_SIZE,i+WINDOW_SIZE})if(!wet[n]&&ground[n]<water){wet[n]=true;pending.add(n);}
        }
        if(count<20)return null;
        boolean[] protectedColumns=Arrays.copyOf(wet,COUNT);
        for(int i=0;i<COUNT;i++)if(wet[i])for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            int n=i+dz*WINDOW_SIZE+dx;
            if(!nativeGround[n].eligible||!wet[n]&&ground[n]<water)return null;
            protectedColumns[n]=true;
        }
        Map<Long,Column> columns=new LinkedHashMap<>();
        for(int i=0;i<COUNT;i++)if(protectedColumns[i]){
            int x=centerX+i%WINDOW_SIZE-RADIUS,z=centerZ+i/WINDOW_SIZE-RADIUS;
            int floor=ground[i]-2;
            if(!wet[i])for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
                int nx=i%WINDOW_SIZE+dx,nz=i/WINDOW_SIZE+dz;
                if(nx>=0&&nz>=0&&nx<WINDOW_SIZE&&nz<WINDOW_SIZE){
                    int n=nz*WINDOW_SIZE+nx;if(wet[n])floor=Math.min(floor,ground[n]-2);
                }
            }
            columns.put(pack(x,z),new Column(true,wet[i],id,nativeGround[i].height,ground[i],water,floor));
        }
        return new Mask(id,water,count,columns);
    }
    private static long pack(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    private static long mix(long x){x=(x^(x>>>30))*0xbf58476d1ce4e5b9L;x=(x^(x>>>27))*0x94d049bb133111ebL;return x^(x>>>31);}
}

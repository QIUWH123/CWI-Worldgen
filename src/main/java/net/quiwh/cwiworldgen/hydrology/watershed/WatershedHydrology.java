package net.quiwh.cwiworldgen.hydrology.watershed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.quiwh.cwiworldgen.hydrology.DirectedReach;
import net.quiwh.cwiworldgen.hydrology.HydrologyBounds;
import net.quiwh.cwiworldgen.hydrology.HydrologyQuery;
import net.quiwh.cwiworldgen.hydrology.HydrologySnapshot;
import net.quiwh.cwiworldgen.hydrology.ReachPoint;
import net.quiwh.cwiworldgen.terrain.RegionalLandforms;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.RiverCarverSettings.RiverZone;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;

/** One world-owned routing provider. Plans sample only uncarved terrain and never request Minecraft chunks. */
public final class WatershedHydrology implements AutoCloseable {
    public static final String PROVIDER="cwi:terrain_watershed";
    public static final int VERSION=2;
    private static final net.quiwh.cwiworldgen.terrain.reference.cell.terrain.Terrain BASIN = net.quiwh.cwiworldgen.terrain.reference.cell.terrain.WatershedTerrain.LAKE;
    private static final int SPACING=128, SIZE=193, CACHE_LIMIT=4, BIN=128;
    private static final int COMPACT_SPACING=80, MAX_SIZE=257;
    private final int seed;
    private final Levels levels;
    private final RegionalLandforms.Sampler base;
    private final RegionalLandforms landforms;
    private final boolean natural;
    private final int generationVersion;
    private final EnclosedPondPlanner ponds;
    private final LinkedHashMap<Long,Bundle> plans=new LinkedHashMap<>(8,.75F,true);
    private final Map<Long,PlanBuild> planning=new HashMap<>();
    private final Semaphore planBuilders=new Semaphore(CACHE_LIMIT);
    private boolean closed;
    private long builds,cacheEpoch;

    private record PlanBuild(long epoch,Thread owner,CompletableFuture<Bundle> result) {}

    public WatershedHydrology(long seed, Levels levels, RegionalLandforms.Sampler base) {
        this(seed, levels, base, 2);
    }
    public WatershedHydrology(long seed, Levels levels, RegionalLandforms.Sampler base, int generationVersion) {
        this.seed=(int)seed;this.levels=levels;this.base=base;
        this.generationVersion=generationVersion;
        natural=generationVersion>=4;
        landforms=new RegionalLandforms(seed,levels,base,generationVersion);
        ponds=generationVersion>=12?new EnclosedPondPlanner(this.seed,this::pondGround,this::pondConflict):null;
    }
    public RegionalLandforms landforms(){return landforms;}
    public void applyLandforms(Cell cell,int x,int z){landforms.apply(cell,x,z);}
    private void uncarved(Cell cell,int x,int z){base.sampleInto(cell,x,z);landforms.apply(cell,x,z);}
    public WatershedSample sample(int x,int z){Cell c=new Cell();uncarved(c,x,z);return sample(c,x,z);}
    public EnclosedPondPlanner.Column pondColumn(int x,int z){
        return ponds==null?EnclosedPondPlanner.Column.NONE:ponds.column(x,z);
    }
    public boolean pondRectangleIntersectsWet(int minX,int minZ,int maxX,int maxZ){
        return ponds!=null&&ponds.rectangleIntersectsWet(minX,minZ,maxX,maxZ);
    }
    private EnclosedPondPlanner.Ground pondGround(int x,int z){
        Cell ground=new Cell();uncarved(ground,x,z);
        boolean allowed=ground.height*levels.worldHeight>levels.waterY+3
                && !ground.terrain.isDeepOcean()&&!ground.terrain.isShallowOcean();
        // Managed mountain bowls and lagoons keep their existing selection and outlets.
        if(allowed)for(var basin:landforms.basinsNear(x,z))if(landforms.basinRadius(basin,x,z)<2.2){allowed=false;break;}
        return new EnclosedPondPlanner.Ground(ground.height*levels.worldHeight,allowed);
    }
    private boolean pondConflict(int x,int z){
        Cell ground=new Cell();uncarved(ground,x,z);
        // Query only a completed old network; never ask sample(), which would plan ponds recursively.
        Bundle bundle=plan(ground.continentX,ground.continentZ);
        return bundle.sample(x,z,ground.height*levels.worldHeight,Math.clamp(.8-.5*ground.erosion,.25,.85)).valid();
    }
    public WatershedSample sample(Cell ground,int x,int z){
        var pond=pondColumn(x,z);
        if(pond.wet())return new WatershedSample(true,pond.id(),0,1,0,0,true,false,true,
                pond.originalGroundY(),pond.waterY(),pond.originalGroundY(),false,false,0);
        return sampleNetwork(ground,x,z);
    }
    private WatershedSample sampleNetwork(Cell ground,int x,int z){
        Bundle b=plan(ground.continentX,ground.continentZ);
        double original=ground.height*levels.worldHeight;
        // Continuous terrain erodibility is a cohesion proxy, not a block-hardness classifier.
        double cohesion=generationVersion>=6?Math.clamp(.8-.5*ground.erosion,.25,.85):1;
        return b.sample(x,z,original,cohesion);
    }
    public void apply(Cell cell,int x,int z){
        var pond=pondColumn(x,z);
        if(pond.protectedColumn()){
            cell.cwiPondProtected=true;cell.cwiPond=pond.wet();
            cell.cwiOriginalGroundHeight=(float)(pond.originalGroundY()/levels.worldHeight);
            cell.height=cell.cwiOriginalGroundHeight;cell.cwiBedHeight=cell.height;
            if(pond.wet()){
                cell.cwiPlannedWater=true;cell.cwiBasin=true;cell.cwiNaturalRiver=true;
                cell.cwiWaterHeight=(float)pond.waterY()/levels.worldHeight;
                cell.riverWaterLevel=cell.cwiWaterHeight;cell.riverZone=RiverZone.Riverbed;
                cell.terrain=BASIN;cell.riverMask=0;cell.cwiChannelWidth=1;
            }
            return;
        }
        WatershedSample s=sampleNetwork(cell,x,z);
        if(!s.valid())return;
        if(s.island()) { cell.cwiIsland=true; cell.cwiOriginalGroundHeight=(float)(s.originalGroundY()/levels.worldHeight); return; }
        double distance=Math.abs(s.distance()), width=s.width();
        double radius=natural?NaturalValley.radius(width):width+Math.max(5,width*.8);
        double fade=1-smooth(width,radius,distance);
        if(fade<=0)return;
        double ground=cell.height*levels.worldHeight;
        double target=s.bedY();
        if(!natural&&distance>width) target=lerp(target,ground,smooth(width,radius,distance));
        cell.height=(float)(Math.min(ground,target)/levels.worldHeight);
        cell.cwiOriginalGroundHeight=(float)(s.originalGroundY()/levels.worldHeight);
        cell.cwiBedHeight=cell.height;
        cell.cwiChannelWidth=(float)width;
        cell.cwiChannelDistance=(float)s.distance();
        cell.cwiDryChannel=s.dry();
        cell.cwiShoal=s.shoal();
        cell.cwiGorge=s.gorge();
        cell.cwiNaturalRiver=natural;
        cell.riverMask=Math.min(cell.riverMask,(float)smooth(0,width+8,distance));
        if(s.wet() && distance<=width){
            cell.cwiPlannedWater=true;
            cell.cwiBasin=s.basin();
            cell.cwiWaterHeight=(float)(s.waterY()/levels.worldHeight);
            cell.riverWaterLevel=Math.max(levels.unit,cell.cwiWaterHeight);
            cell.riverZone=RiverZone.Riverbed;
            cell.terrain=s.basin()?BASIN:TerrainType.RIVER;
        }else if(s.wet()){
            cell.cwiPlannedWater=true;cell.cwiWaterHeight=(float)(s.waterY()/levels.worldHeight);
            cell.riverZone=RiverZone.Banks;
        }
    }
    /** Filters may soften beds. Keep the planned wet interval while allowing ordinary erosion elsewhere. */
    public static void finalizeCell(Cell cell){
        if(cell.cwiPondProtected){
            // Restore the already existing bed and one-column native rim after erosion.
            // No rim elevation or bowl excavation is introduced by this mask.
            cell.height=cell.cwiOriginalGroundHeight;
            if(cell.cwiPond){cell.terrain=BASIN;cell.riverWaterLevel=cell.cwiWaterHeight;}
            return;
        }
        if (cell.cwiIsland) cell.height = cell.cwiOriginalGroundHeight;
        else if (cell.cwiDryChannel) cell.height = Math.min(cell.height, cell.cwiBedHeight);
        if(cell.cwiGorge) cell.height=cell.cwiBedHeight;
        if(cell.cwiPlannedWater && cell.riverWaterLevel > 0) {
            cell.terrain = cell.cwiBasin ? BASIN : TerrainType.RIVER;
            cell.height=cell.cwiShoal ? cell.cwiBedHeight : cell.cwiNaturalRiver ? Math.min(cell.height,cell.cwiBedHeight)
                    : Math.min(cell.height,Math.min(cell.cwiBedHeight,cell.cwiWaterHeight-2F/256F));
        }
    }
    public HydrologyQuery query(int x,int z){
        WatershedSample s=sample(x,z);
        if(!s.valid()||!s.wet()||Math.abs(s.distance())>s.width()||s.basin()||s.island())
            return HydrologyQuery.invalid(s.basin()?"still_watershed_basin":"outside_watershed_water");
        return new HydrologyQuery(true,s.reachId(),s.progress(),s.tangentX(),s.tangentZ(),s.distance(),"directed_watershed_reach");
    }
    public HydrologySnapshot snapshot(int x,int z){
        Cell c=new Cell();uncarved(c,x,z);Bundle b=plan(c.continentX,c.continentZ);
        return new HydrologySnapshot(PROVIDER,VERSION,seed,c.continentX,c.continentZ,
                new HydrologyBounds(b.plan.originX(),b.plan.originZ(),b.plan.originX()+(b.plan.size()-1)*b.plan.spacing(),b.plan.originZ()+(b.plan.size()-1)*b.plan.spacing()),b.topology());
    }
    public record Diagnostics(int nodes, int ownedNodes, int oceanSeeds, int terminalComponents,
            int clippedLandNodes, int wetReaches, int dryReaches, int islandCorridors, int mountainLakes, int coastalLagoons,
            int gridSpacing, double ownedLandArea, int wetCandidates, int acceptedWetCandidates) {}
    public Diagnostics diagnostics(int x,int z) {
        Cell c=new Cell();uncarved(c,x,z);Bundle b=plan(c.continentX,c.continentZ);
        int owned=0,land=0,clipped=0,wet=0,dry=0,mountain=0,coast=0;
        for(int i=0;i<b.plan.count();i++) if(b.plan.owned(i)) {
            owned++;
            if(!b.plan.ocean(i))land++;
            int gx=i%b.plan.size(),gz=i/b.plan.size();
            if((gx==0||gz==0||gx==b.plan.size()-1||gz==b.plan.size()-1)&&!b.plan.ocean(i))clipped++;
        }
        for(var ch:b.channels)if(ch.wet)wet++;else dry++;
        for(var lake:b.lakes)if(lake.basin.kind()==RegionalLandforms.Kind.MOUNTAIN)mountain++;else coast++;
        int candidates=0,accepted=0;
        for(var edge:b.plan.edges())if(edge.wet()){
            candidates++;if(b.projection==null||b.projection.wet(edge.from()))accepted++;
        }
        return new Diagnostics(b.plan.count(),owned,b.plan.oceanSeedCount(),b.plan.terminalCount(),clipped,wet,dry,b.islands.size(),mountain,coast,
                b.plan.spacing(),land*(double)b.plan.spacing()*b.plan.spacing(),candidates,accepted);
    }
    public List<RegionalLandforms.Basin> basins(int x,int z) {
        Cell c=new Cell();uncarved(c,x,z);Bundle b=plan(c.continentX,c.continentZ);
        return b.lakes.stream().map(Lake::basin).toList();
    }
    /** Accepted generation geometry only; consumers must still confirm final terrain/water. */
    public record SearchPoint(double x, double z, double waterY) {}
    public record SearchReach(long id, boolean wet, double width, List<SearchPoint> points) {
        public SearchReach { points = List.copyOf(points); }
    }
    public record SearchSnapshot(int continentX, int continentZ,
            List<SearchReach> reaches, List<RegionalLandforms.Basin> basins, Set<Long> lagoonReaches) {
        public SearchSnapshot {
            reaches = List.copyOf(reaches);
            basins = List.copyOf(basins);
            lagoonReaches = Set.copyOf(lagoonReaches);
        }
    }
    public SearchSnapshot searchSnapshot(int x, int z) {
        Cell c = new Cell(); uncarved(c, x, z);
        return plan(c.continentX, c.continentZ).searchSnapshot();
    }
    public EnclosedPondPlanner.Mask pondSearchMask(int cellX, int cellZ) {
        return ponds == null ? null : ponds.searchMask(cellX, cellZ);
    }
    public synchronized int cachedPlans(){return plans.size();}
    public synchronized long builtPlans(){return builds;}
    public void clearPlans(){
        // Pond planning may request a completed network: never hold the network lock while acquiring its cache lock.
        if(ponds!=null)ponds.clear();
        synchronized(this){cacheEpoch++;plans.clear();}
    }
    @Override public void close(){
        if(ponds!=null)ponds.clear();
        synchronized(this){cacheEpoch++;plans.clear();closed=true;}
        landforms.close();
    }
    private Bundle plan(int cx,int cz){
        long key=pack(cx,cz);
        PlanBuild pending;
        synchronized(this){
            requireOpen();
            Bundle known=plans.get(key);if(known!=null)return known;
            requireNonrecursivePlanning();
            pending=planning.get(key);
        }
        if(pending!=null)return completed(pending);
        // Bound concurrent cold builders without holding the provider monitor. Construction
        // uses only base terrain/descriptors and must never request a neighbouring plan.
        try{planBuilders.acquire();}
        catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to plan a watershed",interrupted);
        }
        boolean owns=false,holdsPermit=true;
        try{
            synchronized(this){
                requireOpen();
                Bundle known=plans.get(key);if(known!=null)return known;
                requireNonrecursivePlanning();
                pending=planning.get(key);
                if(pending==null){
                    pending=new PlanBuild(cacheEpoch,Thread.currentThread(),new CompletableFuture<>());
                    planning.put(key,pending);owns=true;
                }
            }
            if(!owns){
                // Another caller won the key while this caller waited for a builder slot.
                planBuilders.release();
                holdsPermit=false;
                return completed(pending);
            }
            try{
                Bundle built=buildPlan(cx,cz);
                synchronized(this){
                    builds++;
                    if(!closed&&pending.epoch==cacheEpoch){
                        plans.put(key,built);
                        if(plans.size()>CACHE_LIMIT)plans.remove(plans.keySet().iterator().next());
                    }
                    planning.remove(key,pending);
                }
                pending.result.complete(built);
                return built;
            }catch(RuntimeException|Error failure){
                synchronized(this){planning.remove(key,pending);}
                pending.result.completeExceptionally(failure);
                throw failure;
            }
        }finally{if(holdsPermit)planBuilders.release();}
    }
    private void requireOpen(){
        if(closed)throw new IllegalStateException("Closed watershed provider");
    }
    /** Pure planning must not wait on its own key or consume another bounded builder slot. */
    private void requireNonrecursivePlanning(){
        for(PlanBuild build:planning.values())if(build.owner==Thread.currentThread())
            throw new IllegalStateException("Recursive watershed planning");
    }
    private static Bundle completed(PlanBuild pending){
        try{return pending.result.join();}
        catch(CompletionException failure){
            if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
            if(failure.getCause() instanceof Error error)throw error;
            throw new IllegalStateException("Could not plan watershed",failure.getCause());
        }
    }
    private Bundle buildPlan(int cx,int cz){
        // Build on the caller thread: no worker waits on its own worldgen executor.
        int spacing=generationVersion>=7?COMPACT_SPACING:SPACING;
        int size=SIZE;
        if(generationVersion>=7)while(size<MAX_SIZE&&clipsOwnedLand(cx,cz,size,spacing))size+=32;
        int ox=cx-(size-1)/2*spacing,oz=cz-(size-1)/2*spacing;
        Cell reusable=new Cell();
        // Admit smaller tributary catchments (64,000 blocks²) only for new profile9 worlds.
        // Saved profiles retain their original catchment threshold and exact geography.
        WatershedPlan graph=new WatershedPlan(ox,oz,size,spacing,levels.waterY,(x,z)->{
            reusable.reset();uncarved(reusable,x,z);
            return new WatershedPlan.Ground(reusable.height*levels.worldHeight,
                    reusable.terrain.isDeepOcean()||reusable.terrain.isShallowOcean(),
                    reusable.continentX==cx&&reusable.continentZ==cz);
        },generationVersion>=7?8:5,generationVersion>=9?10:22,generationVersion);
        return new Bundle(cx,cz,graph);
    }
    /** Expand only when actual owned land reaches a perimeter. A bounded unresolved clip
     * remains explicit in diagnostics and terminal drainage; it never becomes an ocean. */
    private boolean clipsOwnedLand(int cx,int cz,int size,int spacing){
        int ox=cx-(size-1)/2*spacing,oz=cz-(size-1)/2*spacing;
        Cell probe=new Cell();
        for(int i=0;i<size;i++)for(int side=0;side<4;side++){
            int x=side<2?ox+i*spacing:ox+(side-2)*(size-1)*spacing;
            int z=side<2?oz+side*(size-1)*spacing:oz+i*spacing;
            probe.reset();uncarved(probe,x,z);
            if(probe.continentX==cx&&probe.continentZ==cz&&!probe.terrain.isDeepOcean()&&!probe.terrain.isShallowOcean())return true;
        }
        return false;
    }
    private double physicalArea(WatershedPlan plan,double cells){
        return generationVersion>=7?cells*plan.spacing()*plan.spacing()/(128.*128.):cells;
    }
    private record Point(double x,double z,double water){}
    private record Channel(long id,int from,int to,List<Point> points,double width,double depth,boolean wet,boolean split){}
    private record Segment(Channel channel,int index){}
    private record RuntimeSegment(Channel channel,int index,double startWidth,double endWidth,double maximumRadius,
            double minimumX,double maximumX,double minimumZ,double maximumZ){
        private RuntimeSegment(Channel channel,int index,double startWidth,double endWidth,double maximumRadius){
            this(channel,index,startWidth,endWidth,maximumRadius,projectionBound(channel,index,maximumRadius));
        }
        private RuntimeSegment(Channel channel,int index,double startWidth,double endWidth,double maximumRadius,double bound){
            this(channel,index,startWidth,endWidth,maximumRadius,
                    Math.nextDown(Math.min(channel.points.get(index-1).x,channel.points.get(index).x)-bound),
                    Math.nextUp(Math.max(channel.points.get(index-1).x,channel.points.get(index).x)+bound),
                    Math.nextDown(Math.min(channel.points.get(index-1).z,channel.points.get(index).z)-bound),
                    Math.nextUp(Math.max(channel.points.get(index-1).z,channel.points.get(index).z)+bound));
        }
        private static double projectionBound(Channel channel,int index,double radius){
            Point a=channel.points.get(index-1),b=channel.points.get(index);
            double scale=Math.max(Math.max(Math.abs(a.x),Math.abs(b.x)),Math.max(Math.abs(a.z),Math.abs(b.z)));
            // Outside integer-column coordinates, retain the original projection:
            // nonfinite/overflow arithmetic can otherwise change its NaN fallback.
            if(!Double.isFinite(scale)||scale>0x1p31||!Double.isFinite(radius)||radius<=0||radius>0x1p31)
                return Double.POSITIVE_INFINITY;
            // A one-block halo exceeds lerp/box roundoff in the supported
            // integer-world domain; rounding still goes outward on each side.
            return Math.nextUp(radius+1);
        }
    }
    private record Lake(RegionalLandforms.Basin basin,Channel connector){}
    private final class Bundle {
        final int cx,cz;final WatershedPlan plan;
        final List<Channel> channels;
        final List<Channel> islands;
        final Map<Long,List<RuntimeSegment>> bins;
        final List<Lake> lakes;
        final Set<Long> plungeReaches;
        final NaturalRiverNetwork projection;
        final Map<Long,HydraulicChannelProfile.Geometry> hydraulics;
        private SearchSnapshot searchSnapshot;
        Bundle(int cx,int cz,WatershedPlan plan){
            this.cx=cx;this.cz=cz;this.plan=plan;
            // Basin validation can rebuild candidate routes. Native terrain is pure at integer
            // coordinates, so reuse those build-local samples without retaining a world cache.
            Long2FloatOpenHashMap groundSamples=natural?new Long2FloatOpenHashMap():null;
            if(natural)groundSamples.defaultReturnValue(Float.NaN);
            var foreignSamples=generationVersion>=9?new it.unimi.dsi.fastutil.longs.LongOpenHashSet():null;
            Cell scratch=new Cell();
            java.util.function.DoubleBinaryOperator groundHeight=(x,z)->{
                int bx=(int)Math.round(x),bz=(int)Math.round(z);long key=pack(bx,bz);
                float known=groundSamples.get(key);if(!Float.isNaN(known))return known;
                scratch.reset();uncarved(scratch,bx,bz);float value=scratch.height*levels.worldHeight;
                groundSamples.put(key,value);
                if(foreignSamples!=null&&(scratch.continentX!=cx||scratch.continentZ!=cz))foreignSamples.add(key);
                return value;
            };
            // Share the terrain memo with the off-grid ownership screen; never request a
            // neighbouring plan while building this one. The cache dies with this build.
            java.util.function.BiPredicate<Integer,Integer> owns=generationVersion>=9?(x,z)->{
                groundHeight.applyAsDouble(x,z);return !foreignSamples.contains(pack(x,z));
            }:(x,z)->true;
            List<RegionalLandforms.Basin> candidates=basinCandidates(plan);
            ArrayList<Lake> accepted=new ArrayList<>();
            for(var basin:candidates){
                Cell owner = new Cell(); uncarved(owner, (int)basin.centerX(), (int)basin.centerZ());
                if (owner.continentX != cx || owner.continentZ != cz) continue;
                int node=nearestOutlet(plan,basin);if(node<0)continue;
                long id=mix(basin.id()^0x424153494EL);
                var outlet=new Point(basin.outletX(),basin.outletZ(),basin.spillY());
                var dest=new Point(plan.x(node),plan.z(node),Math.min(basin.spillY(),plan.water(node)));
                Channel connector=new Channel(id,-1,node,List.of(new Point(basin.centerX(),basin.centerZ(),basin.spillY()),outlet,dest),4.5,3,true,false);
                accepted.add(new Lake(basin,connector));
            }
            Network network;
            NaturalRiverNetwork.RebuildWorkspace routeInputs=natural?new NaturalRiverNetwork.RebuildWorkspace(
                    plan,mix(pack(cx,cz)^seed),groundHeight,levels.waterY,generationVersion):null;
            while (true) {
                network = buildChannels(accepted,groundHeight,owns,routeInputs);
                ArrayList<Lake> retained = new ArrayList<>();
                for (Lake lake : network.lakes) if (!hasLowerInlet(lake, network.channels, network.projection)) retained.add(lake);
                if (retained.size() == accepted.size()) break;
                // A bowl with an incompatible inlet stays dry; never invent an uphill water step.
                accepted = retained;
            }
            if(generationVersion>=16&&natural)network=decorateSourceMouths(network,groundHeight,owns);
            lakes=network.lakes;
            channels=network.channels;
            islands=network.islands;
            projection=network.projection;
            Map<Long,HydraulicChannelProfile.Geometry> hydraulicData=new HashMap<>();
            if(generationVersion>=6)for(Channel channel:channels)if(channel.wet&&channel.from>=0&&!channel.split)
                hydraulicData.put(channel.id,new HydraulicChannelProfile.Geometry(channel.points.stream()
                        .map(p->new HydraulicChannelProfile.Control(p.x,p.z,p.water)).toList()));
            hydraulics=Map.copyOf(hydraulicData);
            Map<Integer,Double> incomingDrops=new HashMap<>();
            for(Channel channel:channels) if(channel.wet&&channel.from>=0)
                incomingDrops.merge(channel.to,channel.points.getFirst().water-channel.points.getLast().water,Math::max);
            Set<Long> plunges=new HashSet<>();
            for(Channel channel:channels) if(channel.wet&&channel.from>=0&&!channel.split
                    &&incomingDrops.getOrDefault(channel.from,0.)>=4
                    &&channel.points.getFirst().water-channel.points.getLast().water<=1.)plunges.add(channel.id);
            plungeReaches=Set.copyOf(plunges);
            Map<Long,ArrayList<RuntimeSegment>> indexed=new HashMap<>();
            // Segments enter each bin in the original channel/point order. The
            // bounds enclose every scoring footprint, preserving strict tie wins.
            for(Channel c:channels){double margin=natural?NaturalValley.radius(generationVersion>=8?28:19):c.width*2+8;
                double maximumRadius=Double.POSITIVE_INFINITY;
                if(generationVersion>=16&&natural){
                    double maximumWidth=c.from>=0?projection.maximumWidth(c.from):c.width;
                    if(Double.isFinite(maximumWidth)&&maximumWidth>0){
                        double radius=NaturalValley.radius(maximumWidth);
                        maximumRadius=radius+4*Math.ulp(radius);
                    }
                }
                for(int i=1;i<c.points.size();i++){
                Point a=c.points.get(i-1),b=c.points.get(i);
                // Endpoint widths use the final decorated projection; angular caps stay coordinate-dependent.
                double startWidth=0,endWidth=0;
                if(generationVersion>=16){
                    double dx=b.x-a.x,dz=b.z-a.z,len2=dx*dx+dz*dz;
                    if(!(len2<1E-8)){
                        startWidth=channelWidth(c,(i-1+0.0)/(c.points.size()-1),projection);
                        endWidth=channelWidth(c,(i-1+1.0)/(c.points.size()-1),projection);
                    }
                }
                RuntimeSegment segment=new RuntimeSegment(c,i,startWidth,endWidth,maximumRadius);
                int minX=Math.floorDiv((int)Math.floor(Math.min(a.x,b.x)-margin),BIN),maxX=Math.floorDiv((int)Math.ceil(Math.max(a.x,b.x)+margin),BIN);
                int minZ=Math.floorDiv((int)Math.floor(Math.min(a.z,b.z)-margin),BIN),maxZ=Math.floorDiv((int)Math.ceil(Math.max(a.z,b.z)+margin),BIN);
                for(int bz=minZ;bz<=maxZ;bz++)for(int bx=minX;bx<=maxX;bx++){
                    indexed.computeIfAbsent(pack(bx,bz),ignored->new ArrayList<>()).add(segment);
                }
            }}
            Map<Long,List<RuntimeSegment>> frozen=new HashMap<>();indexed.forEach((k,v)->frozen.put(k,List.copyOf(v)));bins=Map.copyOf(frozen);
        }
        private record Network(List<Channel> channels, List<Channel> islands, List<Lake> lakes, NaturalRiverNetwork projection) {}
        private Network decorateSourceMouths(Network admitted,java.util.function.DoubleBinaryOperator ground,
                java.util.function.BiPredicate<Integer,Integer> owns) {
            Set<Integer> splitSources=new HashSet<>();
            for(Channel channel:admitted.channels)if(channel.split&&channel.from>=0)splitSources.add(channel.from);
            NaturalRiverNetwork decorated=admitted.projection.withSourceMouths(ground,owns,splitSources);
            if(decorated==admitted.projection)return admitted;
            // Optional geometry cannot remove a retained basin, its promoted routes, or a split.
            for(Lake lake:admitted.lakes)
                if(hasLowerInlet(lake,admitted.channels,decorated))return admitted;
            if(!admitted.islands.isEmpty()
                    &&!splitConflicts(admitted.channels,admitted.islands,decorated,ground,owns).isEmpty())return admitted;
            return new Network(admitted.channels,admitted.islands,admitted.lakes,decorated);
        }
        private Network buildChannels(List<Lake> accepted,java.util.function.DoubleBinaryOperator groundHeight,
                java.util.function.BiPredicate<Integer,Integer> owns,NaturalRiverNetwork.RebuildWorkspace routeInputs) {
            Set<Integer> promoted = new HashSet<>();
            for (Lake lake : accepted) {
                int p=lake.connector.to;
                while(plan.receiver(p)>=0){promoted.add(p);p=plan.receiver(p);}
            }
            ArrayList<Channel> made=new ArrayList<>();
            ArrayList<Channel> preserved=new ArrayList<>();
            Map<Integer,Channel> unsplit=generationVersion>=9?new HashMap<>():null;
            ArrayList<WatershedPlan.Edge> edges = new ArrayList<>(plan.edges());
            Set<Integer> existing = new HashSet<>();
            for (var edge : edges) existing.add(edge.from());
            for (int from : promoted) if (!existing.contains(from) && plan.receiver(from) >= 0) {
                int to = plan.receiver(from);
                edges.add(new WatershedPlan.Edge(from,to,plan.x(from),plan.z(from),plan.x(to),plan.z(to),plan.water(from),plan.water(to),plan.area(from),true));
            }
            edges.sort(Comparator.comparingInt(WatershedPlan.Edge::from));
            if(natural)edges.replaceAll(e->promoted.contains(e.from())&&!e.wet()?new WatershedPlan.Edge(
                    e.from(),e.to(),e.x1(),e.z1(),e.x2(),e.z2(),e.water1(),e.water2(),e.area(),true):e);
            NaturalRiverNetwork curves=natural?new NaturalRiverNetwork(plan,edges,mix(pack(cx,cz)^seed),groundHeight,
                    levels.waterY,generationVersion,owns,generationVersion<16,routeInputs):null;
            for(var edge:edges){
                // A closed depression is left uncut when no supported downhill water profile exists.
                if(natural&&edge.wet()&&!curves.wet(edge.from()))continue;
                boolean wet=natural?curves.wet(edge.from()):edge.wet()||promoted.contains(edge.from());
                double area=physicalArea(plan,edge.area());
                double width=wet?Math.min(18,4+Math.sqrt(area)*.52):2.2+Math.min(3,Math.sqrt(area)*.3);
                double depth=wet?3+Math.min(5,Math.sqrt(area)*.12):1.4+Math.min(2.5,Math.sqrt(area)*.07);
                long id=reachId(cx,cz,edge.from(),0);
                Cell midpoint=new Cell();uncarved(midpoint,(int)((edge.x1()+edge.x2())*.5),(int)((edge.z1()+edge.z2())*.5));
                double middleWater=Math.max(edge.water1(),edge.water2());
                if(natural){
                    var path=curves.points(edge.from());var p=path.get(path.size()/2);
                    uncarved(midpoint,(int)Math.round(p.x()),(int)Math.round(p.z()));middleWater=p.water();
                }
                boolean split=wet&&area>=70&&(mix(id)&7)==0
                        &&midpoint.height*levels.worldHeight>middleWater+5;
                if(natural&&split&&!safeSplit(edge,curves,width))split=false;
                if(split){
                    if(unsplit!=null)unsplit.put(edge.from(),channel(edge,id,width,depth,wet,0,false,curves));
                    made.add(channel(edge,id,width*.48,depth,wet,-width*1.35,true,curves));
                    made.add(channel(edge,reachId(cx,cz,edge.from(),1),width*.48,depth,wet,width*1.35,true,curves));
                    preserved.add(new Channel(id, edge.from(), edge.to(),
                            natural?curves.points(edge.from()).stream().map(p->new Point(p.x(),p.z(),p.water())).toList():List.of(new Point(edge.x1(),edge.z1(),edge.water1()),new Point(edge.x2(),edge.z2(),edge.water2())),width*.42,depth,true,true));
                }else made.add(channel(edge,id,width,depth,wet,0,false,curves));
            }
            ArrayList<Lake> adjusted=new ArrayList<>();
            for(Lake lake:accepted){
                Lake actual=lake;
                if(natural){
                    if(plan.receiver(lake.connector.to)>=0&&!curves.wet(lake.connector.to))continue;
                    var path=curves.points(lake.connector.to);
                    Point dest=path.isEmpty()?new Point(plan.x(lake.connector.to),plan.z(lake.connector.to),levels.waterY)
                            :new Point(path.getFirst().x(),path.getFirst().z(),path.getFirst().water());
                    if(dest.water>lake.basin.spillY())continue;
                    Channel connector=new Channel(lake.connector.id,-1,lake.connector.to,List.of(
                            new Point(lake.basin.centerX(),lake.basin.centerZ(),lake.basin.spillY()),
                            new Point(lake.basin.outletX(),lake.basin.outletZ(),lake.basin.spillY()),dest),4.5,3,true,false);
                    actual=new Lake(lake.basin,connector);
                    if(perchedConnector(actual))continue;
                }
                adjusted.add(actual);made.add(actual.connector);
            }
            if(generationVersion>=9&&!preserved.isEmpty()){
                // Split offsets are decorations added after the retained trunk network was
                // validated. Restore a conflicting decoration's accepted original channel;
                // never remove a wet route or leave its preserved island behind.
                int attempts=preserved.size();
                for(int attempt=0;attempt<attempts&&!preserved.isEmpty();attempt++){
                    Set<Integer> conflicts=splitConflicts(made,preserved,curves,groundHeight,owns);
                    if(conflicts.isEmpty())break;
                    ArrayList<Channel> restored=new ArrayList<>();Set<Integer> added=new HashSet<>();
                    for(Channel channel:made){
                        if(channel.split&&conflicts.contains(channel.from)){
                            if(added.add(channel.from))restored.add(unsplit.get(channel.from));
                        }else restored.add(channel);
                    }
                    made=restored;preserved.removeIf(island->conflicts.contains(island.from));
                }
            }
            return new Network(List.copyOf(made), List.copyOf(preserved),List.copyOf(adjusted),curves);
        }
        private record SplitProjection(Channel channel,double water) {}
        // The immutable admission index owns this build-local memo. Restored channels
        // get a new index; a decorated projection explicitly invalidates its old values.
        private static final int PROJECTION_CACHE_LIMIT=32768;
        private final class SplitProjectionIndex extends HashMap<Long,List<Segment>> {
            private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<SplitProjection> projected=
                    new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
            private final SplitProjection absent=new SplitProjection(null,0);
            private NaturalRiverNetwork projection;
            private boolean lastValid;
            private long lastColumn;
            private SplitProjection lastValue;
            private void bind(NaturalRiverNetwork curves){
                if(projection!=curves){projected.clear();projection=curves;lastValid=false;}
            }
            private SplitProjection remember(long column,SplitProjection result){
                // Clearing only changes hit rate. It cannot change projection order,
                // winners or floating-point arithmetic on a cache miss.
                if(projected.size()>=PROJECTION_CACHE_LIMIT)projected.clear();
                projected.put(column,result==null?absent:result);
                lastColumn=column;lastValue=result;lastValid=true;
                return result;
            }
        }
        private Map<Long,List<Segment>> splitIndex(List<Channel> channels,boolean island){
            final int size=32;double margin=island?13:29;
            Map<Long,List<Segment>> result=island?new HashMap<>():new SplitProjectionIndex();
            for(Channel channel:channels)if(channel.wet)for(int i=1;i<channel.points.size();i++){
                Point a=channel.points.get(i-1),b=channel.points.get(i);Segment segment=new Segment(channel,i);
                int x0=Math.floorDiv((int)Math.floor(Math.min(a.x,b.x)-margin),size),x1=Math.floorDiv((int)Math.ceil(Math.max(a.x,b.x)+margin),size);
                int z0=Math.floorDiv((int)Math.floor(Math.min(a.z,b.z)-margin),size),z1=Math.floorDiv((int)Math.ceil(Math.max(a.z,b.z)+margin),size);
                for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++)result.computeIfAbsent(pack(x,z),ignored->new ArrayList<>()).add(segment);
            }
            return result;
        }
        private SplitProjection splitProjection(int x,int z,Map<Long,List<Segment>> index,NaturalRiverNetwork curves){
            if(index instanceof SplitProjectionIndex memo){
                memo.bind(curves);long column=pack(x,z);
                if(memo.lastValid&&memo.lastColumn==column)return memo.lastValue;
                SplitProjection known=memo.projected.get(column);
                if(known!=null){
                    memo.lastColumn=column;memo.lastValue=known==memo.absent?null:known;memo.lastValid=true;
                    return memo.lastValue;
                }
                return memo.remember(column,calculateSplitProjection(x,z,index,curves));
            }
            return calculateSplitProjection(x,z,index,curves);
        }
        private SplitProjection calculateSplitProjection(int x,int z,Map<Long,List<Segment>> index,NaturalRiverNetwork curves){
            var nearby=index.get(pack(Math.floorDiv(x,32),Math.floorDiv(z,32)));
            if(nearby==null)return null;
            Channel selected=null;double score=Double.POSITIVE_INFINITY,water=0;
            for(Segment segment:nearby){
                Channel channel=segment.channel;int i=segment.index;
                Point a=channel.points.get(i-1),b=channel.points.get(i);
                // Current width profiles stay below26.88; source caps are at most5.5.
                // Pad wider constant connectors for outward endpoint/lerp rounding.
                double bound=channel.from>=0?29:Math.max(29,channel.width+1);
                if(x<Math.min(a.x,b.x)-bound||x>Math.max(a.x,b.x)+bound
                        ||z<Math.min(a.z,b.z)-bound||z>Math.max(a.z,b.z)+bound)continue;
                double dx=b.x-a.x,dz=b.z-a.z,len2=dx*dx+dz*dz;if(len2<1E-8)continue;
                double t=Math.clamp(((x-a.x)*dx+(z-a.z)*dz)/len2,0,1);
                double px=lerp(a.x,b.x,t),pz=lerp(a.z,b.z,t);
                double distance=Math.hypot(x-px,z-pz),progress=(i-1+t)/(channel.points.size()-1);
                double width=channelWidth(channel,progress,curves);
                width=channelCapWidth(channel,progress,curves,x,z,px,pz,dx,dz,width);
                if(distance>width)continue;
                double candidate=distance/width-2;
                if(candidate>=score)continue;
                selected=channel;score=candidate;water=lerp(a.water,b.water,t);
            }
            return selected==null?null:new SplitProjection(selected,water);
        }
        private Channel splitIsland(int x,int z,Map<Long,List<Segment>> index,NaturalRiverNetwork curves,
                java.util.function.DoubleBinaryOperator groundHeight){
            var nearby=index.get(pack(Math.floorDiv(x,32),Math.floorDiv(z,32)));
            if(nearby==null)return null;
            for(Segment segment:nearby){
                Channel island=segment.channel;Point start=island.points.getFirst();
                if(Math.abs(x-start.x)>350||Math.abs(z-start.z)>350)continue;
                int i=segment.index;Point a=island.points.get(i-1),b=island.points.get(i);
                double dx=b.x-a.x,dz=b.z-a.z,len2=dx*dx+dz*dz;if(len2<1E-8)continue;
                double t=Math.clamp(((x-a.x)*dx+(z-a.z)*dz)/len2,0,1),progress=(i-1+t)/(island.points.size()-1);
                if(progress<.22||progress>.78)continue;
                double distance=Math.hypot(x-lerp(a.x,b.x,t),z-lerp(a.z,b.z,t));
                double width=curves.width(island.from,progress)*.42;
                if(distance<width*Math.sin(Math.PI*progress)
                        &&groundHeight.applyAsDouble(x,z)>lerp(a.water,b.water,t)+5)return island;
            }
            return null;
        }
        private Set<Integer> splitConflicts(List<Channel> channels,List<Channel> islands,NaturalRiverNetwork curves,
                java.util.function.DoubleBinaryOperator groundHeight,java.util.function.BiPredicate<Integer,Integer> owns){
            var index=splitIndex(channels,false);var islandIndex=splitIndex(islands,true);
            Set<Integer> conflicts=new HashSet<>();
            for(Channel channel:channels)if(channel.wet){
                SplitProjection previous=null;
                for(int i=1;i<channel.points.size();i++){
                    Point a=channel.points.get(i-1),b=channel.points.get(i);
                    double dx=b.x-a.x,dz=b.z-a.z,length=Math.hypot(dx,dz);
                    int count=Math.max(2,2*(int)Math.ceil(length));
                    for(int n=0;n<=count;n++){
                        double t=n/(double)count,px=lerp(a.x,b.x,t),pz=lerp(a.z,b.z,t);
                        int x=(int)Math.round(px),z=(int)Math.round(pz);
                        Channel island=splitIsland(x,z,islandIndex,curves,groundHeight);
                        if(island!=null){conflicts.add(island.from);continue;}
                        if(channel.split&&length>1E-8){
                            double width=channelWidth(channel,(i-1+t)/(channel.points.size()-1),curves);
                            double bx=-dz/length*width,bz=dx/length*width;
                            if(!owns.test(x,z)||!owns.test((int)Math.round(px+bx),(int)Math.round(pz+bz))
                                    ||!owns.test((int)Math.round(px-bx),(int)Math.round(pz-bz)))conflicts.add(channel.from);
                        }
                        SplitProjection current=splitProjection(x,z,index,curves);
                        if(current==null){if(channel.split)conflicts.add(channel.from);continue;}
                        if(previous!=null&&Math.floor(current.water)>Math.floor(previous.water)){
                            if(channel.split)conflicts.add(channel.from);
                            if(previous.channel.split)conflicts.add(previous.channel.from);
                            if(current.channel.split)conflicts.add(current.channel.from);
                        }
                        previous=current;
                    }
                }
            }
            return conflicts;
        }
        private boolean perchedConnector(Lake lake){
            var basin=lake.basin;
            for(int i=1;i<lake.connector.points.size();i++){
                Point a=lake.connector.points.get(i-1),b=lake.connector.points.get(i);
                double length=Math.hypot(b.x-a.x,b.z-a.z);
                int n=generationVersion>=10?Math.max(2,2*(int)Math.ceil(length))
                        :Math.max(1,(int)Math.ceil(length/2));
                int lastX=0,lastZ=0;boolean sampledColumn=false;
                for(int j=0;j<=n;j++){
                    double t=j/(double)n,x=lerp(a.x,b.x,t),z=lerp(a.z,b.z,t);
                    double bx=(x-basin.centerX())/basin.radiusX(),bz=(z-basin.centerZ())/basin.radiusZ();
                    if((generationVersion>=8?Math.pow(landforms.basinRadius(basin,x,z),2):bx*bx+bz*bz)
                            <(generationVersion>=10?1:1.04))continue;
                    int columnX=(int)Math.round(x),columnZ=(int)Math.round(z);
                    if(generationVersion>=10&&sampledColumn&&columnX==lastX&&columnZ==lastZ)continue;
                    lastX=columnX;lastZ=columnZ;sampledColumn=true;
                    Cell c=new Cell();uncarved(c,columnX,columnZ);
                    double water=lerp(a.water,b.water,t),host=c.height*levels.worldHeight;
                    if(water>Math.max(levels.waterY,host)+1)return true;
                    // Lake connectors bypass the retained trunk solver. Apply its
                    // incision budget before accepting the whole basin, so a sea-level
                    // lagoon cannot carve a straight trench through a coastal ridge.
                    if(generationVersion>=10&&host-water>30)return true;
                }
            }
            return false;
        }
        private boolean safeSplit(WatershedPlan.Edge edge,NaturalRiverNetwork curves,double baseWidth){
            var points=curves.points(edge.from());
            Cell ground=new Cell();
            for(int i=0;i<points.size();i++){
                var p=points.get(i);var a=points.get(Math.max(0,i-1));var b=points.get(Math.min(points.size()-1,i+1));
                double dx=b.x()-a.x(),dz=b.z()-a.z(),length=Math.hypot(dx,dz),t=i/(double)(points.size()-1);
                double splitWeight=Math.pow(Math.sin(Math.PI*t),2);
                double offsetWidth=generationVersion>=8?curves.width(edge.from(),t):baseWidth;
                double outer=offsetWidth*1.35*splitWeight+curves.width(edge.from(),t)*(1-.52*splitWeight);
                for(int sign:new int[]{-1,1}){
                    uncarved(ground,(int)Math.round(p.x()-dz/length*outer*sign),(int)Math.round(p.z()+dx/length*outer*sign));
                    if(generationVersion>=9&&(ground.continentX!=cx||ground.continentZ!=cz))return false;
                    if(p.water()>Math.max(levels.waterY,ground.height*levels.worldHeight-.5))return false;
                    if(generationVersion>=5){
                        // Split lanes move away from the validated trunk. Their own centers
                        // must retain the same incision budget as the original route.
                        double offset=offsetWidth*1.35*splitWeight*sign;
                        uncarved(ground,(int)Math.round(p.x()-dz/length*offset),(int)Math.round(p.z()+dx/length*offset));
                        if(generationVersion>=9&&(ground.continentX!=cx||ground.continentZ!=cz))return false;
                        if(ground.height*levels.worldHeight-p.water()>30)return false;
                    }
                }
            }
            return true;
        }
        private boolean hasLowerInlet(Lake lake, List<Channel> routed, NaturalRiverNetwork curves) {
            var basin=lake.basin;
            // A through-channel at a different level would drop into the bowl and step back up on exit.
            for (Channel channel : routed) {
                if (!channel.wet || channel.id == lake.connector.id) continue;
                if(generationVersion>=8) {
                    if(conflictingWetFootprint(basin,channel,curves))return true;
                    continue;
                }
                for (int i=1;i<channel.points.size();i++) {
                    Point a=channel.points.get(i-1), b=channel.points.get(i);
                    int steps=Math.max(1,(int)Math.ceil(Math.hypot(b.x-a.x,b.z-a.z)/4));
                    for(int n=0;n<=steps;n++) {
                        double t=n/(double)steps,x=lerp(a.x,b.x,t),z=lerp(a.z,b.z,t);
                        double bx=(x-basin.centerX())/basin.radiusX(),bz=(z-basin.centerZ())/basin.radiusZ();
                        if(bx*bx+bz*bz<1.04 && Math.floor(lerp(a.water,b.water,t))!=Math.floor(basin.spillY())) return true;
                    }
                }
            }
            // Reject a connector whose wet core crosses an unrelated level before its intended join.
            var connector=lake.connector;
            double minX=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY;
            double minZ=Double.POSITIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;
            for(Point point:connector.points){minX=Math.min(minX,point.x);maxX=Math.max(maxX,point.x);
                minZ=Math.min(minZ,point.z);maxZ=Math.max(maxZ,point.z);}
            ArrayList<Segment> possibleCrossings=new ArrayList<>();
            for(Channel other:routed) {
                if(!other.wet||other.id==connector.id)continue;
                double margin=(generationVersion>=8&&other.from>=0?28:other.width)*1.08+connector.width+1;
                for(int j=1;j<other.points.size();j++) {
                    Point c=other.points.get(j-1),d=other.points.get(j);
                    if(maxX<Math.min(c.x,d.x)-margin||minX>Math.max(c.x,d.x)+margin
                            ||maxZ<Math.min(c.z,d.z)-margin||minZ>Math.max(c.z,d.z)+margin)continue;
                    possibleCrossings.add(new Segment(other,j));
                }
            }
            for(int i=1;i<connector.points.size();i++) {
                Point a=connector.points.get(i-1),b=connector.points.get(i);
                int steps=Math.max(1,(int)Math.ceil(Math.hypot(b.x-a.x,b.z-a.z)/2));
                for(int n=0;n<=steps;n++) {
                    double t=n/(double)steps,x=lerp(a.x,b.x,t),z=lerp(a.z,b.z,t),water=lerp(a.water,b.water,t);
                    for(Segment segment:possibleCrossings) {
                        Channel other=segment.channel;int j=segment.index;
                        Point c=other.points.get(j-1),d=other.points.get(j);
                        // Profile 8 widths stay below 26.88; connector widths are
                        // constant. An extra block keeps the broad phase conservative.
                        double widthBound=generationVersion>=8&&other.from>=0?28:other.width;
                        double margin=widthBound*1.08+connector.width+1;
                        if(x<Math.min(c.x,d.x)-margin||x>Math.max(c.x,d.x)+margin
                                ||z<Math.min(c.z,d.z)-margin||z>Math.max(c.z,d.z)+margin)continue;
                        double dx=d.x-c.x,dz=d.z-c.z,len2=dx*dx+dz*dz;
                        if(len2<1E-8)continue;
                        double p=Math.clamp(((x-c.x)*dx+(z-c.z)*dz)/len2,0,1);
                        double px=lerp(c.x,d.x,p),pz=lerp(c.z,d.z,p);
                        double distance=Math.hypot(x-px,z-pz),progress=(j-1+p)/(other.points.size()-1);
                        double actualWidth=generationVersion>=8?channelWidth(other,progress,curves):other.width;
                        actualWidth=channelCapWidth(other,progress,curves,x,z,px,pz,dx,dz,actualWidth);
                        if(distance<actualWidth*1.08+connector.width && Math.floor(water)!=Math.floor(lerp(c.water,d.water,p))) return true;
                    }
                }
            }
            return false;
        }
        /** Screen every potentially shared generated column, including off-center wet banks.
         * Centerline-only sampling can miss a lower channel grazing an irregular lake shore. */
        private boolean conflictingWetFootprint(RegionalLandforms.Basin basin,Channel channel,NaturalRiverNetwork curves) {
            double basinBound=Math.max(basin.radiusX(),basin.radiusZ())*2.1;
            double widthBound=channel.from>=0?28:channel.width;
            for(int i=1;i<channel.points.size();i++) {
                Point a=channel.points.get(i-1),b=channel.points.get(i);
                if(Math.floor(a.water)==Math.floor(basin.spillY())&&Math.floor(b.water)==Math.floor(basin.spillY()))continue;
                int minX=(int)Math.ceil(Math.max(Math.min(a.x,b.x)-widthBound,basin.centerX()-basinBound));
                int maxX=(int)Math.floor(Math.min(Math.max(a.x,b.x)+widthBound,basin.centerX()+basinBound));
                int minZ=(int)Math.ceil(Math.max(Math.min(a.z,b.z)-widthBound,basin.centerZ()-basinBound));
                int maxZ=(int)Math.floor(Math.min(Math.max(a.z,b.z)+widthBound,basin.centerZ()+basinBound));
                double dx=b.x-a.x,dz=b.z-a.z,len2=dx*dx+dz*dz;
                if(len2<1E-8)continue;
                for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++) {
                    double t=Math.clamp(((x-a.x)*dx+(z-a.z)*dz)/len2,0,1);
                    if(Math.floor(lerp(a.water,b.water,t))==Math.floor(basin.spillY()))continue;
                    double px=lerp(a.x,b.x,t),pz=lerp(a.z,b.z,t);
                    double progress=(i-1+t)/(channel.points.size()-1);
                    double width=channelWidth(channel,progress,curves);
                    width=channelCapWidth(channel,progress,curves,x,z,px,pz,dx,dz,width);
                    if((x-px)*(x-px)+(z-pz)*(z-pz)>width*width)continue;
                    double radius=landforms.basinRadius(basin,x,z);
                    if(radius*radius<1.04)return true;
                }
            }
            return false;
        }
        private Channel channel(WatershedPlan.Edge e,long id,double width,double depth,boolean wet,double side,boolean split,NaturalRiverNetwork curves){
            if(curves!=null){
                var path=curves.points(e.from());ArrayList<Point> points=new ArrayList<>();
                for(int i=0;i<path.size();i++){
                    var p=path.get(i);var a=path.get(Math.max(0,i-1));var b=path.get(Math.min(path.size()-1,i+1));
                    double dx=b.x()-a.x(),dz=b.z()-a.z(),len=Math.hypot(dx,dz),t=i/(double)(path.size()-1);
                    double offsetSide=generationVersion>=8&&split?Math.signum(side)*curves.width(e.from(),t)*1.35:side;
                    double bend=offsetSide*Math.pow(Math.sin(Math.PI*t),2);
                    points.add(new Point(p.x()-dz/len*bend,p.z()+dx/len*bend,p.water()));
                }
                return new Channel(id,e.from(),e.to(),List.copyOf(points),width,depth,wet,split);
            }
            ArrayList<Point> points=new ArrayList<>();double dx=e.x2()-e.x1(),dz=e.z2()-e.z1(),length=Math.hypot(dx,dz);
            for(int i=0;i<=8;i++){double t=i/8.;double bend=Math.sin(Math.PI*t)*(side+Math.sin(t*Math.PI*2+(id&255))*(split?1.2:Math.min(7,length*.045)));
                points.add(new Point(lerp(e.x1(),e.x2(),t)-dz/length*bend,lerp(e.z1(),e.z2(),t)+dx/length*bend,lerp(e.water1(),e.water2(),t)));}
            return new Channel(id,e.from(),e.to(),List.copyOf(points),width,depth,wet,split);
        }
        private double channelWidth(Channel channel,double progress,NaturalRiverNetwork curves) {
            return channel.from>=0
                    ?curves.width(channel.from,progress)*(channel.split?1-.52*Math.pow(Math.sin(Math.PI*progress),2):1)
                    :channel.width;
        }
        private double channelCapWidth(Channel channel,double progress,NaturalRiverNetwork curves,
                double x,double z,double px,double pz,double dx,double dz,double width) {
            return generationVersion>=16&&channel.wet&&channel.from>=0
                    ?curves.sourceCapWidth(channel.from,progress,x,z,px,pz,dx,dz,width):width;
        }
        WatershedSample sample(int x,int z,double original,double cohesion){
            for(Lake lake:lakes){var b=lake.basin;double dx=(x-b.centerX())/b.radiusX(),dz=(z-b.centerZ())/b.radiusZ();double q=generationVersion>=8?Math.pow(landforms.basinRadius(b,x,z),2):dx*dx+dz*dz;
                if(q<1){double water=b.spillY();double bed=generationVersion>=8?landforms.basinBed(b,x,z):lerp(b.floorY(),water-2,smooth(.25,1,q));
                    return new WatershedSample(true,lake.connector.id,0,Math.min(b.radiusX(),b.radiusZ()),0,1,true,false,true,Math.min(original,bed),water,original,false,false,0);}}
            for (Channel island : islands) {
                if(natural){
                    Point start=island.points.getFirst();
                    if(Math.abs(x-start.x)>350||Math.abs(z-start.z)>350)continue;
                    for(int i=1;i<island.points.size();i++){
                        Point a=island.points.get(i-1),b=island.points.get(i);
                        double dx=b.x-a.x,dz=b.z-a.z,len2=dx*dx+dz*dz;
                        if(len2<1E-8)continue;
                        double t=Math.clamp(((x-a.x)*dx+(z-a.z)*dz)/len2,0,1);
                        double progress=(i-1+t)/(island.points.size()-1);
                        if(progress<.22||progress>.78)continue;
                        double distance=Math.hypot(x-lerp(a.x,b.x,t),z-lerp(a.z,b.z,t)),water=lerp(a.water,b.water,t);
                        double islandWidth=generationVersion>=8?projection.width(island.from,progress)*.42:island.width;
                        if(distance<islandWidth*Math.sin(Math.PI*progress)&&original>water+5)
                            return new WatershedSample(true,island.id,distance,islandWidth,dx/Math.sqrt(len2),dz/Math.sqrt(len2),false,false,false,original,water,original,true,false,progress);
                    }
                    continue;
                }
                Point a=island.points.getFirst(), b=island.points.getLast();
                double dx=b.x-a.x,dz=b.z-a.z,len2=dx*dx+dz*dz;
                double t=((x-a.x)*dx+(z-a.z)*dz)/len2;
                if(t<.22||t>.78) continue;
                double d=Math.abs(dx*(z-a.z)-dz*(x-a.x))/Math.sqrt(len2);
                double water=lerp(a.water,b.water,t);
                if(d<island.width*Math.sin(Math.PI*t)&&original>water+5)
                    return new WatershedSample(true,island.id,d,island.width,dx/Math.sqrt(len2),dz/Math.sqrt(len2),false,false,false,original,water,original,true,false,t);
            }
            List<RuntimeSegment> nearby=bins.get(pack(Math.floorDiv(x,BIN),Math.floorDiv(z,BIN)));
            if(nearby==null)return WatershedSample.absent(original);
            Channel selected=null;double score=Double.POSITIVE_INFINITY;
            int selectedSegment=0;double selectedT=0,selectedWidth=0,selectedDistance=0,selectedX=0,selectedZ=0;
            for(RuntimeSegment segment:nearby){
                Channel c=segment.channel;int i=segment.index;
                Point a=c.points.get(i-1),b=c.points.get(i);
                if(natural){
                    double bound=c.wet?NaturalValley.radius(generationVersion>=8?28:19):NaturalValley.radius(6);
                    if(x<Math.min(a.x,b.x)-bound||x>Math.max(a.x,b.x)+bound
                            ||z<Math.min(a.z,b.z)-bound||z>Math.max(a.z,b.z)+bound)continue;
                }
                if(generationVersion>=16&&natural
                        &&(x<segment.minimumX||x>segment.maximumX||z<segment.minimumZ||z>segment.maximumZ))continue;
                double dx=b.x-a.x,dz=b.z-a.z,len2=dx*dx+dz*dz;
                if(len2<1E-8)continue;double t=Math.clamp(((x-a.x)*dx+(z-a.z)*dz)/len2,0,1);
                double px=lerp(a.x,b.x,t),pz=lerp(a.z,b.z,t);
                // Reject distant projections before evaluating the varying width
                // and source-cap curves. Survivors keep the exact calculations.
                if(generationVersion>=16&&natural
                        &&Math.max(Math.abs(x-px),Math.abs(z-pz))>segment.maximumRadius)continue;
                double progress=(i-1+t)/(c.points.size()-1);
                double width=generationVersion>=16&&t==0.0?segment.startWidth
                        :generationVersion>=16&&t==1.0?segment.endWidth
                        :generationVersion>=8?channelWidth(c,progress,projection)
                        :natural&&c.from>=0?projection.width(c.from,progress)*(c.split?1-.52*Math.pow(Math.sin(Math.PI*progress),2):1)*
                        (1+.035*Math.sin(px*.017+pz*.011)):c.width*(.96+.08*Math.sin((i-1+t)*.73+(c.id&31)));
                width=channelCapWidth(c,progress,projection,x,z,px,pz,dx,dz,width);
                double radius=natural?NaturalValley.radius(width):width*1.8+8;
                double distance=Math.hypot(x-px,z-pz);
                if(distance>radius)continue;
                // Wet cores take precedence over dry tributaries at actual confluences.
                double candidate=distance/width-(c.wet && distance <= width ? 2 : 0);
                if(candidate>=score)continue;
                selected=c;score=candidate;selectedSegment=i;selectedT=t;
                selectedWidth=width;selectedDistance=distance;selectedX=px;selectedZ=pz;
            }
            if(selected==null)return WatershedSample.absent(original);
            // Scoring depends only on the projected footprint. Compute the selected
            // bed once, after the same ordered strict comparisons have chosen it.
            Channel c=selected;int i=selectedSegment;double t=selectedT,width=selectedWidth,distance=selectedDistance;
            Point a=c.points.get(i-1),b=c.points.get(i);
            double dx=b.x-a.x,dz=b.z-a.z,len2=dx*dx+dz*dz;
            double progress=(i-1+t)/(c.points.size()-1);
            double water=lerp(a.water,b.water,t),length=Math.sqrt(len2);
            double lateral=ChannelDistance.signed(x,z,a.x,a.z,b.x,b.z);
            double baseDepth=c.depth;
            if(generationVersion>=8&&c.wet&&c.from>=0) {
                // Keep the average incision budget while varying the bed and retaining more routes.
                double fromDepth=2.4+Math.min(5,Math.sqrt(physicalArea(plan,plan.area(c.from)))*.12);
                double toDepth=2.4+Math.min(5,Math.sqrt(physicalArea(plan,plan.area(c.to)))*.12);
                baseDepth=lerp(fromDepth,toDepth,smooth(0,1,progress));
            }
            double selectedDepth=baseDepth*(generationVersion>=5&&c.from>=0?projection.depthScale(c.from,progress):1);
            boolean hydraulicRefinement=generationVersion>=6&&c.wet&&c.from>=0&&!c.split
                    &&!plungeReaches.contains(c.id);
            // The ordinary natural reach overwrites this provisional bed below.
            // Avoid its pure coordinate-field work until the actual bend is known.
            double selectedBed=c.wet?(generationVersion>=8?(hydraulicRefinement?original:NaturalValley.naturalBed(lateral,width,selectedDepth,water,original,seed,selectedX,selectedZ,0,cohesion))
                        :natural?NaturalValley.bed(distance,width,selectedDepth,water,original):water-selectedDepth)
                        :original-c.depth*(1-smooth(0,width,distance));
            if(!natural&&c.wet&&distance>width)selectedBed=water-1;
            WatershedSample best=new WatershedSample(true,c.id,lateral,width,dx/length,dz/length,c.wet,!c.wet,false,selectedBed,water,original,false,
                        c.wet&&a.water-b.water>2,progress);
            if(selected.wet && selected.from>=0 && !selected.split) {
                double bed=best.bedY();boolean hydraulicShoal=false;
                HydraulicChannelProfile.Context context=null;
                if(hydraulicRefinement) {
                    context=hydraulics.get(best.reachId()).at(selectedSegment,selectedT,best.width());
                    double refined=HydraulicChannelProfile.bed(best.distance(),best.width(),selectedDepth,
                            best.waterY(),original,context.curvature(),context.grade(),cohesion,best.progress(),
                            context.distanceToEnd(),context.length());
                    if(generationVersion>=8) {
                        // The hydraulic model contributes bars/thalweg changes; the seeded bed
                        // and independently weathered banks remain the authoritative base.
                        bed=NaturalValley.naturalBed(best.distance(),best.width(),selectedDepth,best.waterY(),
                                original,seed,selectedX,selectedZ,context.curvature(),cohesion);
                        if(Math.abs(best.distance())<best.width()) {
                            double base=HydraulicChannelProfile.baseBed(best.distance(),best.width(),selectedDepth,best.waterY(),original);
                            double revised=Math.min(original,Math.min(best.waterY()-.8,bed+refined-base));
                            hydraulicShoal=revised>bed+.03;bed=revised;
                        }
                    } else {hydraulicShoal=refined>bed+.03;bed=refined;}
                }
                if(generationVersion>=10&&Math.abs(best.distance())<best.width()){
                    if(context==null)context=hydraulics.get(best.reachId()).at(selectedSegment,selectedT,best.width());
                    double varied=HydraulicChannelProfile.landscapeBed(best.distance(),best.width(),selectedDepth,
                            best.waterY(),original,bed,context.grade(),best.progress(),context.distanceToEnd(),
                            context.length(),best.reachId());
                    hydraulicShoal|=varied>bed+.03;bed=varied;
                }
                RiverBankContext bankContext=generationVersion>=12
                        ?projection.bankContext(selected.from,selectedSegment,selectedT):null;
                if(bankContext!=null) {
                    bankContext=bankContext.withHydraulics(cohesion,context==null?0:context.curvature());
                    // Only exposed banks change; submerged hydraulics and junction collars remain authoritative.
                    if(bankContext.gorgeWeight()<1&&Math.abs(best.distance())>best.width())bed=NaturalValley.contextualBed(best.distance(),best.width(),
                            selectedDepth,best.waterY(),original,seed,selectedX,selectedZ,bankContext,generationVersion);
                }
                var morphology=WatershedMorphology.shape(best.reachId(),best.progress(),best.distance(),best.width(),
                        original,best.waterY(),bed,false,false,plungeReaches.contains(best.reachId()),
                        selected.points.getFirst().water-selected.points.getLast().water<=1.);
                return new WatershedSample(best.valid(),best.reachId(),best.distance(),best.width(),best.tangentX(),best.tangentZ(),
                        best.wet(),best.dry(),best.basin(),morphology.bedY(),best.waterY(),original,best.island(),best.drop(),best.progress(),
                        morphology.shoal()||hydraulicShoal,morphology.dryCrest(),morphology.plungePool(),morphology.gorge(),bankContext);
            }
            return best;
        }
        synchronized SearchSnapshot searchSnapshot() {
            if (searchSnapshot == null) {
                List<SearchReach> accepted = channels.stream().map(channel -> new SearchReach(
                        channel.id, channel.wet, channel.width,
                        channel.points.stream().map(point -> new SearchPoint(point.x, point.z, point.water)).toList())).toList();
                Set<Long> coastal = new HashSet<>();
                for (var lake : lakes) if (lake.basin.kind() == RegionalLandforms.Kind.COAST)
                    coastal.add(lake.connector.id);
                searchSnapshot = new SearchSnapshot(cx, cz, accepted, lakes.stream().map(Lake::basin).toList(), coastal);
            }
            return searchSnapshot;
        }
        List<DirectedReach> topology(){
            Map<Integer,List<Channel>> byStart=new HashMap<>();for(Channel c:channels)if(c.from>=0)byStart.computeIfAbsent(c.from,ignored->new ArrayList<>()).add(c);
            ArrayList<DirectedReach> result=new ArrayList<>();
            for(Channel c:channels){List<Long> downstream=byStart.getOrDefault(c.to,List.of()).stream().map(Channel::id).toList();
                List<ReachPoint> points=c.points.stream().map(p->new ReachPoint(p.x,p.z)).toList();
                result.add(new DirectedReach(c.id,downstream.isEmpty()?0:downstream.getFirst(),!downstream.isEmpty(),0,points,downstream));}
            return List.copyOf(result);
        }
    }
    private List<RegionalLandforms.Basin> basinCandidates(WatershedPlan p){
        Map<Long,RegionalLandforms.Basin> selected=new HashMap<>();
        for(int z=p.originZ();z<=p.originZ()+(p.size()-1)*p.spacing();z+=640)
            for(int x=p.originX();x<=p.originX()+(p.size()-1)*p.spacing();x+=640)
                for(var b:landforms.basinsNear(x,z))selected.putIfAbsent(b.id(),b);
        return selected.values().stream().sorted(Comparator.comparingLong(RegionalLandforms.Basin::id)).toList();
    }
    private int nearestOutlet(WatershedPlan p,RegionalLandforms.Basin basin){
        int selected=-1;double best=Double.POSITIVE_INFINITY;
        int gx=Math.floorDiv((int)basin.outletX()-p.originX(),p.spacing()),gz=Math.floorDiv((int)basin.outletZ()-p.originZ(),p.spacing());
        int search=generationVersion>=7?(int)Math.ceil(256./p.spacing()):2;
        for(int dz=-search;dz<=search;dz++)for(int dx=-search;dx<=search;dx++){
            int x=gx+dx,z=gz+dz;if(x<0||z<0||x>=p.size()||z>=p.size())continue;int i=z*p.size()+x;
            if(!p.owned(i)||!p.reachesOcean(i)||p.water(i)>basin.spillY()+.01)continue;
            double basinX=(p.x(i)-basin.centerX())/basin.radiusX(), basinZ=(p.z(i)-basin.centerZ())/basin.radiusZ();
            if((generationVersion>=8?Math.pow(landforms.basinRadius(basin,p.x(i),p.z(i)),2):basinX*basinX+basinZ*basinZ)<=1.05)continue;
            // The outlet must continue away from its bowl; never connect back through the lake.
            double outwardX=(basin.outletX()-basin.centerX())/basin.radiusX(),outwardZ=(basin.outletZ()-basin.centerZ())/basin.radiusZ();
            double onwardX=(p.x(i)-basin.outletX())/basin.radiusX(),onwardZ=(p.z(i)-basin.outletZ())/basin.radiusZ();
            if(generationVersion>=8) {
                outwardX=basin.outletX()-basin.centerX();outwardZ=basin.outletZ()-basin.centerZ();
                onwardX=p.x(i)-basin.outletX();onwardZ=p.z(i)-basin.outletZ();
            }
            if(outwardX*onwardX+outwardZ*onwardZ<0)continue;
            double distance=Math.hypot(p.x(i)-basin.outletX(),p.z(i)-basin.outletZ());
            if(distance<best&&distance<=256){best=distance;selected=i;}
        }return selected;
    }
    private long reachId(int cx,int cz,int node,int branch){return mix(((long)seed<<32)^pack(cx,cz)^((long)node<<2)^branch);}
    private static long pack(int x,int z){return ((long)x<<32)^(z&0xFFFFFFFFL);}
    private static long mix(long v){v=(v^(v>>>30))*0xBF58476D1CE4E5B9L;v=(v^(v>>>27))*0x94D049BB133111EBL;return v^(v>>>31);}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private static double smooth(double a,double b,double x){double t=Math.clamp((x-a)/(b-a),0,1);return t*t*(3-2*t);}
}

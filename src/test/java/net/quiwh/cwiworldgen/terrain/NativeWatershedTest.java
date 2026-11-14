package net.quiwh.cwiworldgen.terrain;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

/** Positive fixtures from the real uncarved native terrain, then real block generation. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeWatershedTest {
    @GameTest(template="empty", templateNamespace="cwi_worldgen")
    public static void finiteChannelEndpointCapsCannotTurnDistantColumnsIntoWetCores(GameTestHelper helper) {
        helper.assertTrue(net.quiwh.cwiworldgen.hydrology.watershed.ChannelDistance.signed(-4,0,0,0,10,0)==4
                && net.quiwh.cwiworldgen.hydrology.watershed.ChannelDistance.signed(14,0,0,0,10,0)==4,
                "Clamped channel endpoint discarded longitudinal distance");
        helper.assertTrue(net.quiwh.cwiworldgen.hydrology.watershed.ChannelDistance.signed(13,-4,0,0,10,0)==-5,
                "Endpoint cap lost signed Euclidean distance");
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace="cwi_worldgen", timeoutTicks=1200)
    public static void nativeWatershedHasConnectedWetDrySplitAndBasinGeometry(GameTestHelper helper) {
        try (var terrain=new ReferenceTerrain(42,2)) {
            var graph=terrain.hydrologySnapshot(0,0);
            var metrics=terrain.watershedDiagnostics(0,0);
            helper.assertTrue(metrics.oceanSeeds()>0 && metrics.terminalComponents()==0
                    && metrics.clippedLandNodes()==0, "Fixture has no real ocean outlet or clips land");
            helper.assertTrue(metrics.wetReaches()>0 && metrics.dryReaches()>0 && metrics.islandCorridors()>0
                    && metrics.mountainLakes()>0 && metrics.coastalLagoons()>0, "Native positive feature is absent: "+metrics);
            var incoming=new HashMap<Long,Integer>();
            int splits=0;
            for(var reach:graph.reaches()) {
                if(reach.downstreamIds().size()>1)splits++;
                for(long target:reach.downstreamIds())incoming.merge(target,1,Integer::sum);
            }
            helper.assertTrue(splits>0 && incoming.values().stream().anyMatch(n->n>1),
                    "Detached topology lacks actual split/rejoin or confluence edges");
            var island=terrain.watershedSample(2132,-42);
            var filtered=terrain.sample(2132,-42);
            helper.assertTrue(island.island() && island.originalGroundY()>island.waterY()+5,
                    "Fixture is no longer a tall preserved host-rock island");
            helper.assertTrue(filtered.cwiIsland && Math.abs(filtered.height*256-island.originalGroundY())<.001,
                    "Final tile filters flattened the original island");
            for(int[] p:new int[][]{{2126,-48},{2138,-36}}) {
                var column=ReferenceRiverColumn.sample(terrain,p[0],p[1]);
                helper.assertTrue(column.hasWater() && column.waterY()-column.solidTopY()>=2,
                        "Split flank no longer contains a projected wet interval: "+column);
            }
            var before=terrain.watershedSample(2132,-42);
            terrain.clearWatershedPlans();
            helper.assertTrue(before.equals(terrain.watershedSample(2132,-42)), "Plan eviction changed seeded geometry");
            System.out.println("CWI_WATERSHED_NATIVE="+metrics+",reaches:"+graph.reaches().size());
        }
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace="cwi_worldgen", timeoutTicks=1200)
    public static void realWetPolylinesRemainConnectedAndNeverStepUpThroughBasins(GameTestHelper helper) {
        for(long seed:new long[]{0,42}) try(var terrain=new ReferenceTerrain(seed,2)) {
            int checked=0;
            for(var reach:terrain.hydrologySnapshot(0,0).reaches()) {
                Boolean wet=null;
                for(var point:reach.points()) {
                    var sample=terrain.watershedSample((int)Math.round(point.x()),(int)Math.round(point.z()));
                    if(sample.reachId()==reach.id()&&!sample.basin()&&!sample.island()) {wet=sample.wet();break;}
                }
                if(!Boolean.TRUE.equals(wet))continue;
                double previous=Double.POSITIVE_INFINITY;
                for(int p=1;p<reach.points().size();p++) {
                    var a=reach.points().get(p-1);var b=reach.points().get(p);
                    int count=(int)Math.ceil(Math.hypot(b.x()-a.x(),b.z()-a.z())/2);
                    for(int i=0;i<=count;i++) {
                        int x=(int)Math.round(a.x()+(b.x()-a.x())*i/count),z=(int)Math.round(a.z()+(b.z()-a.z())*i/count);
                        var sample=terrain.watershedSample(x,z);
                        helper.assertTrue(sample.wet()&&!sample.island()&&Math.abs(sample.distance())<=sample.width(),
                                "Actual wet polyline contains a gap at "+x+","+z);
                        helper.assertTrue(Math.floor(sample.waterY())<=Math.floor(previous),
                                "Actual water steps uphill through a basin at "+x+","+z);
                        previous=sample.waterY();checked++;
                    }
                }
            }
            helper.assertTrue(checked>60000,"No substantive native polyline coverage");
            System.out.println("CWI_WATERSHED_CONTINUITY=seed:"+seed+",wet_samples:"+checked+",gaps:0,upward_water_block_steps:0");
        }
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace="cwi_worldgen", timeoutTicks=6000)
    public static void actualNativeBlocksKeepTallIslandAndBothWetFlanksAfterCarving(GameTestHelper helper) {
        ServerLevel level=helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:watershed_wasteland_test")));
        var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        var state=RandomState.create(generator.generatorSettings().value(), level.registryAccess().lookupOrThrow(Registries.NOISE),42L);
        var terrain=((TerrainRandomState)(Object)state).cwiWorldgen$terrain();
        helper.assertTrue(terrain!=null && terrain.generationVersion()==2,"Physical fixture did not bind generation2");
        try {
            var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
            Map<Long,ProtoChunk> owned=new HashMap<>();
            for(ChunkPos position:new ChunkPos[]{new ChunkPos(132,-3),new ChunkPos(133,-3)}) {
                var chunk=new ProtoChunk(position,UpgradeData.EMPTY,level,registry,null);
                complete(level,generator.createBiomes(state,Blender.empty(),level.structureManager(),chunk));
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                complete(level,generator.fillFromNoise(Blender.empty(),state,level.structureManager(),chunk));
                chunk.setPersistedStatus(ChunkStatus.NOISE);
                owned.put(position.toLong(),chunk);
            }
            for(var chunk:owned.values()) {
                var region=new OwnedRegion(level,chunk,owned,generator,state);
                var biomes=new BiomeManager(region,BiomeManager.obfuscateSeed(42L));
                generator.buildSurface(chunk,new WorldGenerationContext(generator,level),state,
                        level.structureManager(),biomes,registry,Blender.empty());
                chunk.setPersistedStatus(ChunkStatus.SURFACE);
                generator.applyCarvers(region,42L,state,biomes,level.structureManager(),chunk,GenerationStep.Carving.AIR);
                chunk.setPersistedStatus(ChunkStatus.CARVERS);
                helper.assertTrue(ReferenceRiverGasket.place(chunk,state,42L)==0,"Post-carver water gasket was not idempotent");
                for(var neighbor:region.neighbors.values())for(var section:neighbor.getSections())
                    helper.assertTrue(section.hasOnlyAir(),"Watershed wrote into an unowned neighboring chunk");
            }
            for(int[] p:new int[][]{{2126,-48},{2138,-36}}) {
                var column=ReferenceRiverColumn.sample(terrain,p[0],p[1]);
                var chunk=owned.get(ChunkPos.asLong(p[0]>>4,p[1]>>4));
                var top=new BlockPos(p[0],column.waterY(),p[1]);
                helper.assertTrue(chunk.getBlockState(top).is(Blocks.WATER)
                        && chunk.getBlockState(top.below()).is(Blocks.WATER),"Physical split flank lost its water at "+top);
            }
            var island=terrain.watershedSample(2132,-42);
            var crest=new BlockPos(2132,(int)Math.floor(island.originalGroundY()),-42);
            var chunk=owned.get(ChunkPos.asLong(2132>>4,-42>>4));
            // Check preserved host volume above water rather than a low sediment bar.
            for(int y=(int)Math.ceil(island.waterY())+1;y<=crest.getY()-2;y++) {
                var block=chunk.getBlockState(new BlockPos(2132,y,-42));
                helper.assertTrue(!block.isAir() && block.getFluidState().isEmpty(),"Tall host-rock island was removed at Y="+y);
            }
            System.out.println("CWI_WATERSHED_BLOCKS=seed:42,island:2132/-42,wet_flanks:2126/-48;2138/-36,unowned_neighbor_writes:0");
        } finally { terrain.close(); }
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace="cwi_worldgen", timeoutTicks=6000)
    public static void actualNativeMountainLakeAndCoastalLagoonContainWaterAfterCarving(GameTestHelper helper) {
        ServerLevel level=helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:watershed_wasteland_test")));
        var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        var state=RandomState.create(generator.generatorSettings().value(),level.registryAccess().lookupOrThrow(Registries.NOISE),42L);
        var terrain=((TerrainRandomState)(Object)state).cwiWorldgen$terrain();
        helper.assertTrue(terrain!=null && terrain.generationVersion()==2,"Physical fixture did not bind generation2");
        try {
            var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
            Map<Long,ProtoChunk> owned=new HashMap<>();
            for(int[] point:new int[][]{{3043,900},{114,251}}) {
                var position=new ChunkPos(point[0]>>4,point[1]>>4);
                var chunk=new ProtoChunk(position,UpgradeData.EMPTY,level,registry,null);
                complete(level,generator.createBiomes(state,Blender.empty(),level.structureManager(),chunk));
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                complete(level,generator.fillFromNoise(Blender.empty(),state,level.structureManager(),chunk));
                chunk.setPersistedStatus(ChunkStatus.NOISE);owned.put(position.toLong(),chunk);
            }
            for(var chunk:owned.values()) {
                var region=new OwnedRegion(level,chunk,owned,generator,state);
                var biomes=new BiomeManager(region,BiomeManager.obfuscateSeed(42L));
                generator.buildSurface(chunk,new WorldGenerationContext(generator,level),state,
                        level.structureManager(),biomes,registry,Blender.empty());
                chunk.setPersistedStatus(ChunkStatus.SURFACE);
                generator.applyCarvers(region,42L,state,biomes,level.structureManager(),chunk,GenerationStep.Carving.AIR);
                chunk.setPersistedStatus(ChunkStatus.CARVERS);
                for(var neighbor:region.neighbors.values())for(var section:neighbor.getSections())
                    helper.assertTrue(section.hasOnlyAir(),"Basin generation wrote into an unowned neighbor");
            }
            for(int[] point:new int[][]{{3043,900},{114,251}}) {
                var planned=terrain.watershedSample(point[0],point[1]);
                var column=ReferenceRiverColumn.sample(terrain,point[0],point[1]);
                helper.assertTrue(planned.basin()&&column.hasWater(),"Accepted basin fixture no longer has planned water");
                var chunk=owned.get(ChunkPos.asLong(point[0]>>4,point[1]>>4));
                var top=new BlockPos(point[0],column.waterY(),point[1]);
                helper.assertTrue(chunk.getBlockState(top).is(Blocks.WATER)&&chunk.getBlockState(top.below()).is(Blocks.WATER),
                        "Actual basin lost its wet interval at "+top);
                helper.assertTrue(chunk.getBlockState(top.above()).isAir(),"Actual basin retained a solid roof at "+top);
                System.out.println("CWI_WATERSHED_BASIN_BLOCKS=x:"+point[0]+",z:"+point[1]+",water:"+column.waterY()+",bed:"+column.groundY());
            }
        } finally {terrain.close();}
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace="cwi_worldgen", timeoutTicks=2400)
    public static void completedV2TerrainRetainsLowShoalsDropFootPoolsAndUnequalGorgeBanks(GameTestHelper helper) {
        try(var terrain=new ReferenceTerrain(42,2)) {
            var bar=terrain.watershedSample(4965,6332);
            var crest=terrain.sample(4965,6332);
            helper.assertTrue(bar.shoal()&&bar.dryCrest()&&!bar.drop()&&crest.cwiShoal
                    &&Math.abs(crest.height*256-bar.bedY())<.001&&Math.floor(bar.bedY())==Math.floor(bar.waterY()),
                    "Actual stable v2 reach lost its one-block-capped dry bar");
            var rim=terrain.watershedSample(4969,6335);
            helper.assertTrue(rim.shoal()&&!rim.dryCrest()&&rim.bedY()<Math.floor(rim.waterY())-1,
                    "Actual bar lacks a submerged shoal perimeter");
            for(int[] p:new int[][]{{4974,6339},{4956,6325}}) {
                var column=ReferenceRiverColumn.sample(terrain,p[0],p[1]);
                helper.assertTrue(column.hasWater()&&column.waterY()-column.solidTopY()>=2,
                        "Actual low bar blocked one of its wet flanks");
            }
            var pool=terrain.watershedSample(5421,-1295);
            var outside=terrain.watershedSample(5426,-1300);
            helper.assertTrue(pool.plungePool()&&!pool.gorge()&&pool.waterY()-pool.bedY()>5
                    &&pool.bedY()<outside.bedY()-1&&terrain.sample(5421,-1295).height*256<=pool.bedY()+.001,
                    "Actual downstream drop foot lost its extra pool depth");
            var graph=terrain.hydrologySnapshot(0,0);
            var current=graph.reaches().stream().filter(r->r.id()==pool.reachId()).findFirst().orElseThrow();
            var first=current.points().getFirst();var last=current.points().getLast();
            double downstreamDrop=terrain.watershedSample((int)first.x(),(int)first.z()).waterY()
                    -terrain.watershedSample((int)last.x(),(int)last.z()).waterY();
            boolean steepIncoming=graph.reaches().stream().filter(r->r.downstreamIds().contains(current.id())).anyMatch(r->{
                var start=r.points().getFirst();var end=r.points().getLast();
                return terrain.watershedSample((int)start.x(),(int)start.z()).waterY()
                        -terrain.watershedSample((int)end.x(),(int)end.z()).waterY()>=4;
            });
            helper.assertTrue(downstreamDrop<=1.001&&steepIncoming,"Pool was not attached to an actual steep-to-flat DAG junction");
            var gorge=terrain.watershedSample(3095,3331);
            var opposite=terrain.watershedSample(3105,3344);
            helper.assertTrue(gorge.gorge()&&opposite.gorge()&&gorge.reachId()==opposite.reachId()
                    &&gorge.originalGroundY()-gorge.waterY()>40
                    &&opposite.bedY()-gorge.bedY()>.8&&terrain.sample(3095,3331).cwiGorge,
                    "Actual high-relief reach lost asymmetric gorge shaping");
            helper.assertTrue(!terrain.watershedSample(2132,-42).shoal()&&terrain.sample(2132,-42).cwiIsland,
                    "Low-bar policy replaced the preserved tall island");
            System.out.println("CWI_WATERSHED_MORPHOLOGY=seed:42,bar:4965/6332,pool:5421/-1295,gorge:3095/3331,stable_bar:true,steep_to_flat_pool:true");
        }
        helper.succeed();
    }

    @GameTest(template="empty", templateNamespace="cwi_worldgen", timeoutTicks=6000)
    public static void actualV2BlocksKeepDryBarTwoWetFlanksAndDeeperDropFootWater(GameTestHelper helper) {
        ServerLevel level=helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:watershed_wasteland_test")));
        var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        var state=RandomState.create(generator.generatorSettings().value(),level.registryAccess().lookupOrThrow(Registries.NOISE),42L);
        var terrain=((TerrainRandomState)(Object)state).cwiWorldgen$terrain();
        helper.assertTrue(terrain!=null && terrain.generationVersion()==2,"Physical fixture did not bind generation2");
        try {
            var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
            Map<Long,ProtoChunk> owned=new HashMap<>();
            for(ChunkPos position:new ChunkPos[]{new ChunkPos(310,395),new ChunkPos(310,396),new ChunkPos(309,395),new ChunkPos(338,-81)}) {
                var chunk=new ProtoChunk(position,UpgradeData.EMPTY,level,registry,null);
                complete(level,generator.createBiomes(state,Blender.empty(),level.structureManager(),chunk));
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                complete(level,generator.fillFromNoise(Blender.empty(),state,level.structureManager(),chunk));
                chunk.setPersistedStatus(ChunkStatus.NOISE);owned.put(position.toLong(),chunk);
            }
            for(var chunk:owned.values()) {
                var region=new OwnedRegion(level,chunk,owned,generator,state);
                var biomes=new BiomeManager(region,BiomeManager.obfuscateSeed(42L));
                generator.buildSurface(chunk,new WorldGenerationContext(generator,level),state,
                        level.structureManager(),biomes,registry,Blender.empty());
                chunk.setPersistedStatus(ChunkStatus.SURFACE);
                generator.applyCarvers(region,42L,state,biomes,level.structureManager(),chunk,GenerationStep.Carving.AIR);
                chunk.setPersistedStatus(ChunkStatus.CARVERS);
                for(var neighbor:region.neighbors.values())for(var section:neighbor.getSections())
                    helper.assertTrue(section.hasOnlyAir(),"Morphology wrote outside the owning chunks");
            }
            var bar=terrain.watershedSample(4965,6332);
            var barChunk=owned.get(ChunkPos.asLong(310,395));
            var crest=new BlockPos(4965,(int)Math.floor(bar.waterY()),6332);
            var crestState=barChunk.getBlockState(crest);
            helper.assertTrue(!crestState.isAir()&&crestState.getFluidState().isEmpty()
                    &&barChunk.getBlockState(crest.above()).isAir(),"Actual generated low bar lost its dry one-block crest");
            for(int[] p:new int[][]{{4974,6339},{4956,6325},{5421,-1295}}) {
                var column=ReferenceRiverColumn.sample(terrain,p[0],p[1]);
                var chunk=owned.get(ChunkPos.asLong(p[0]>>4,p[1]>>4));
                var top=new BlockPos(p[0],column.waterY(),p[1]);
                helper.assertTrue(chunk.getBlockState(top).is(Blocks.WATER)&&chunk.getBlockState(top.below()).is(Blocks.WATER),
                        "Actual generated morphology lost its wet interval at "+top);
                if(p[0]==5421)helper.assertTrue(chunk.getBlockState(top.below(5)).is(Blocks.WATER),
                        "Actual drop-foot pool lacks its extra water depth");
            }
            System.out.println("CWI_WATERSHED_MORPHOLOGY_BLOCKS=seed:42,dry_crests:1,wet_flanks:2,deep_pools:1,unowned_neighbor_writes:0");
        } finally {terrain.close();}
        helper.succeed();
    }

    private static void complete(ServerLevel level,CompletableFuture<?> future) {
        level.getServer().managedBlock(()->{level.getChunkSource().pollTask();return future.isDone();});
        future.join();
    }
    private static final class OwnedRegion extends WorldGenRegion {
        private final ServerLevel level;
        private final Map<Long,ProtoChunk> owned;
        private final NoiseBasedChunkGenerator generator;
        private final RandomState state;
        private final Map<Long,ProtoChunk> neighbors=new HashMap<>();
        OwnedRegion(ServerLevel level,ChunkAccess center,Map<Long,ProtoChunk> owned,NoiseBasedChunkGenerator generator,RandomState state) {
            super(level,null,null,center);this.level=level;this.owned=owned;this.generator=generator;this.state=state;
        }
        @Override public long getSeed(){return 42L;}
        @Override public ChunkAccess getChunk(int x,int z){return getChunk(x,z,ChunkStatus.EMPTY,true);}
        @Override public ChunkAccess getChunk(int x,int z,ChunkStatus status,boolean required) {
            long key=ChunkPos.asLong(x,z);var existing=owned.get(key);if(existing!=null)return existing;
            if(status.isAfter(ChunkStatus.EMPTY))throw new AssertionError("Watershed requested a generated neighbor");
            return neighbors.computeIfAbsent(key,ignored->new ProtoChunk(new ChunkPos(x,z),UpgradeData.EMPTY,level,
                    level.registryAccess().registryOrThrow(Registries.BIOME),null));
        }
        @Override public Holder<Biome> getNoiseBiome(int x,int y,int z) {
            return generator.getBiomeSource().getNoiseBiome(x,y,z,state.sampler());
        }
    }
}

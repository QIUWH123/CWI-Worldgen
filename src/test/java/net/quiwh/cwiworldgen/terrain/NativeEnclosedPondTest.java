package net.quiwh.cwiworldgen.terrain;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.quiwh.cwiworldgen.structure.TerrainCheckedJigsawStructure;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.hydrology.watershed.EnclosedPondPlanner;
import net.quiwh.cwiworldgen.hydrology.watershed.WatershedHydrology;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.WatershedTerrain;

@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeEnclosedPondTest {
    @GameTest(template="empty",templateNamespace="cwi_worldgen")
    public static void pondFootprintFindsSmallWaterBetweenEightBlockSurveyPoints(GameTestHelper helper){
        int cx=-2,cz=-1;while(!EnclosedPondPlanner.eligible(42,cx,cz))cx--;
        int[] center=EnclosedPondPlanner.candidateCenter(42,cx,cz,0);
        var planner=new EnclosedPondPlanner(42,(x,z)->new EnclosedPondPlanner.Ground(
                Math.abs(x-center[0])<=2&&Math.abs(z-center[1])<=2?90:96,true));
        var box=new BoundingBox(center[0]-4,0,center[1]-4,center[0]+4,0,center[1]+4);
        helper.assertTrue(TerrainCheckedJigsawStructure.acceptsTerrain(box,
                (x,z)->planner.column(x,z).wet()?95:96,
                (x,z)->planner.column(x,z).wet()?90:96,0,0),
                "25-column pond fixture did not fall between all eight-block survey points");
        helper.assertTrue(planner.rectangleIntersectsWet(box.minX(),box.minZ(),box.maxX(),box.maxZ()),
                "Profile12 full footprint query missed pond between survey points");
        helper.assertTrue(!planner.rectangleIntersectsWet(center[0]+3,center[1]-2,center[0]+3,center[1]+2),
                "Dry pond rim was rejected as wet footprint");
        System.out.println("CWI_POND_FOOTPRINT_NATIVE=wet:25,legacy_survey_accepts:true,full_footprint_intersects:true");
        helper.succeed();
    }
    @GameTest(template="empty",templateNamespace="cwi_worldgen")
    public static void nativePondMaskSurvivesCellCopyErosionAndRiverColumnProjection(GameTestHelper helper){
        var mask=EnclosedPondPlanner.inspect(7,-16,-16,(x,z)->new EnclosedPondPlanner.Ground(
                Math.abs(x+16)<=5&&Math.abs(z+16)<=4?90:96,true));
        helper.assertTrue(mask!=null,"Closed native fixture absent");
        var levels=new Levels(256,63);
        int wet=0,rim=0;
        for(int z=-23;z<=-9;z++)for(int x=-24;x<=-8;x++){
            var expected=mask.column(x,z);
            Cell cell=cell(mask,x,z);
            Cell copied=new Cell();copied.copyFrom(cell);
            helper.assertTrue(copied.cwiPond==cell.cwiPond&&copied.cwiPondProtected==cell.cwiPondProtected,"Tile copy lost pond flags");
            if(expected.protectedColumn()){
                copied.height-=4F/256;WatershedHydrology.finalizeCell(copied);
                helper.assertTrue(copied.height*256==expected.originalGroundY(),"Erosion opened existing pond bed/rim");
                var projection=ReferenceRiverColumn.project(levels,copied,cell(mask,x,z-1),cell(mask,x,z+1),cell(mask,x+1,z),cell(mask,x-1,z));
                helper.assertTrue(projection.hasWater()==expected.wet(),"Mask footprint differs from native water projection");
                helper.assertTrue(projection.groundY()==expected.groundY(),"Pond carved or raised native ground");
                if(expected.wet()){
                    wet++;
                    helper.assertTrue(projection.waterY()==95&&projection.transition()==ReferenceRiverColumn.Transition.NONE,"Still pond introduced a flow step");
                    helper.assertTrue(expected.protects(88)&&expected.protects(90)&&!expected.protects(87)
                            &&expected.containsWater(91)&&expected.containsWater(95)&&!expected.containsWater(96),"Floor/water envelope differs");
                }else{
                    rim++;
                    helper.assertTrue(expected.protectionFloorY()==88&&expected.protects(91),"Native rim lost side-wall protection");
                    helper.assertTrue(projection.retainingTopY()==expected.groundY(),"Gasket raised pond rim");
                }
            }
            copied.reset();helper.assertTrue(!copied.cwiPond&&!copied.cwiPondProtected,"Cell reset leaked pond flags");
        }
        helper.assertTrue(wet==99&&rim==44,"Pond footprint or one-column rim changed: "+wet+" / "+rim);
        helper.succeed();
    }
    @GameTest(template="empty",templateNamespace="cwi_worldgen",timeoutTicks=12000)
    public static void realProfile12PondMasksKeepNativeFloorsRimsWaterAndHeightBoundsAfterCarving(GameTestHelper helper){
        ServerLevel level=helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:coordinated_wasteland_test")));
        var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        var settings=generator.generatorSettings().value();
        var state=RandomState.create(settings,level.registryAccess().lookupOrThrow(Registries.NOISE),42L);
        var terrain=((TerrainRandomState)(Object)state).cwiWorldgen$terrain();
        helper.assertTrue(terrain!=null&&terrain.generationVersion()==12,"Physical pond fixture did not bind profile12");
        try{
            var generation=new Structure.GenerationContext(level.registryAccess(),generator,generator.getBiomeSource(),
                    state,level.getStructureManager(),42L,new ChunkPos(-2607>>4,-3029>>4),level,ignored->true);
            helper.assertTrue(!TerrainCheckedJigsawStructure.acceptsTerrain(generation,
                    new BoundingBox(-2607,0,-3029,-2607,0,-3029),256,16),
                    "Profile12 structure accepted wet pond footprint under a permissive depth threshold");
            Map<BlockPos,EnclosedPondPlanner.Column> firstMask=null;
            for(int[] fixture:new int[][]{{-2607,-3029},{-1681,-2701},{575,-2890}}){
                var mask=collectMask(terrain,fixture[0],fixture[1]);
                if(firstMask==null)firstMask=mask;
                long wet=mask.values().stream().filter(EnclosedPondPlanner.Column::wet).count();
                int deepest=mask.values().stream().filter(EnclosedPondPlanner.Column::wet).mapToInt(c->c.waterY()-c.groundY()).max().orElse(0);
                helper.assertTrue(wet>=20&&wet<=768&&deepest>=3&&deepest<=8,"Native pond lost bounded connected dimensions");
                for(var entry:mask.entrySet()){
                    BlockPos p=entry.getKey();var expected=entry.getValue();
                    var cell=terrain.sample(p.getX(),p.getZ());
                    var column=ReferenceRiverColumn.sample(terrain,p.getX(),p.getZ());
                    var protection=terrain.waterProtection().column(p.getX(),p.getZ());
                    helper.assertTrue(cell.cwiPondProtected&&cell.cwiPond==expected.wet()
                            &&column.groundY()==expected.groundY()&&column.hasWater()==expected.wet(),"Filtered height or wet footprint differs at "+p);
                    helper.assertTrue(protection.floorY()==expected.protectionFloorY()&&protection.groundY()==expected.groundY(),"Native hook lost pond side/floor envelope at "+p);
                    if(expected.wet()){
                        var sample=terrain.watershedSample(p.getX(),p.getZ());
                        helper.assertTrue(sample.basin()&&sample.wet()&&!terrain.queryHydrology(p.getX(),p.getZ()).validDirection()
                                &&column.waterY()==expected.waterY()&&column.transition()==ReferenceRiverColumn.Transition.NONE,"Native pond acquired flow or water step");
                        var box=new BoundingBox(p.getX(),0,p.getZ(),p.getX(),0,p.getZ());
                        helper.assertTrue(!TerrainCheckedJigsawStructure.acceptsTerrain(box,(x,z)->column.waterY(),(x,z)->column.solidTopY(),256,0),"Structure dry-ground screen accepted pond water");
                    }else helper.assertTrue(column.retainingTopY()==expected.groundY(),"Native gasket raised existing pond rim");
                }
                System.out.println("CWI_POND_MASK_NATIVE=x:"+fixture[0]+",z:"+fixture[1]+",wet:"+wet+",max_depth:"+deepest+",protected:"+mask.size());
            }
            var biomes=level.registryAccess().registryOrThrow(Registries.BIOME);
            Map<Long,ProtoChunk> chunks=new LinkedHashMap<>();
            for(BlockPos p:firstMask.keySet())chunks.computeIfAbsent(new ChunkPos(p).toLong(),key->new ProtoChunk(new ChunkPos(p),UpgradeData.EMPTY,level,biomes,null));
            for(var chunk:chunks.values()){
                complete(level,generator.createBiomes(state,Blender.empty(),level.structureManager(),chunk));
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                complete(level,generator.fillFromNoise(Blender.empty(),state,level.structureManager(),chunk));
                chunk.setPersistedStatus(ChunkStatus.NOISE);
                int bound=ReferenceGenerationHeight.height(settings.noiseSettings(),settings,terrain,chunk.getPos(),Blender.empty(),level.structureManager(),true);
                for(var entry:firstMask.entrySet())if(new ChunkPos(entry.getKey()).equals(chunk.getPos())){
                    var expected=entry.getValue();var p=entry.getKey();
                    helper.assertTrue(settings.noiseSettings().minY()+bound>Math.max(expected.groundY(),expected.waterY()),"Optimized height clipped pond envelope");
                    for(int y=expected.protectionFloorY();y<=expected.groundY();y++){
                        var block=chunk.getBlockState(p.atY(y));
                        helper.assertTrue(!block.isAir()&&block.getFluidState().isEmpty(),"Noise/aquifer opened native pond shell at "+p.atY(y));
                    }
                }
            }
            for(var chunk:chunks.values()){
                var region=new OwnedPondRegion(level,chunk,chunks,generator,state);
                var biomeManager=new BiomeManager(region,BiomeManager.obfuscateSeed(42));
                generator.buildSurface(chunk,new WorldGenerationContext(generator,level),state,level.structureManager(),biomeManager,biomes,Blender.empty());
                chunk.setPersistedStatus(ChunkStatus.SURFACE);
                generator.applyCarvers(region,42L,state,biomeManager,level.structureManager(),chunk,GenerationStep.Carving.AIR);
                chunk.setPersistedStatus(ChunkStatus.CARVERS);
                helper.assertTrue(ReferenceRiverGasket.place(chunk,state,42)==0,"Pond post-carver gasket was not idempotent");
                for(var neighbor:region.neighbors.values())for(var section:neighbor.getSections())helper.assertTrue(section.hasOnlyAir(),"Pond wrote outside owning chunks");
            }
            int waterBlocks=0,shellBlocks=0;
            for(var entry:firstMask.entrySet()){
                BlockPos p=entry.getKey();var expected=entry.getValue();var chunk=chunks.get(new ChunkPos(p).toLong());
                for(int y=expected.protectionFloorY();y<=expected.groundY();y++){
                    var block=chunk.getBlockState(p.atY(y));
                    helper.assertTrue(!block.isAir()&&block.getFluidState().isEmpty(),"Surface/carver opened pond floor or sidewall at "+p.atY(y));shellBlocks++;
                }
                if(expected.wet()){
                    for(int y=expected.groundY()+1;y<=expected.waterY();y++){
                        helper.assertTrue(chunk.getBlockState(p.atY(y)).is(Blocks.WATER),"Pond water interval missing at "+p.atY(y));waterBlocks++;
                    }
                    helper.assertTrue(chunk.getBlockState(p.atY(expected.waterY()+1)).isAir(),"Pond retained a roof");
                }
            }
            helper.assertTrue(waterBlocks>20&&shellBlocks>60,"Native pond block fixture lacked positive coverage");
            System.out.println("CWI_POND_BLOCKS_NATIVE=chunks:"+chunks.size()+",water_blocks:"+waterBlocks+",shell_blocks:"+shellBlocks+",unowned_neighbor_writes:0");
        }finally{if(terrain!=null)terrain.close();}
        helper.succeed();
    }

    private static Map<BlockPos,EnclosedPondPlanner.Column> collectMask(ReferenceTerrain terrain,int x,int z){
        var start=terrain.pondColumn(x,z);if(!start.wet())throw new AssertionError("Native pond fixture absent "+x+","+z);
        Map<BlockPos,EnclosedPondPlanner.Column> wet=new LinkedHashMap<>();
        ArrayDeque<BlockPos> pending=new ArrayDeque<>();pending.add(new BlockPos(x,0,z));
        while(!pending.isEmpty()){
            BlockPos p=pending.remove();if(wet.containsKey(p))continue;
            var column=terrain.pondColumn(p.getX(),p.getZ());if(!column.wet()||column.id()!=start.id())continue;
            wet.put(p,column);if(wet.size()>768)throw new AssertionError("Native pond exceeded bounded area");
            pending.add(p.north());pending.add(p.south());pending.add(p.east());pending.add(p.west());
        }
        Map<BlockPos,EnclosedPondPlanner.Column> result=new LinkedHashMap<>(wet);
        for(BlockPos p:wet.keySet())for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            BlockPos q=p.offset(dx,0,dz);var column=terrain.pondColumn(q.getX(),q.getZ());
            if(column.protectedColumn()&&column.id()==start.id())result.put(q,column);
        }
        return result;
    }
    private static void complete(ServerLevel level,CompletableFuture<?> future){
        level.getServer().managedBlock(()->{level.getChunkSource().pollTask();return future.isDone();});future.join();
    }
    private static final class OwnedPondRegion extends WorldGenRegion{
        private final ServerLevel level;
        private final Map<Long,ProtoChunk> chunks,neighbors=new LinkedHashMap<>();
        private final NoiseBasedChunkGenerator generator;
        private final RandomState state;
        OwnedPondRegion(ServerLevel level,ChunkAccess center,Map<Long,ProtoChunk> chunks,NoiseBasedChunkGenerator generator,RandomState state){
            super(level,null,null,center);this.level=level;this.chunks=chunks;this.generator=generator;this.state=state;
        }
        @Override public long getSeed(){return 42;}
        @Override public ChunkAccess getChunk(int x,int z){return getChunk(x,z,ChunkStatus.EMPTY,true);}
        @Override public ChunkAccess getChunk(int x,int z,ChunkStatus status,boolean required){
            long key=ChunkPos.asLong(x,z);var existing=chunks.get(key);if(existing!=null)return existing;
            if(status.isAfter(ChunkStatus.EMPTY))throw new AssertionError("Pond requested generated unowned neighbor");
            return neighbors.computeIfAbsent(key,ignored->new ProtoChunk(new ChunkPos(x,z),UpgradeData.EMPTY,level,level.registryAccess().registryOrThrow(Registries.BIOME),null));
        }
        @Override public Holder<Biome> getNoiseBiome(int x,int y,int z){return generator.getBiomeSource().getNoiseBiome(x,y,z,state.sampler());}
    }

    private static Cell cell(EnclosedPondPlanner.Mask mask,int x,int z){
        var column=mask.column(x,z);Cell cell=new Cell();
        cell.height=column.protectedColumn()?(float)(column.originalGroundY()/256):96F/256;
        cell.terrain=TerrainType.FLATS;
        cell.cwiPondProtected=column.protectedColumn();cell.cwiPond=column.wet();
        cell.cwiOriginalGroundHeight=cell.height;cell.cwiBedHeight=cell.height;
        if(column.wet()){
            cell.cwiBasin=true;cell.cwiNaturalRiver=true;cell.cwiPlannedWater=true;
            cell.cwiWaterHeight=column.waterY()/256F;cell.riverWaterLevel=cell.cwiWaterHeight;
            cell.terrain=WatershedTerrain.LAKE;
        }
        return cell;
    }
}

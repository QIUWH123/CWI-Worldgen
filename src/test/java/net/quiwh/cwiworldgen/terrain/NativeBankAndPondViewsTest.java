package net.quiwh.cwiworldgen.terrain;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Supplemental actual block views; run once per seed in a fresh disposable native server. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeBankAndPondViewsTest {
    @GameTest(template="empty",templateNamespace="cwi_worldgen",timeoutTicks=24000)
    public static void actualFeatureBankEdgesAndPondExportMixedWaterAndDryViews(GameTestHelper helper) throws Exception {
        if(!Boolean.getBoolean("cwi.worldgen.bankViews")){helper.succeed();return;}
        if(!(helper.getLevel().getServer() instanceof GameTestServer))throw new AssertionError("Bank views require a disposable GameTestServer");
        long seed=Long.parseLong(System.getProperty("cwi.worldgen.bankViewSeed","42"));
        if(!Set.of(0L,42L,123456789L,-987654321L).contains(seed))throw new AssertionError("Unexpected bank-view fixture seed");
        Path output=Path.of(System.getProperty("cwi.worldgen.bankViewOutput","native-bank-and-pond-views-"+seed+".json")).toAbsolutePath();
        if(Files.exists(output))throw new AssertionError("Never overwrite an existing bank-view artifact: "+output);
        ServerLevel level=helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:coordinated_wasteland_test")));
        if(level==null)throw new AssertionError("Missing default-profile native wasteland dimension");
        var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
        if(TerrainGenerationProfile.fromRouter(generator.generatorSettings().value().noiseRouter())!=12)
            throw new AssertionError("Bank-view fixture requires production profile12");
        List<Map<String,Object>> attempts=new ArrayList<>(),selected=new ArrayList<>();
        try(var scope=new NativeGenerationSeedScope(level,generator,seed)){
            var terrain=((TerrainRandomState)(Object)level.getChunkSource().randomState()).cwiWorldgen$terrain();
            if(terrain==null||terrain.generationVersion()!=12||level.getSeed()!=seed)throw new AssertionError("Wrong native seed/profile");
            Set<Long> visitedWindows=new LinkedHashSet<>();int examined=0;
            // Reuse one completed drainage graph. The 64-node screen and four actual windows are hard bounds.
            var reaches=terrain.hydrologySnapshot(0,0).reaches().stream()
                    .sorted(Comparator.comparingLong(net.quiwh.cwiworldgen.hydrology.DirectedReach::id)).toList();
            search:for(var reach:reaches){
                if(examined>=64)break;
                examined++;
                if(reach.points().size()<2||!reach.hasDownstream())continue;
                var point=reach.points().get(reach.points().size()/2);
                int x=(int)Math.round(point.x()),z=(int)Math.round(point.z());
                var channel=terrain.watershedSample(x,z);
                if(!channel.valid()||!channel.wet()||channel.basin()||channel.island()||channel.reachId()!=reach.id()
                        ||channel.bankContext()==null||channel.width()<6||Math.abs(channel.distance())>channel.width()
                        ||channel.waterY()<=terrain.levels().waterY+2)continue;
                double length=Math.hypot(channel.tangentX(),channel.tangentZ());if(length<.5)continue;
                double nx=-channel.tangentZ()/length,nz=channel.tangentX()/length;
                for(int side:new int[]{1,-1}){
                    // Shift from projected sample to the real signed bank edge, then four blocks into exposed bank.
                    double offset=side*(channel.width()+4)-channel.distance();
                    int edgeX=(int)Math.round(x+nx*offset),edgeZ=(int)Math.round(z+nz*offset);
                    int cx=Math.floorDiv(edgeX,16)-1,cz=Math.floorDiv(edgeZ,16)-1;
                    if(!visitedWindows.add(ChunkPos.asLong(cx,cz)))continue;
                    var view=exportView(level,cx,cz,"native-bank-edge",Math.abs(nx)>=Math.abs(nz));
                    view.put("selected_reach_id",channel.reachId());view.put("selected_reach_sample",List.of(x,z));
                    view.put("planned_width",channel.width());view.put("planned_water_y",channel.waterY());
                    view.put("edge_center",List.of(edgeX,edgeZ));view.put("edge_side",side);
                    view.put("planned_bank_context",channel.bankContext());attempts.add(view);
                    int water=((Number)view.get("water_columns")).intValue(),dry=((Number)view.get("dry_columns")).intValue();
                    System.out.println("CWI_NATIVE_BANK_VIEW=seed:"+seed+",attempt:"+attempts.size()+",chunk:"+cx+","+cz+",water:"+water+",dry:"+dry);
                    if(water>=8&&dry>=8){selected.add(view);break search;}
                    if(attempts.size()>=4)break search;
                }
            }
            if(seed==42){
                var pond=terrain.pondColumn(-2607,-3029);
                if(!pond.wet())throw new AssertionError("Known seed42 pond fixture no longer exists");
                var wet=pondFootprint(terrain,-2607,-3029,pond.id());
                int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
                for(var position:wet){minX=Math.min(minX,position.getX());maxX=Math.max(maxX,position.getX());minZ=Math.min(minZ,position.getZ());maxZ=Math.max(maxZ,position.getZ());}
                if(maxX-minX>=49||maxZ-minZ>=49)throw new AssertionError("Known pond escaped its bounded49-column inspection");
                int cx=Math.floorDiv(minX-2,16),cz=Math.floorDiv(minZ-2,16);
                int chunksX=Math.floorDiv(maxX+2,16)-cx+1,chunksZ=Math.floorDiv(maxZ+2,16)-cz+1;
                // Complete natural water footprint, its native one-column rim and one extra dry context column.
                // At most5x5 owner chunks even at unfavorable chunk alignment; only the seed42 run adds this view.
                var view=exportView(level,cx,cz,chunksX,chunksZ,"seed42-native-pond",true,
                        Math.floorDiv(minZ+maxZ,2));
                view.put("pond_fixture",List.of(-2607,-3029));view.put("planned_water_y",pond.waterY());view.put("pond_id",pond.id());
                view.put("pond_wet_bounds",List.of(minX,minZ,maxX,maxZ));view.put("planned_pond_wet_columns",wet.size());
                view.put("complete_pond_footprint",true);view.put("minimum_dry_context_columns",2);
                int[][] depths=(int[][])view.get("water_depth");int[][] tops=(int[][])view.get("actual_top_y");
                for(var position:wet){
                    int localX=position.getX()-cx*16,localZ=position.getZ()-cz*16;
                    if(depths[localZ][localX]<1||tops[localZ][localX]!=pond.waterY())
                        throw new AssertionError("Actual FEATURES pond footprint lost surface water at "+position);
                }
                if(((Number)view.get("water_columns")).intValue()<8||((Number)view.get("dry_columns")).intValue()<8)
                    throw new AssertionError("Known pond crop did not contain both native water and dry bank");
                selected.add(view);
            }
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("seed",seed);report.put("profile",12);report.put("stage","FEATURES");
            report.put("method","Actual native ChunkStatus.FEATURES pipeline including structures, noise, surface, caves and complete decoration; independent disposable server per seed");
            report.put("view_method","Actual top material, native heightmaps and cross-bank center block section. Data views, not in-game camera screenshots. No timing or performance claim.");
            report.put("selection","At most64 existing directed reach midpoints; accepted main-reach context, inland water, signed width/normal bank edge; at most4 actual2x2owner bank windows. Seed42 additionally crops the complete bounded pond wet mask plus two-column dry context in at most5x5owner chunks. Require at least8water and8dry columns.");
            report.put("examined_reaches",examined);report.put("bank_attempts",attempts);report.put("selected_views",selected);
            boolean passed=selected.stream().anyMatch(view->view.get("label").equals("native-bank-edge"));report.put("result",passed?"PASS":"FAIL");
            Files.createDirectories(output.getParent());Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
            if(!passed)throw new AssertionError("No mixed actual bank view within bounded attempts; diagnostics retained at "+output);
            System.out.println("CWI_NATIVE_BANK_AND_POND_VIEWS="+output+",seed:"+seed+",bank_attempts:"+attempts.size()+",selected:"+selected.size());
        }
        helper.succeed();
    }

    private static Set<BlockPos> pondFootprint(net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain terrain,int x,int z,long id){
        Set<BlockPos> wet=new LinkedHashSet<>(),visited=new LinkedHashSet<>();ArrayDeque<BlockPos> pending=new ArrayDeque<>();
        pending.add(new BlockPos(x,0,z));
        while(!pending.isEmpty()){
            var position=pending.remove();if(!visited.add(position))continue;
            if(visited.size()>49*49)throw new AssertionError("Pond footprint discovery exceeded one49x49inspection");
            var column=terrain.pondColumn(position.getX(),position.getZ());if(!column.wet()||column.id()!=id)continue;
            wet.add(position);if(wet.size()>768)throw new AssertionError("Pond footprint exceeds768wet columns");
            pending.add(position.north());pending.add(position.south());pending.add(position.east());pending.add(position.west());
        }
        if(wet.size()<20)throw new AssertionError("Known pond lacks its minimum20-column wet footprint");
        return wet;
    }
    private static Map<String,Object> exportView(ServerLevel level,int cx,int cz,String label,boolean sectionAlongX){
        return exportView(level,cx,cz,2,2,label,sectionAlongX,sectionAlongX?cz*16+16:cx*16+16);
    }
    private static Map<String,Object> exportView(ServerLevel level,int cx,int cz,int chunksX,int chunksZ,
            String label,boolean sectionAlongX,int fixedCoordinate){
        if(chunksX<1||chunksZ<1||chunksX>5||chunksZ>5)throw new AssertionError("View exceeds bounded owner crop");
        Map<Long,ChunkAccess> chunks=new LinkedHashMap<>();
        for(int z=0;z<chunksZ;z++)for(int x=0;x<chunksX;x++){
            var chunk=level.getChunk(cx+x,cz+z,ChunkStatus.FEATURES,true);
            if(chunk==null||!chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES))throw new AssertionError("Bank owner did not reach FEATURES");
            chunks.put(ChunkPos.asLong(cx+x,cz+z),chunk);
        }
        int width=chunksX*16,depth=chunksZ*16;
        if(fixedCoordinate<(sectionAlongX?cz*16:cx*16)||fixedCoordinate>=(sectionAlongX?cz*16+depth:cx*16+width))throw new AssertionError("Section outside exported owners");
        int[][] height=new int[depth][width],floor=new int[depth][width],actual=new int[depth][width],material=new int[depth][width],depths=new int[depth][width];
        Map<String,Integer> indices=new LinkedHashMap<>();List<String> palette=new ArrayList<>();
        int water=0,dry=0,minDepth=Integer.MAX_VALUE,maxDepth=0,minSlice=Integer.MAX_VALUE,maxSlice=Integer.MIN_VALUE;
        var cursor=new BlockPos.MutableBlockPos();
        for(int z=0;z<depth;z++)for(int x=0;x<width;x++){
            int wx=cx*16+x,wz=cz*16+z;var chunk=chunks.get(ChunkPos.asLong(wx>>4,wz>>4));
            height[z][x]=chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,x&15,z&15);
            floor[z][x]=chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG,x&15,z&15);
            int top=Math.min(chunk.getMaxBuildHeight()-1,chunk.getHighestSectionPosition()+15);
            while(top>chunk.getMinBuildHeight()&&chunk.getBlockState(cursor.set(wx,top,wz)).isAir())top--;
            actual[z][x]=top;var block=chunk.getBlockState(cursor.set(wx,top,wz));material[z][x]=palette(indices,palette,block);
            if(block.getFluidState().is(Fluids.WATER)){
                water++;int y=top;
                while(y>=chunk.getMinBuildHeight()&&chunk.getBlockState(cursor.set(wx,y,wz)).getFluidState().is(Fluids.WATER))y--;
                int waterDepth=top-y;depths[z][x]=waterDepth;minDepth=Math.min(minDepth,waterDepth);maxDepth=Math.max(maxDepth,waterDepth);
            }else if(block.getFluidState().isEmpty())dry++;
            if(sectionAlongX?wz==fixedCoordinate:wx==fixedCoordinate){minSlice=Math.min(minSlice,Math.max(chunk.getMinBuildHeight(),Math.min(top,floor[z][x])-10));maxSlice=Math.max(maxSlice,Math.min(chunk.getMaxBuildHeight()-1,top+4));}
        }
        int sectionWidth=sectionAlongX?width:depth;
        int[][] slice=new int[maxSlice-minSlice+1][sectionWidth];
        for(int y=maxSlice;y>=minSlice;y--)for(int i=0;i<sectionWidth;i++){
            int wx=sectionAlongX?cx*16+i:fixedCoordinate,wz=sectionAlongX?fixedCoordinate:cz*16+i;
            var chunk=chunks.get(ChunkPos.asLong(wx>>4,wz>>4));slice[maxSlice-y][i]=palette(indices,palette,chunk.getBlockState(cursor.set(wx,y,wz)));
        }
        Map<String,Object> view=new LinkedHashMap<>();view.put("label",label);view.put("chunk_origin",List.of(cx,cz));view.put("chunk_dimensions",List.of(chunksX,chunksZ));if(chunksX==chunksZ)view.put("chunks_per_side",chunksX);view.put("block_width",width);view.put("block_depth",depth);
        view.put("palette",palette);view.put("top_material_indices",material);view.put("world_surface_wg",height);view.put("ocean_floor_wg",floor);view.put("actual_top_y",actual);
        view.put("water_depth",depths);view.put("water_columns",water);view.put("dry_columns",dry);view.put("min_water_depth",water==0?0:minDepth);view.put("max_water_depth",maxDepth);
        view.put("center_slice_axis",sectionAlongX?"x":"z");view.put("center_slice_fixed_coordinate",fixedCoordinate);
        view.put("center_slice_min_y",minSlice);view.put("center_slice_max_y",maxSlice);view.put("center_slice_indices",slice);return view;
    }
    private static int palette(Map<String,Integer> index,List<String> values,net.minecraft.world.level.block.state.BlockState state){
        String name=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();Integer known=index.get(name);if(known!=null)return known;
        int next=values.size();values.add(name);index.put(name,next);return next;
    }
}

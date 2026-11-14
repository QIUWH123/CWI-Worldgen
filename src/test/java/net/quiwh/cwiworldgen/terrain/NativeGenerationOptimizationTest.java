package net.quiwh.cwiworldgen.terrain;

import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Run unchanged against baseline and candidate JARs in fresh disposable servers. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeGenerationOptimizationTest {
    @GameTest(template="empty",templateNamespace="cwi_worldgen",timeoutTicks=24000)
    public static void completedFeatureBlocksBiomesAndHeightmapsStayIdentical(GameTestHelper helper) throws Exception {
        if(!(helper.getLevel().getServer() instanceof GameTestServer))
            throw new AssertionError("Generation verification requires an isolated GameTestServer");
        var level=helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        helper.assertTrue(level!=null&&level.getSeed()==0,"Wrong isolated dimension or seed");
        var terrain=((TerrainRandomState)(Object)level.getChunkSource().randomState()).cwiWorldgen$terrain();
        helper.assertTrue(terrain!=null&&terrain.generationVersion()==21,"Wrong generation profile");
        var positions=new ArrayList<>(List.of(new ChunkPos(0,0),new ChunkPos(1,0)));
        // A confirmed river location supplements adjacent dry/ordinary owner chunks.
        for(var reach:terrain.hydrologySnapshot(0,0).reaches().stream()
                .sorted(java.util.Comparator.comparingLong(net.quiwh.cwiworldgen.hydrology.DirectedReach::id)).toList()){
            if(reach.points().isEmpty())continue;
            var point=reach.points().get(reach.points().size()/2);
            int x=(int)Math.round(point.x()),z=(int)Math.round(point.z());
            var water=terrain.watershedSample(x,z);
            if(!water.wet()||water.basin()||water.island()||water.reachId()!=reach.id())continue;
            var position=new ChunkPos(new BlockPos(x,0,z));
            if(!positions.contains(position)){positions.add(position);break;}
        }
        helper.assertTrue(positions.size()==3,"No confirmed river fixture");
        List<ChunkAccess> generated=new ArrayList<>();
        long start=System.nanoTime();
        for(var position:positions){
            var chunk=level.getChunk(position.x,position.z,ChunkStatus.FEATURES,true);
            helper.assertTrue(chunk!=null&&chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES),
                    "Incomplete decorated chunk "+position);
            generated.add(chunk);
        }
        double seconds=(System.nanoTime()-start)/1E9;
        List<Map<String,Object>> chunks=new ArrayList<>();
        for(var chunk:generated)chunks.add(fingerprint(chunk));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("profile",21);result.put("seed",0);result.put("stage","FEATURES");result.put("chunks",chunks);
        result.put("generation_seconds",seconds);
        System.out.println("CWI_GENERATION_OPTIMIZATION="+new GsonBuilder().create().toJson(result));
        helper.succeed();
    }
    private static Map<String,Object> fingerprint(ChunkAccess chunk) throws Exception {
        var blocks=MessageDigest.getInstance("SHA-256");
        var biomes=MessageDigest.getInstance("SHA-256");
        var heights=MessageDigest.getInstance("SHA-256");
        var position=chunk.getPos();var cursor=new BlockPos.MutableBlockPos();
        long count=0,nonAir=0,water=0;
        for(int y=chunk.getMinBuildHeight();y<chunk.getMaxBuildHeight();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            cursor.set(position.getBlockX(x),y,position.getBlockZ(z));
            var state=chunk.getBlockState(cursor);text(blocks,state.toString());count++;
            if(!state.isAir())nonAir++;
            if(!state.getFluidState().isEmpty())water++;
        }
        for(int y=chunk.getMinBuildHeight()>>2;y<chunk.getMaxBuildHeight()>>2;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)
            text(biomes,chunk.getNoiseBiome(position.x*4+x,y,position.z*4+z).unwrapKey().orElseThrow().location().toString());
        for(var type:Heightmap.Types.values())for(int z=0;z<16;z++)for(int x=0;x<16;x++)
            text(heights,type.getSerializationKey()+":"+x+","+z+"="+chunk.getHeight(type,x,z));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("x",position.x);result.put("z",position.z);result.put("blocks",HexFormat.of().formatHex(blocks.digest()));
        result.put("biomes",HexFormat.of().formatHex(biomes.digest()));result.put("heightmaps",HexFormat.of().formatHex(heights.digest()));
        result.put("block_count",count);result.put("non_air",nonAir);result.put("fluid",water);
        return result;
    }
    private static void text(MessageDigest digest,String text){digest.update((text+"\n").getBytes(StandardCharsets.UTF_8));}
}

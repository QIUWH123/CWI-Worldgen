package net.quiwh.cwiworldgen.terrain;

import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Executed unchanged against baseline and candidate mod classes in separate disposable servers. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeSavedFeatureParityTest {
    private static final String[] DIMENSIONS = {"", "wasteland_legacy_test", "watershed_wasteland_test",
            "compact_wasteland_test", "natural_wasteland_test", "refined_wasteland_test", "streams_wasteland_test",
            "compact_natural_wasteland_test", "natural_geometry_wasteland_test", "river_frequency_wasteland_test",
            "water_landscape_wasteland_test", "mountain_ranges_wasteland_test", "coordinated_wasteland_test",
            "scenery_shapes_wasteland_test", "fine_sediments_wasteland_test", "mountain_ridges_wasteland_test", "naturalness_wasteland_test"};

    @GameTest(template="empty",templateNamespace="cwi_worldgen",timeoutTicks=24000)
    public static void savedProfilesReachRealFeaturesStageWithExactBlockFixtures(GameTestHelper helper) throws Exception {
        if(!Boolean.getBoolean("cwi.worldgen.savedFeatureParity")) { helper.succeed(); return; }
        if(!(helper.getLevel().getServer() instanceof GameTestServer))
            throw new AssertionError("Saved feature parity is restricted to a disposable GameTestServer");
        long seed=Long.parseLong(System.getProperty("cwi.worldgen.savedFeatureSeed","0"));
        int[] profiles=Arrays.stream(System.getProperty("cwi.worldgen.savedFeatureProfiles","1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16")
                .split(",")).mapToInt(Integer::parseInt).toArray();
        if(profiles.length==0)throw new AssertionError("No saved profiles selected");
        for(int profile:profiles) {
            if(profile<1||profile>=DIMENSIONS.length)throw new AssertionError("Expected a historical profile, got "+profile);
            ServerLevel level=helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse("cwi_worldgen:"+DIMENSIONS[profile])));
            if(level==null)throw new AssertionError("Missing historical dimension "+DIMENSIONS[profile]);
            var generator=(NoiseBasedChunkGenerator)level.getChunkSource().getGenerator();
            if(TerrainGenerationProfile.fromRouter(generator.generatorSettings().value().noiseRouter())!=profile)
                throw new AssertionError("Historical dimension has the wrong profile");
            long started=System.nanoTime();
            try(var scope=new NativeGenerationSeedScope(level,generator,seed)) {
                var terrain=((TerrainRandomState)(Object)level.getChunkSource().randomState()).cwiWorldgen$terrain();
                if(level.getSeed()!=seed||terrain==null||terrain.generationVersion()!=profile)
                    throw new AssertionError("Real feature pipeline did not adopt the requested seed/profile");
                // Two adjacent owners exercise cross-chunk decorations and their deterministic ordering.
                List<ChunkPos> fixtures=new ArrayList<>(List.of(new ChunkPos(0,0),new ChunkPos(1,0)));
                if(profile>=2)for(var reach:terrain.hydrologySnapshot(0,0).reaches().stream()
                        .sorted(java.util.Comparator.comparingLong(net.quiwh.cwiworldgen.hydrology.DirectedReach::id)).toList()) {
                    if(reach.points().isEmpty())continue;
                    var point=reach.points().get(reach.points().size()/2);
                    int x=(int)Math.round(point.x()),z=(int)Math.round(point.z());
                    var water=terrain.watershedSample(x,z);
                    if(!water.wet()||water.basin()||water.island()||water.reachId()!=reach.id())continue;
                    var chunk=new ChunkPos(new BlockPos(x,0,z));
                    if(!fixtures.contains(chunk))fixtures.add(chunk);
                    break;
                }
                List<ChunkAccess> generated=new ArrayList<>();
                for(var position:fixtures) {
                    // Native ChunkStatus tasks execute structures, biomes, noise, surface, carvers and
                    // the complete biome-decoration list, including every preceding feature RNG draw.
                    ChunkAccess chunk=level.getChunk(position.x,position.z,ChunkStatus.FEATURES,true);
                    if(chunk==null||!chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES))
                        throw new AssertionError("Fixture failed to reach FEATURES: "+position);
                    generated.add(chunk);
                }
                List<Map<String,Object>> chunks=new ArrayList<>();
                for(var chunk:generated)chunks.add(hashChunk(chunk));
                Map<String,Object> result=new LinkedHashMap<>();
                result.put("profile",profile);result.put("seed",seed);result.put("stage","FEATURES");
                result.put("chunks",chunks);result.put("duration_seconds",(System.nanoTime()-started)/1E9);
                System.out.println("CWI_SAVED_FEATURE_PARITY="+new GsonBuilder().create().toJson(result));
            }
        }
        helper.succeed();
    }

    private static Map<String,Object> hashChunk(ChunkAccess chunk) throws Exception {
        var blocks=MessageDigest.getInstance("SHA-256");
        var biomes=MessageDigest.getInstance("SHA-256");
        var heights=MessageDigest.getInstance("SHA-256");
        var position=chunk.getPos();var cursor=new BlockPos.MutableBlockPos();
        long blockCount=0;
        for(int y=chunk.getMinBuildHeight();y<chunk.getMaxBuildHeight();y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
            cursor.set(position.getBlockX(x),y,position.getBlockZ(z));
            text(blocks,chunk.getBlockState(cursor).toString());blockCount++;
        }
        for(int y=chunk.getMinBuildHeight()>>2;y<chunk.getMaxBuildHeight()>>2;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)
            text(biomes,chunk.getNoiseBiome(position.x*4+x,y,position.z*4+z).unwrapKey().orElseThrow().location().toString());
        for(var type:Heightmap.Types.values())for(int z=0;z<16;z++)for(int x=0;x<16;x++)
            text(heights,type.getSerializationKey()+":"+x+","+z+"="+chunk.getHeight(type,x,z));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("x",position.x);result.put("z",position.z);result.put("blocks",hex(blocks));
        result.put("biomes",hex(biomes));result.put("heightmaps",hex(heights));result.put("block_count",blockCount);
        return result;
    }
    private static void text(MessageDigest digest,String value) { digest.update((value+"\n").getBytes(StandardCharsets.UTF_8)); }
    private static String hex(MessageDigest digest) { return java.util.HexFormat.of().formatHex(digest.digest()); }

}

package net.quiwh.cwiworldgen.terrain;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.quiwh.cwiworldgen.placement.SceneryClusters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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

/** Opt-in real FEATURES-stage timings and block projections; never a sampled-terrain stand-in. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeGeneratedWorldSurveyTest {
    @GameTest(template="empty",templateNamespace="cwi_worldgen",timeoutTicks=24000)
    public static void realFeatureChunksExportTimingsHeightmapsAndSurfaceBlocks(GameTestHelper helper) throws Exception {
        if (!Boolean.getBoolean("cwi.worldgen.generatedSurvey")) { helper.succeed(); return; }
        long seed = Long.parseLong(System.getProperty("cwi.worldgen.generatedSeed", "0"));
        int count = Integer.parseInt(System.getProperty("cwi.worldgen.generatedChunks", "8"));
        int cx = Integer.parseInt(System.getProperty("cwi.worldgen.generatedChunkX", "128"));
        int cz = Integer.parseInt(System.getProperty("cwi.worldgen.generatedChunkZ", "128"));
        int treeChunks = Integer.getInteger("cwi.worldgen.generatedTreeChunks", 2);
        int treeWindows = Integer.getInteger("cwi.worldgen.generatedTreeWindows", 2);
        if (count < 2 || count > 16) throw new AssertionError("Bounded survey requires 2-16 chunks per side");
        if (treeChunks < 2 || treeChunks > 8 || treeWindows < 1 || treeWindows > 8)
            throw new AssertionError("Supplemental tree surveys require 1-8 windows of 2-8 chunks per side");
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        if (level == null) throw new AssertionError("Missing native default-profile wasteland dimension");
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        int profile = TerrainGenerationProfile.fromRouter(generator.generatorSettings().value().noiseRouter());
        int expected = Integer.parseInt(System.getProperty("cwi.worldgen.generatedProfile", Integer.toString(profile)));
        if (profile != expected) throw new AssertionError("Survey bound another default profile: " + profile);
        Path output = Path.of(System.getProperty("cwi.worldgen.generatedOutput", "generated-world-survey.json")).toAbsolutePath();
        if (Files.exists(output)) throw new AssertionError("Never overwrite a prior generated-world survey: " + output);
        try (var scope = new NativeGenerationSeedScope(level, generator, seed)) {
            var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
            if (level.getSeed() != seed || terrain == null || terrain.generationVersion() != profile)
                throw new AssertionError("Survey did not bind requested actual seed/profile");
            // Warm JIT and native status tasks in a disjoint area; measurements begin with fresh owners.
            long warmStarted = System.nanoTime();
            level.getChunk(-128, -128, ChunkStatus.FEATURES, true);
            double warmSeconds = (System.nanoTime() - warmStarted) / 1E9;
            List<Map<String,Object>> timings = new ArrayList<>();
            Map<Long,ChunkAccess> chunks = new LinkedHashMap<>();
            for (int z = 0; z < count; z++) for (int x = 0; x < count; x++) {
                ChunkPos pos = new ChunkPos(cx + x, cz + z);
                long started = System.nanoTime();
                ChunkAccess chunk = level.getChunk(pos.x, pos.z, ChunkStatus.FEATURES, true);
                double millis = (System.nanoTime() - started) / 1E6;
                if (chunk == null || !chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES))
                    throw new AssertionError("Actual chunk did not reach FEATURES: " + pos);
                chunks.put(pos.toLong(), chunk);
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("x", pos.x); row.put("z", pos.z); row.put("milliseconds", millis);
                timings.add(row);
                System.out.println("CWI_GENERATED_CHUNK_TIME=seed:" + seed + ",profile:" + profile
                        + ",x:" + pos.x + ",z:" + pos.z + ",stage:FEATURES,ms:" + millis);
            }
            int width = count * 16;
            int[][] heights = new int[width][width], floors = new int[width][width], actualTop = new int[width][width];
            int[][] topMaterial = new int[width][width];
            Map<String,Integer> paletteIndex = new LinkedHashMap<>();
            List<String> palette = new ArrayList<>();
            Map<String,Long> blockCounts = new LinkedHashMap<>();
            Map<String,Integer> biomeColumns = new LinkedHashMap<>();
            var cursor = new BlockPos.MutableBlockPos();
            int minSlice = Integer.MAX_VALUE, maxSlice = Integer.MIN_VALUE;
            for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
                int wx = cx * 16 + x, wz = cz * 16 + z;
                var chunk = chunks.get(ChunkPos.asLong(wx >> 4, wz >> 4));
                heights[z][x] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
                floors[z][x] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15);
                int top = Math.min(chunk.getMaxBuildHeight() - 1, chunk.getHighestSectionPosition() + 15);
                while (top > chunk.getMinBuildHeight() && chunk.getBlockState(cursor.set(wx, top, wz)).isAir()) top--;
                actualTop[z][x] = top;
                String topId = id(chunk.getBlockState(cursor.set(wx, top, wz)).getBlock());
                topMaterial[z][x] = palette(paletteIndex, palette, topId);
                String biome = chunk.getNoiseBiome(wx >> 2, top >> 2, wz >> 2).unwrapKey().orElseThrow().location().toString();
                biomeColumns.merge(biome, 1, Integer::sum);
                int bottom = Math.max(chunk.getMinBuildHeight(), floors[z][x] - 8);
                int cap = Math.min(chunk.getMaxBuildHeight() - 1, Math.max(floors[z][x] + 40, top + 2));
                for (int y = bottom; y <= cap; y++) {
                    var state = chunk.getBlockState(cursor.set(wx, y, wz));
                    if (!state.isAir()) blockCounts.merge(id(state.getBlock()), 1L, Long::sum);
                }
                if (z == width / 2) { minSlice = Math.min(minSlice, bottom); maxSlice = Math.max(maxSlice, top + 3); }
            }
            int[][] slice = new int[maxSlice - minSlice + 1][width];
            int sliceZ = cz * 16 + width / 2;
            for (int y = maxSlice; y >= minSlice; y--) for (int x = 0; x < width; x++) {
                int wx = cx * 16 + x;
                var chunk = chunks.get(ChunkPos.asLong(wx >> 4, sliceZ >> 4));
                slice[maxSlice - y][x] = palette(paletteIndex, palette, id(chunk.getBlockState(cursor.set(wx, y, sliceZ)).getBlock()));
            }
            // Decoration evidence starts only after every timed owner has completed and been exported.
            // Replayed baseline window coordinates keep placement comparisons paired and timing unchanged.
            List<Map<String,Object>> treeSurveys=new ArrayList<>();
            treeSurveys.add(treeSurvey(level,chunks,cx,cz,count,seed,"primary"));
            Map<String,Object> treeSelection=new LinkedHashMap<>();
            String referenceName=System.getProperty("cwi.worldgen.generatedTreeReference","");
            JsonObject reference=referenceName.isBlank()?null:new GsonBuilder().create().fromJson(Files.readString(Path.of(referenceName)),JsonObject.class);
            if(reference!=null){
                validateTreeReference(reference,seed,cx,cz,count,treeChunks,treeWindows);
                treeSelection.put("algorithm","baseline-only-actual-grove-v1");
                treeSelection.put("mode","immutable-baseline-replay");
                treeSelection.put("reference_sha256",java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(referenceName)))));
                treeSelection.put("requested_windows",treeWindows);
                List<Map<String,Object>> replayed=new ArrayList<>();
                for(var entry:reference.getAsJsonArray("tree_surveys")){
                    var window=entry.getAsJsonObject();if(window.get("label").getAsString().equals("primary"))continue;
                    var origin=window.getAsJsonArray("chunk_origin");int tx=origin.get(0).getAsInt(),tz=origin.get(1).getAsInt();
                    int size=window.get("chunks_per_side").getAsInt();
                    var observed=treeSurvey(level,featureChunks(level,tx,tz,size),tx,tz,size,seed,window.get("label").getAsString());
                    // Empty candidate outcomes remain paired observations; they never trigger a replacement search.
                    treeSurveys.add(observed);replayed.add(observed);
                }
                treeSelection.put("replayed_windows",replayed);
                treeSelection.put("status",replayed.size()==treeWindows?"COMPLETE":"INCOMPLETE");
                writeTreeDiscovery(output,treeSelection);
            }else{
                if(profile>=17)throw new AssertionError("Candidate tree coverage requires an immutable completed baseline reference");
                treeSurveys.addAll(treeWindows(level,generator,terrain,cx,cz,count,seed,treeChunks,treeWindows,treeSelection,output));
            }
            if(treeSurveys.size()!=treeWindows+1)throw new AssertionError("Required primary plus "+treeWindows+" actual supplemental tree windows; got "+treeSurveys.size());
            Map<String,Object> treeTotals=treeTotals(treeSurveys);
            Map<String,Object> treeComparison=reference==null?Map.of("result","UNPAIRED","reason","No baseline reference supplied"):
                    compareTrees(treeSurveys,treeTotals,reference,profile);
            List<Map<String,Object>> supplementalViews=new ArrayList<>();
            int[] river=riverWindow(terrain,cx*16+width/2,cz*16+width/2);
            if(river!=null)supplementalViews.add(surfaceView(featureChunks(level,river[0],river[1],2),river[0],river[1],2,"native-river"));
            if(seed==42)supplementalViews.add(surfaceView(featureChunks(level,Math.floorDiv(-2607,16)-1,Math.floorDiv(-3029,16)-1,2),
                    Math.floorDiv(-2607,16)-1,Math.floorDiv(-3029,16)-1,2,"seed42-native-pond-site"));
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("method", "Actual native ChunkStatus.FEATURES task pipeline; structures, biomes, noise, surface, carvers, complete biome decorations and preceding RNG draws");
            result.put("view_method", "Block-derived top-material projection, actual heightmaps, and actual center block slice; these are data views, not in-game camera screenshots");
            result.put("seed", seed); result.put("profile", profile); result.put("warmup_seconds", warmSeconds);
            result.put("chunk_origin", List.of(cx, cz)); result.put("chunks_per_side", count); result.put("block_width", width);
            result.put("timings", timings); result.put("palette", palette); result.put("top_material_indices", topMaterial);
            result.put("world_surface_wg", heights); result.put("ocean_floor_wg", floors); result.put("actual_top_y", actualTop);
            result.put("center_slice_z", sliceZ); result.put("center_slice_min_y", minSlice); result.put("center_slice_max_y", maxSlice);
            result.put("center_slice_indices", slice); result.put("surface_band_block_counts", blockCounts);
            result.put("surface_band_definition", "Each column OCEAN_FLOOR_WG-8 through max(OCEAN_FLOOR_WG+40,actual_top+2), clamped to build height; air omitted");
            result.put("top_biome_columns", biomeColumns);
            result.put("tree_surveys",treeSurveys);result.put("tree_totals",treeTotals);result.put("tree_comparison",treeComparison);
            result.put("tree_selection",treeSelection);
            result.put("tree_scope","Actual supported vertical ash/burnt logs after FEATURES; shared candidate coordinates are not counted as successful trees. Baseline selects the first requested actual grove-eligible windows in a frozen bounded schedule; successful tree count never selects a window. Candidate replays every immutable baseline window, including empty candidate outcomes. All discovery and replay are outside timing scope.");
            result.put("supplemental_views",supplementalViews);
            result.put("supplemental_scope","Actual native FEATURES block projections generated after all measured owners. Baseline tree discovery examines at most256 deterministic origins and generates at most16 windows of the configured2-8 chunks per side; selected windows and every rejected actual observation are retained in the discovery trace. Candidate replays only the required baseline windows. Also one2x2 river window and seed42 one2x2 known pond-site window; none included in timings.");
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
            System.out.println("CWI_GENERATED_TREE_SURVEY="+new GsonBuilder().create().toJson(treeTotals)+",comparison="+new GsonBuilder().create().toJson(treeComparison));
            if(reference!=null&&!"PASS".equals(treeComparison.get("result")))throw new AssertionError("Actual standing-tree coverage/gap comparison failed: "+treeComparison);
            System.out.println("CWI_GENERATED_WORLD_SURVEY=" + output + ",seed:" + seed + ",profile:" + profile + ",chunks:" + timings.size());
        }
        helper.succeed();
    }
    private static Map<Long,ChunkAccess> featureChunks(ServerLevel level,int cx,int cz,int count){
        Map<Long,ChunkAccess> result=new LinkedHashMap<>();
        for(int z=0;z<count;z++)for(int x=0;x<count;x++){
            var chunk=level.getChunk(cx+x,cz+z,ChunkStatus.FEATURES,true);
            if(chunk==null||!chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES))throw new AssertionError("Supplemental owner did not reach FEATURES");
            result.put(ChunkPos.asLong(cx+x,cz+z),chunk);
        }
        return result;
    }
    private static List<int[]> treeWindowOrigins(){
        List<int[]> result=new ArrayList<>();
        // Keep the original64 probes in exactly their historical order.
        for(int index=0;index<64;index++)result.add(new int[]{-32+(index%8)*8,-32+(index/8)*8});
        // Append the192 surrounding grid cells; do not reinterpret the old indices with %16.
        for(int z=-8;z<8;z++)for(int x=-8;x<8;x++){
            if(x>=-4&&x<4&&z>=-4&&z<4)continue;
            result.add(new int[]{x*8,z*8});
        }
        if(result.size()!=256)throw new AssertionError("Bounded tree-discovery schedule drifted");
        return result;
    }
    private static List<Map<String,Object>> treeWindows(ServerLevel level,NoiseBasedChunkGenerator generator,
            net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain terrain,int primaryX,int primaryZ,int primarySize,long seed,
            int treeChunks,int treeWindows,Map<String,Object> selection,Path output) throws Exception{
        List<Map<String,Object>> result=new ArrayList<>(),attempts=new ArrayList<>();
        Map<String,Integer> rejections=new LinkedHashMap<>();
        int generated=0;String stopReason="candidate-origin-bound";
        selection.put("algorithm","baseline-only-actual-grove-v1");selection.put("mode","baseline-discovery");
        selection.put("seed",seed);selection.put("requested_windows",treeWindows);
        selection.put("candidate_origin_bound",256);selection.put("actual_features_window_bound",16);
        selection.put("schedule","Original8x8 grid (-32..24, step8) first; then16x16 grid (-64..56, step8) excluding original grid, row major");
        selection.put("eligibility","Original raw grove/terrain/biome/placement prefilter, then actual FEATURES gap_eligible_columns>0; successful tree count never selects a window");
        selection.put("attempts",attempts);selection.put("rejection_counts",rejections);
        try{
            List<int[]> origins=treeWindowOrigins();
            for(int index=0;index<origins.size();index++){
                if(result.size()==treeWindows){stopReason="requested-window-count";break;}
                int cx=origins.get(index)[0],cz=origins.get(index)[1];
                Map<String,Object> attempt=new LinkedHashMap<>();attempt.put("index",index);
                attempt.put("chunk_origin",List.of(cx,cz));attempt.put("chunks_per_side",treeChunks);attempts.add(attempt);
                String reason=treeWindowRejection(level,generator,terrain,primaryX,primaryZ,primarySize,seed,treeChunks,cx,cz);
                if(reason!=null){attempt.put("outcome","rejected");attempt.put("reason",reason);rejections.merge(reason,1,Integer::sum);continue;}
                if(generated==16){attempt.put("outcome","not-generated");attempt.put("reason","actual-features-window-bound");stopReason="actual-features-window-bound";break;}
                generated++;attempt.put("outcome","generation-started");
                var observed=treeSurvey(level,featureChunks(level,cx,cz,treeChunks),cx,cz,treeChunks,seed,"supplemental-tree-"+(result.size()+1));
                attempt.put("observed_window",observed);
                if(((Number)observed.get("gap_eligible_columns")).intValue()==0){
                    attempt.put("outcome","rejected");attempt.put("reason","no-actual-grove-eligible-columns");
                    rejections.merge("no-actual-grove-eligible-columns",1,Integer::sum);continue;
                }
                // Retain zero-tree windows when grove-eligible columns exist: successful placement is evidence, never a selector.
                result.add(observed);attempt.put("outcome","selected");attempt.put("selected_index",result.size()-1);
            }
            if(result.size()==treeWindows)stopReason="requested-window-count";
        }catch(Exception|AssertionError failure){
            stopReason="native-discovery-failure";selection.put("failure",failure.getClass().getSimpleName()+": "+failure.getMessage());
            throw failure;
        }finally{
            selection.put("examined_origins",attempts.size());selection.put("actual_features_windows",generated);
            selection.put("selected_windows",result.size());selection.put("stop_reason",stopReason);
            selection.put("status",result.size()==treeWindows?"COMPLETE":"INCOMPLETE");
            // Persist all attempted observations even if discovery or native generation fails.
            writeTreeDiscovery(output,selection);
        }
        if(result.size()!=treeWindows)throw new AssertionError("Bounded baseline grove discovery found "+result.size()+"/"+treeWindows
                +" actual windows after "+attempts.size()+"/256 origins and "+generated+"/16 FEATURES windows; see tree-discovery trace");
        return result;
    }
    private static String treeWindowRejection(ServerLevel level,NoiseBasedChunkGenerator generator,
            net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain terrain,int primaryX,int primaryZ,int primarySize,
            long seed,int treeChunks,int cx,int cz){
        if(cx<primaryX+primarySize&&cx+treeChunks>primaryX&&cz<primaryZ+primarySize&&cz+treeChunks>primaryZ)return "primary-overlap";
        int x=cx*16+16,z=cz*16+16;
        if(SceneryClusters.groveRichness(seed,x,z)<.35)return "grove-richness-below-0.35";
        var cell=terrain.sampleRaw(x,z);
        if(cell.terrain.isSubmerged()||cell.terrain.isRiver()||cell.terrain.isLake())return "raw-wet-terrain";
        var biome=generator.getBiomeSource().getNoiseBiome(x>>2,(int)(cell.height*256)>>2,z>>2,level.getChunkSource().randomState().sampler());
        boolean burnt=biome.is(ResourceLocation.parse("cwi:smoldering_barren"));
        if(!burnt&&!biome.is(ResourceLocation.parse("cwi:forsaken_land")))return "unsupported-raw-biome";
        for(int dz=0;dz<treeChunks;dz++)for(int dx=0;dx<treeChunks;dx++)if(!SceneryClusters.candidates(seed,burnt?612509:156791,cx+dx,cz+dz,
                burnt?.28:.48,1,2,1,2,burnt?2:3,11,true).isEmpty())return null;
        return "no-placement-candidates";
    }
    private static void writeTreeDiscovery(Path output,Map<String,Object> selection) throws Exception{
        Path trace=output.resolveSibling(output.getFileName().toString()+".tree-discovery.json");
        if(Files.exists(trace))throw new AssertionError("Never overwrite a tree-discovery trace: "+trace);
        Files.createDirectories(trace.getParent());
        Files.writeString(trace,new GsonBuilder().setPrettyPrinting().create().toJson(selection)+"\n");
    }
    private static void validateTreeReference(JsonObject reference,long seed,int primaryX,int primaryZ,int primarySize,int treeChunks,int treeWindows){
        if(reference.get("seed").getAsLong()!=seed)throw new AssertionError("Tree reference seed differs");
        var selection=reference.getAsJsonObject("tree_selection");
        if(selection==null||!"baseline-only-actual-grove-v1".equals(selection.get("algorithm").getAsString())
                ||!"baseline-discovery".equals(selection.get("mode").getAsString())||!"COMPLETE".equals(selection.get("status").getAsString())
                ||selection.get("candidate_origin_bound").getAsInt()!=256||selection.get("actual_features_window_bound").getAsInt()!=16
                ||selection.get("seed").getAsLong()!=seed||selection.get("requested_windows").getAsInt()!=treeWindows
                ||selection.get("examined_origins").getAsInt()>256||selection.get("actual_features_windows").getAsInt()>16
                ||selection.get("actual_features_windows").getAsInt()<treeWindows
                ||selection.get("selected_windows").getAsInt()!=treeWindows)throw new AssertionError("Incomplete or unbounded baseline grove-discovery trace");
        var previous=reference.getAsJsonArray("tree_surveys");
        if(previous==null||previous.size()!=treeWindows+1)throw new AssertionError("Baseline must contain primary plus every requested supplemental window");
        List<int[]> rectangles=new ArrayList<>();
        for(int index=0;index<previous.size();index++){
            var window=previous.get(index).getAsJsonObject();var origin=window.getAsJsonArray("chunk_origin");
            int x=origin.get(0).getAsInt(),z=origin.get(1).getAsInt(),size=window.get("chunks_per_side").getAsInt();
            String label=index==0?"primary":"supplemental-tree-"+index;
            if(!label.equals(window.get("label").getAsString())||size!=(index==0?primarySize:treeChunks))throw new AssertionError("Baseline tree-window label/size differs");
            if(index==0&&(x!=primaryX||z!=primaryZ))throw new AssertionError("Baseline primary coordinates differ");
            if(index>0&&window.get("gap_eligible_columns").getAsInt()<=0)throw new AssertionError("Baseline supplemental window has no actual grove-eligible columns");
            for(int[] old:rectangles)if(x<old[0]+old[2]&&x+size>old[0]&&z<old[1]+old[2]&&z+size>old[1])throw new AssertionError("Baseline tree windows overlap");
            rectangles.add(new int[]{x,z,size});
        }
    }
    private static Map<String,Object> treeSurvey(ServerLevel level,Map<Long,ChunkAccess> chunks,int cx,int cz,int count,long seed,String label){
        int width=count*16;List<Map<String,Object>> anchors=new ArrayList<>();Set<Long> footprintColumns=new LinkedHashSet<>();
        Set<BlockPos> logBlocks=new LinkedHashSet<>(),observedRoots=new LinkedHashSet<>();
        for(int z=0;z<width;z++)for(int x=0;x<width;x++){
            int wx=cx*16+x,wz=cz*16+z;var chunk=chunks.get(ChunkPos.asLong(wx>>4,wz>>4));
            int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,x&15,z&15);
            int bottom=Math.max(chunk.getMinBuildHeight()+1,top-12);
            for(int y=top;y>=bottom;y--){
                var state=chunk.getBlockState(new BlockPos(wx,y,wz));if(!verticalSceneryLog(state))continue;
                String material=id(state.getBlock());
                while(y>=bottom&&chunk.getBlockState(new BlockPos(wx,y,wz)).equals(state))y--;
                int base=y+1;BlockPos support=new BlockPos(wx,y,wz);var ground=chunk.getBlockState(support);
                if(verticalSceneryLog(ground)||!ground.getFluidState().isEmpty()||ground.hasBlockEntity()
                        ||!ground.isFaceSturdy(chunk,support,Direction.UP))continue;
                var root=new BlockPos(wx,base,wz);if(observedRoots.contains(root))continue;
                var connected=standingFootprint(chunks,root,state.getBlock());
                int height=connected.stream().mapToInt(BlockPos::getY).max().orElse(base)-base+1;
                if(connected.size()<3||height<3)continue;
                observedRoots.add(root);
                List<List<Integer>> blocks=new ArrayList<>();Set<Long> treeFootprint=new LinkedHashSet<>();
                for(var pos:connected){
                    logBlocks.add(pos);blocks.add(List.of(pos.getX(),pos.getY(),pos.getZ()));
                    treeFootprint.add(ChunkPos.asLong(pos.getX(),pos.getZ()));
                }
                footprintColumns.addAll(treeFootprint);
                Map<String,Object> anchor=new LinkedHashMap<>();anchor.put("x",wx);anchor.put("y",base);anchor.put("z",wz);
                anchor.put("height",height);anchor.put("material",material);anchor.put("log_blocks",blocks);anchor.put("footprint_columns",treeFootprint.size());anchors.add(anchor);
            }
        }
        double largest=0;int eligible=0;List<Integer> center=List.of();
        for(int z=8;z<width-8;z++)for(int x=8;x<width-8;x++){
            int wx=cx*16+x,wz=cz*16+z;var chunk=chunks.get(ChunkPos.asLong(wx>>4,wz>>4));
            int y=chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,x&15,z&15);
            var biome=chunk.getNoiseBiome(wx>>2,y>>2,wz>>2);
            if(!(biome.is(ResourceLocation.parse("cwi:forsaken_land"))||biome.is(ResourceLocation.parse("cwi:smoldering_barren")))
                    ||!chunk.getBlockState(new BlockPos(wx,y,wz)).getFluidState().isEmpty()||SceneryClusters.groveRichness(seed,wx,wz)<=.15)continue;
            eligible++;double distance=Double.POSITIVE_INFINITY;
            for(var tree:anchors)distance=Math.min(distance,Math.hypot(wx-((Number)tree.get("x")).intValue(),wz-((Number)tree.get("z")).intValue()));
            // A tree beyond the surveyed boundary could be this close. Clipping avoids inventing an unobserved edge gap.
            distance=Math.min(distance,Math.min(Math.min(x+1,width-x),Math.min(z+1,width-z)));
            if(distance>largest){largest=distance;center=List.of(wx,wz);}
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("label",label);result.put("chunk_origin",List.of(cx,cz));result.put("chunks_per_side",count);
        result.put("standing_trunks",anchors.size());result.put("standing_log_blocks",logBlocks.size());result.put("standing_footprint_columns",footprintColumns.size());
        result.put("anchors",anchors);result.put("gap_eligible_columns",eligible);result.put("largest_accidental_gap_radius",largest);result.put("largest_gap_center",center);
        result.put("tree_definition","Supported root with at least3 same-block vertical-axis logs and overall height>=3; bounded connected footprint within radius8 and12blocks high. Connectivity uses six faces plus vertical-horizontal edge steps used by vanilla forking trunks. Same detector for baseline and candidate; fallen horizontal-axis logs excluded.");
        result.put("gap_definition","Largest distance to an observed supported standing trunk on dry Forsaken/Smoldering interior columns with shared grove richness>0.15; eight-block sampling margin, radius clipped at observed boundary. Intentional quiet clearings and water are excluded; zero-tree windows still report a bounded gap.");
        return result;
    }
    private static Set<BlockPos> standingFootprint(Map<Long,ChunkAccess> chunks,BlockPos root,net.minecraft.world.level.block.Block material){
        Set<BlockPos> found=new LinkedHashSet<>(),visited=new LinkedHashSet<>();ArrayDeque<BlockPos> pending=new ArrayDeque<>();
        pending.add(root);
        while(!pending.isEmpty()){
            var pos=pending.remove();
            if(Math.abs(pos.getX()-root.getX())>8||Math.abs(pos.getZ()-root.getZ())>8
                    ||pos.getY()<root.getY()||pos.getY()>=root.getY()+12||!visited.add(pos))continue;
            var chunk=chunks.get(ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4));if(chunk==null)continue;
            var state=chunk.getBlockState(pos);if(!state.is(material)||!verticalSceneryLog(state))continue;
            found.add(pos);
            for(Direction direction:Direction.values())pending.add(pos.relative(direction));
            // Vanilla ForkingTrunkPlacer bends by moving sideways and upward in the same iteration.
            // These logs touch along an edge, so face-only traversal would lose the entire bent crown.
            for(Direction direction:Direction.Plane.HORIZONTAL){
                pending.add(pos.relative(direction).above());pending.add(pos.relative(direction).below());
            }
        }
        return found;
    }
    private static boolean verticalSceneryLog(net.minecraft.world.level.block.state.BlockState state){
        String name=id(state.getBlock());return (name.equals("kubejs:ash_log")||name.equals("kubejs:burnt_log"))
                &&state.hasProperty(RotatedPillarBlock.AXIS)&&state.getValue(RotatedPillarBlock.AXIS)==Direction.Axis.Y;
    }
    private static Map<String,Object> treeTotals(List<Map<String,Object>> windows){
        int trees=0,footprint=0,logs=0;double gap=0;
        for(var window:windows){trees+=((Number)window.get("standing_trunks")).intValue();footprint+=((Number)window.get("standing_footprint_columns")).intValue();
            logs+=((Number)window.get("standing_log_blocks")).intValue();gap=Math.max(gap,((Number)window.get("largest_accidental_gap_radius")).doubleValue());}
        return Map.of("standing_trunks",trees,"standing_footprint_columns",footprint,"standing_log_blocks",logs,"largest_accidental_gap_radius",gap,"windows",windows.size());
    }
    private static Map<String,Object> compareTrees(List<Map<String,Object>> windows,Map<String,Object> totals,JsonObject reference,int profile){
        var baseline=reference.getAsJsonObject("tree_totals");var previous=reference.getAsJsonArray("tree_surveys");
        if(previous.size()!=windows.size())throw new AssertionError("Unpaired tree windows");
        double oldTrees=baseline.get("standing_trunks").getAsDouble(),oldFootprint=baseline.get("standing_footprint_columns").getAsDouble();
        double treeRatio=oldTrees>0?((Number)totals.get("standing_trunks")).doubleValue()/oldTrees:0;
        double footprintRatio=oldFootprint>0?((Number)totals.get("standing_footprint_columns")).doubleValue()/oldFootprint:0;
        boolean gapPass=true;
        for(int i=0;i<windows.size();i++){
            var old=previous.get(i).getAsJsonObject();var now=windows.get(i);
            if(!new GsonBuilder().create().toJson(now.get("chunk_origin")).equals(old.get("chunk_origin").toString())
                    ||((Number)now.get("chunks_per_side")).intValue()!=old.get("chunks_per_side").getAsInt())throw new AssertionError("Tree window coordinates drifted");
            gapPass&=((Number)now.get("largest_accidental_gap_radius")).doubleValue()<=old.get("largest_accidental_gap_radius").getAsDouble()+1E-9;
        }
        // Profile13 restores the author's wider vanilla fork; silhouette size intentionally changes.
        // Count supported root anchors and accidental gaps as coverage, and report footprint separately.
        boolean pass=oldTrees>0&&oldFootprint>0&&treeRatio>=.9&&treeRatio<=1.1
                &&(profile>=13||(footprintRatio>=.9&&footprintRatio<=1.1))&&gapPass;
        return Map.of("result",pass?"PASS":"FAIL","standing_trunk_ratio",treeRatio,"standing_footprint_ratio",footprintRatio,
                "largest_gap_did_not_increase",gapPass,"baseline_standing_trunks",oldTrees,"baseline_standing_footprint_columns",oldFootprint,
                "coverage_min",.9,"coverage_max",1.1);
    }
    private static int[] riverWindow(net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain terrain,int centerX,int centerZ){
        int examined=0;
        for(var reach:terrain.hydrologySnapshot(centerX,centerZ).reaches()){
            if(++examined>64)break;if(reach.points().isEmpty())continue;var point=reach.points().get(reach.points().size()/2);
            int x=(int)Math.round(point.x()),z=(int)Math.round(point.z());var sample=terrain.watershedSample(x,z);
            if(sample.wet()&&!sample.basin()&&!sample.island()&&Math.abs(sample.distance())<=sample.width())return new int[]{Math.floorDiv(x,16)-1,Math.floorDiv(z,16)-1};
        }
        return null;
    }
    private static Map<String,Object> surfaceView(Map<Long,ChunkAccess> chunks,int cx,int cz,int count,String label){
        int width=count*16;int[][] top=new int[width][width],material=new int[width][width];
        Map<String,Integer> indices=new LinkedHashMap<>();List<String> palette=new ArrayList<>();int water=0;
        for(int z=0;z<width;z++)for(int x=0;x<width;x++){
            int wx=cx*16+x,wz=cz*16+z;var chunk=chunks.get(ChunkPos.asLong(wx>>4,wz>>4));
            int y=chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,x&15,z&15);top[z][x]=y;
            var state=chunk.getBlockState(new BlockPos(wx,y,wz));material[z][x]=palette(indices,palette,id(state.getBlock()));
            if(!state.getFluidState().isEmpty())water++;
        }
        return Map.of("label",label,"chunk_origin",List.of(cx,cz),"chunks_per_side",count,"world_surface_wg",top,"palette",palette,"top_material_indices",material,"water_columns",water);
    }
    private static String id(net.minecraft.world.level.block.Block block) { return BuiltInRegistries.BLOCK.getKey(block).toString(); }
    private static int palette(Map<String,Integer> index, List<String> values, String id) {
        Integer known = index.get(id);
        if (known != null) return known;
        int added = values.size(); values.add(id); index.put(id, added); return added;
    }
}

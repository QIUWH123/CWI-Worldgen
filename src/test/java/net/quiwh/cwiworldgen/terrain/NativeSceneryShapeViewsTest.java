package net.quiwh.cwiworldgen.terrain;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainCategory;

/** Opt-in data views of natural FEATURES chunks, in one fresh disposable server per seed. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeSceneryShapeViewsTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 24000)
    public static void actualShelfMaterialsAndRockFacesExportGeneratedBlockViews(GameTestHelper helper) throws Exception {
        if (!Boolean.getBoolean("cwi.worldgen.sceneryShapeViews")) { helper.succeed(); return; }
        if (!(helper.getLevel().getServer() instanceof GameTestServer))
            throw new AssertionError("Scenery views require a disposable GameTestServer");
        long seed = Long.parseLong(System.getProperty("cwi.worldgen.sceneryShapeViewSeed", "42"));
        if (!Set.of(0L, 42L, 123456789L, -987654321L).contains(seed))
            throw new AssertionError("Unsupported scenery-view fixture seed");
        Path output = Path.of(System.getProperty("cwi.worldgen.sceneryShapeViewOutput",
                "native-scenery-shape-views-" + seed + ".json")).toAbsolutePath();
        if (Files.exists(output)) throw new AssertionError("Refusing to overwrite scenery-view artifact: " + output);
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:scenery_shapes_wasteland_test")));
        if (level == null) throw new AssertionError("Missing current-profile native wasteland dimension");
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        if (TerrainGenerationProfile.fromRouter(generator.generatorSettings().value().noiseRouter()) != 13)
            throw new AssertionError("Scenery views require production profile13");
        try (var scope = new NativeGenerationSeedScope(level, generator, seed)) {
            var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
            if (terrain == null || terrain.generationVersion() != 13 || level.getSeed() != seed)
                throw new AssertionError("Native scenery view seed/profile substitution failed");
            var selection = select(terrain, seed);
            List<Map<String, Object>> views = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            if (selection.shelf != null) {
                var view = export(level, selection.shelf, "continental-shelf-materials");
                view.put("raw_selection", selection.shelf);
                int water = ((Number) view.get("water_columns")).intValue();
                boolean enoughWater = water >= 512;
                view.put("selection_result", enoughWater ? "PASS" : "INSUFFICIENT_ACTUAL_WATER");
                if (!enoughWater) missing.add("Shelf candidate had only " + water + " actual surface-water columns; retained unmodified diagnostic view");
                views.add(view);
            } else missing.add("No non-island submerged shelf candidate in the bounded64-coordinate raw screen");
            if (selection.cliff != null) {
                var view = export(level, selection.cliff, "native-rock-face");
                view.put("raw_selection", selection.cliff);
                int range = ((Number) view.get("solid_height_range")).intValue();
                view.put("selection_result", range >= 12 ? "PASS" : "INSUFFICIENT_ACTUAL_RELIEF");
                if (range < 12) missing.add("Rock candidate had only " + range + " blocks of actual surface relief; retained diagnostic view");
                views.add(view);
            } else missing.add("No steep non-island rocky candidate in the bounded raw screen");
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("seed", seed); report.put("profile", 13); report.put("stage", "FEATURES");
            report.put("method", "Actual native ChunkStatus.FEATURES owners including structures, noise, surface, caves and all decoration. One fresh disposable server per seed. No handcrafted terrain or direct feature calls.");
            report.put("view_method", "64x64 actual top and solid-floor block IDs/heights plus a center vertical block section; data views, not in-game camera screenshots. No timing claim.");
            report.put("selection_method", "One64-coordinate8x8 raw base screen at1024-block spacing inside +/-3584; no hydrology discovery. Non-island ocean depth4..48 for shelf. At most8 rocky candidates receive4 slope probes each. Seed42 cliff uses the fixed native fixture spanning tiles12/13,17, with one extra raw metadata query.");
            report.put("coarse_candidates", selection.examined); report.put("rock_slope_candidates", selection.slopeCandidates);
            report.put("selected_views", views); report.put("missing_evidence", missing);
            report.put("result", missing.isEmpty() ? "PASS" : "INCOMPLETE");
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
            System.out.println("CWI_NATIVE_SCENERY_SHAPE_VIEWS=" + output + ",seed:" + seed
                    + ",views:" + views.size() + ",missing:" + missing.size());
        }
        helper.succeed();
    }

    private static Selection select(ReferenceTerrain terrain, long seed) {
        Candidate shelf = null, cliff = null;
        double shelfScore = Double.POSITIVE_INFINITY, cliffScore = .35;
        int examined = 0, slopes = 0;
        var cell = new Cell(); var neighbor = new Cell();
        for (int iz = 0; iz < 8; iz++) for (int ix = 0; ix < 8; ix++) {
            int x = -3584 + ix * 1024, z = -3584 + iz * 1024;
            terrain.sampleBaseInto(cell, x, z); examined++;
            if (cell.cwiIsland || cell.terrain == null || cell.terrain.getCategory() == TerrainCategory.ISLAND) continue;
            double height = cell.height * terrain.levels().worldHeight;
            double depth = terrain.levels().waterY - height;
            if ((cell.terrain.isShallowOcean() || cell.terrain.isDeepOcean()) && depth >= 4 && depth <= 48) {
                double score = Math.abs(depth - 24);
                if (score < shelfScore) { shelfScore = score; shelf = new Candidate(x, z, cell.terrain.getName(), height, depth); }
            }
            if (seed == 42 || slopes >= 8 || !cell.terrain.isMountain() || cell.terrain.isVolcano()
                    || cell.erosion >= .25 || height < terrain.levels().waterY + 24) continue;
            slopes++;
            terrain.sampleBaseInto(neighbor, x - 8, z); double west = neighbor.height;
            terrain.sampleBaseInto(neighbor, x + 8, z); double east = neighbor.height;
            terrain.sampleBaseInto(neighbor, x, z - 8); double north = neighbor.height;
            terrain.sampleBaseInto(neighbor, x, z + 8); double south = neighbor.height;
            double slope = (Math.abs(east - west) + Math.abs(south - north)) * terrain.levels().worldHeight / 16;
            if (slope > cliffScore) { cliffScore = slope; cliff = new Candidate(x, z, cell.terrain.getName(), height, slope); }
        }
        if (seed == 42) {
            terrain.sampleBaseInto(cell, 1664, 2240);
            cliff = new Candidate(1664, 2240, cell.terrain.getName(), cell.height * terrain.levels().worldHeight, 0);
        }
        return new Selection(shelf, cliff, examined, slopes);
    }

    private static Map<String, Object> export(ServerLevel level, Candidate candidate, String label) {
        int cx = Math.floorDiv(candidate.x, 16) - 2, cz = Math.floorDiv(candidate.z, 16) - 2;
        Map<Long, ChunkAccess> chunks = new LinkedHashMap<>();
        for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
            var chunk = level.getChunk(cx + x, cz + z, ChunkStatus.FEATURES, true);
            if (chunk == null || !chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES))
                throw new AssertionError("Scenery owner did not reach FEATURES");
            chunks.put(ChunkPos.asLong(cx + x, cz + z), chunk);
        }
        int[][] topY = new int[64][64], floorY = new int[64][64], topIds = new int[64][64], floorIds = new int[64][64];
        int[][] waterDepth = new int[64][64], surfaceWg = new int[64][64], oceanWg = new int[64][64];
        List<String> palette = new ArrayList<>(); Map<String, Integer> indices = new LinkedHashMap<>();
        Map<String, Integer> wetFloorCounts = new LinkedHashMap<>();
        int water = 0, dry = 0, minFloor = Integer.MAX_VALUE, maxFloor = Integer.MIN_VALUE;
        int minSlice = Integer.MAX_VALUE, maxSlice = Integer.MIN_VALUE;
        var pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) {
            int wx = cx * 16 + x, wz = cz * 16 + z;
            var chunk = chunks.get(ChunkPos.asLong(wx >> 4, wz >> 4));
            surfaceWg[z][x] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
            oceanWg[z][x] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15);
            int top = Math.min(chunk.getMaxBuildHeight() - 1, chunk.getHighestSectionPosition() + 15);
            while (top > chunk.getMinBuildHeight() && chunk.getBlockState(pos.set(wx, top, wz)).isAir()) top--;
            var topState = chunk.getBlockState(pos.set(wx, top, wz));
            topY[z][x] = top; topIds[z][x] = palette(indices, palette, topState);
            int floor = top;
            if (topState.getFluidState().is(Fluids.WATER)) {
                water++;
                while (floor > chunk.getMinBuildHeight()
                        && chunk.getBlockState(pos.set(wx, floor, wz)).getFluidState().is(Fluids.WATER)) floor--;
                waterDepth[z][x] = top - floor;
            } else if (topState.getFluidState().isEmpty()) dry++;
            // Ignore non-solid plants/cover in the exported solid-floor height and material.
            while (floor > chunk.getMinBuildHeight()) {
                var state = chunk.getBlockState(pos.set(wx, floor, wz));
                if (!state.isAir() && state.getFluidState().isEmpty() && state.blocksMotion()) break;
                floor--;
            }
            floorY[z][x] = floor;
            var floorState = chunk.getBlockState(pos.set(wx, floor, wz));
            floorIds[z][x] = palette(indices, palette, floorState);
            if (waterDepth[z][x] > 0) wetFloorCounts.merge(name(floorState), 1, Integer::sum);
            minFloor = Math.min(minFloor, floor); maxFloor = Math.max(maxFloor, floor);
            if (z == 32) { minSlice = Math.min(minSlice, floor - 10); maxSlice = Math.max(maxSlice, top + 5); }
        }
        minSlice = Math.max(level.getMinBuildHeight(), minSlice);
        maxSlice = Math.min(level.getMaxBuildHeight() - 1, maxSlice);
        int[][] slice = new int[maxSlice - minSlice + 1][64];
        for (int y = maxSlice; y >= minSlice; y--) for (int x = 0; x < 64; x++) {
            int wx = cx * 16 + x, wz = cz * 16 + 32;
            var chunk = chunks.get(ChunkPos.asLong(wx >> 4, wz >> 4));
            slice[maxSlice - y][x] = palette(indices, palette, chunk.getBlockState(pos.set(wx, y, wz)));
        }
        int floorTransitions = 0;
        for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) {
            if (x > 0 && floorIds[z][x] != floorIds[z][x - 1]) floorTransitions++;
            if (z > 0 && floorIds[z][x] != floorIds[z - 1][x]) floorTransitions++;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("label", label); view.put("chunk_origin", List.of(cx, cz)); view.put("chunk_dimensions", List.of(4, 4));
        view.put("block_width", 64); view.put("block_depth", 64); view.put("palette", palette);
        view.put("actual_top_y", topY); view.put("top_material_indices", topIds);
        view.put("actual_solid_floor_y", floorY); view.put("floor_material_indices", floorIds);
        view.put("world_surface_wg", surfaceWg); view.put("ocean_floor_wg", oceanWg);
        view.put("water_depth", waterDepth); view.put("water_columns", water); view.put("dry_columns", dry);
        view.put("wet_floor_material_counts", wetFloorCounts); view.put("solid_height_range", maxFloor - minFloor);
        view.put("floor_material_neighbor_transitions", floorTransitions); view.put("floor_material_neighbor_edges", 8064);
        view.put("center_slice_axis", "x"); view.put("center_slice_fixed_coordinate", cz * 16 + 32);
        view.put("center_slice_min_y", minSlice); view.put("center_slice_max_y", maxSlice); view.put("center_slice_indices", slice);
        return view;
    }

    private static String name(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }
    private static int palette(Map<String, Integer> indices, List<String> values, BlockState state) {
        String name = name(state); Integer known = indices.get(name);
        if (known != null) return known;
        int index = values.size(); values.add(name); indices.put(name, index); return index;
    }
    private record Candidate(int x, int z, String terrain, double rawHeight, double selectionValue) {}
    private record Selection(Candidate shelf, Candidate cliff, int examined, int slopeCandidates) {}
}

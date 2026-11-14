package net.quiwh.cwiworldgen.terrain;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.placement.SceneryClusters;

/** Opt-in natural burnt grove comparison; fresh disposable server per seed, outside performance evidence. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeBurntGroveCoverageTest {
    private static final ResourceLocation SMOLDERING = ResourceLocation.parse("cwi:smoldering_barren");
    private static final ResourceLocation BURNT = ResourceLocation.parse("kubejs:burnt_log");
    private static final int WIDTH = 64;

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 24000)
    public static void pairedNaturalSmolderingGroveReportsActualBurntRootsAndShapes(GameTestHelper helper) throws Exception {
        if (!Boolean.getBoolean("cwi.worldgen.burntGroveCoverage")) { helper.succeed(); return; }
        if (!(helper.getLevel().getServer() instanceof GameTestServer)) throw new AssertionError("Use a fresh disposable GameTestServer");
        long seed = Long.parseLong(System.getProperty("cwi.worldgen.burntGroveSeed", "0"));
        if (seed != 0 && seed != 42) throw new AssertionError("Bounded burnt-grove seeds are0and42");
        Path output = Path.of(System.getProperty("cwi.worldgen.burntGroveOutput", "native-burnt-grove-" + seed + ".json")).toAbsolutePath();
        if (Files.exists(output)) throw new AssertionError("Never overwrite burnt-grove evidence");
        ServerLevel current = dimension(helper, "scenery_shapes_wasteland_test");
        ServerLevel previous = dimension(helper, "coordinated_wasteland_test");
        var currentGenerator = (NoiseBasedChunkGenerator) current.getChunkSource().getGenerator();
        var previousGenerator = (NoiseBasedChunkGenerator) previous.getChunkSource().getGenerator();
        if (TerrainGenerationProfile.fromRouter(currentGenerator.generatorSettings().value().noiseRouter()) != 13
                || TerrainGenerationProfile.fromRouter(previousGenerator.generatorSettings().value().noiseRouter()) != 12)
            throw new AssertionError("Burnt grove comparison requires frozen profile12 and current profile13 dimensions");
        Selection selected; Observation modern, legacy;
        try (var scope = new NativeGenerationSeedScope(current, currentGenerator, seed)) {
            selected = select(current, currentGenerator, seed);
            modern = observe(current, selected.cx, selected.cz, seed, 13);
        }
        try (var scope = new NativeGenerationSeedScope(previous, previousGenerator, seed)) {
            legacy = observe(previous, selected.cx, selected.cz, seed, 12);
        }
        Set<Long> common = new LinkedHashSet<>(legacy.gapColumns); common.retainAll(modern.gapColumns);
        double oldGap = gap(legacy.roots, common, selected.cx, selected.cz), newGap = gap(modern.roots, common, selected.cx, selected.cz);
        Map<String, Object> comparison = new LinkedHashMap<>();
        Double ratio = legacy.roots.isEmpty() ? null : modern.roots.size() / (double) legacy.roots.size();
        Double footprintRatio = legacy.footprint.isEmpty() ? null : modern.footprint.size() / (double) legacy.footprint.size();
        comparison.put("standing_trunk_ratio", ratio); comparison.put("standing_footprint_ratio", footprintRatio);
        comparison.put("common_gap_eligible_columns", common.size()); comparison.put("profile12_largest_gap", oldGap);
        comparison.put("profile13_largest_gap", newGap); comparison.put("count_within_90_110_percent", ratio != null && ratio >= .9 && ratio <= 1.1);
        comparison.put("footprint_within_90_110_percent", footprintRatio != null && footprintRatio >= .9 && footprintRatio <= 1.1);
        comparison.put("largest_gap_did_not_increase", !common.isEmpty() && newGap <= oldGap + 1e-9);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("result", "COMPLETED"); report.put("seed", seed); report.put("chunk_origin", List.of(selected.cx, selected.cz));
        report.put("chunks_per_side", 4); report.put("discovery_coarse_points", 128); report.put("selected_planned_candidates", selected.candidates);
        report.put("selection_method", "Highest unchanged planned burnt candidate count among128fixed coarse centers within+-4096; center must be dry Smoldering with grove richness>0.15. Selected before generating either actual window. No actual-output retry or window cherry-picking.");
        report.put("method", "Same seed and4x4owner coordinates in native profile12 and13 dimensions; real FEATURES pipeline. Run one seed per fresh JVM.32explicit owner requests plus normal status dependencies; no timing claim.");
        report.put("detector", "Supported root; same-block vertical-axis burnt logs connected by6faces+8vertical-horizontal edge steps; radius8,height12; >=3logs and >=3overall height. Roots counted separately; overlapping component footprints unioned. Fallen horizontal logs excluded. Same detector both profiles.");
        report.put("limitations", "Finite selected Smoldering grove; current artifact's saved-profile12 branch, not a second historical JAR. Gap uses intersection of dry Smoldering grove columns and8block margins, clipped to view boundary. Ratios are measurements, not an asserted acceptance pass. Nearby touching same-material trees can share observed footprint blocks.");
        report.put("profile12", legacy.report); report.put("profile13", modern.report); report.put("comparison", comparison);
        Files.createDirectories(output.getParent()); Files.writeString(output, new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report) + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println("CWI_BURNT_GROVE=seed:" + seed + ",owner:" + selected.cx + "," + selected.cz
                + ",legacy:" + legacy.roots.size() + ",current:" + modern.roots.size() + ",ratio:" + ratio + ",output:" + output);
        helper.succeed();
    }

    private static ServerLevel dimension(GameTestHelper helper, String name) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("cwi_worldgen:" + name)));
        if (level == null) throw new AssertionError("Missing burnt grove dimension " + name); return level;
    }
    private record Selection(int cx, int cz, int candidates) {}
    private static Selection select(ServerLevel level, NoiseBasedChunkGenerator generator, long seed) {
        var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
        Selection best = null;
        for (int index = 0; index < 128; index++) {
            int x = -3840 + (index % 16) * 512, z = -3584 + (index / 16) * 1024;
            var cell = terrain.sampleRaw(x, z);
            if (cell.terrain == null || cell.terrain.isSubmerged() || cell.terrain.isRiver() || cell.terrain.isLake()
                    || cell.terrain.isWetland() || SceneryClusters.groveRichness(seed, x, z) <= .15) continue;
            int y = terrain.levels().scale(cell.height);
            if (!generator.getBiomeSource().getNoiseBiome(x >> 2, y >> 2, z >> 2, level.getChunkSource().randomState().sampler()).is(SMOLDERING)) continue;
            int cx = Math.floorDiv(x, 16) - 2, cz = Math.floorDiv(z, 16) - 2, candidates = 0;
            for (int dz = 0; dz < 4; dz++) for (int dx = 0; dx < 4; dx++)
                candidates += SceneryClusters.candidates(seed, 612509, cx + dx, cz + dz, .28, 1, 2, 1, 2, 2, 12, true).size();
            if (best == null || candidates > best.candidates) best = new Selection(cx, cz, candidates);
        }
        if (best == null) throw new AssertionError("No dry Smoldering grove within128fixed coarse samples");
        return best;
    }
    private record Observation(Map<String, Object> report, List<BlockPos> roots, Set<Long> footprint, Set<Long> gapColumns) {}
    private static Observation observe(ServerLevel level, int cx, int cz, long seed, int profile) {
        Map<Long, ChunkAccess> chunks = new LinkedHashMap<>();
        for (int dz = 0; dz < 4; dz++) for (int dx = 0; dx < 4; dx++) {
            var chunk = level.getChunk(cx + dx, cz + dz, ChunkStatus.FEATURES, true);
            if (chunk == null || !chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) throw new AssertionError("Incomplete burnt grove owner");
            chunks.put(chunk.getPos().toLong(), chunk);
        }
        Set<BlockPos> rootsSet = new LinkedHashSet<>(), logs = new LinkedHashSet<>();
        Set<Long> footprint = new LinkedHashSet<>(), gapColumns = new LinkedHashSet<>();
        List<Map<String, Object>> anchors = new ArrayList<>(); List<String> palette = new ArrayList<>(); Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        int[][] tops = new int[WIDTH][WIDTH], materials = new int[WIDTH][WIDTH]; int smoldering = 0;
        for (int z = 0; z < WIDTH; z++) for (int x = 0; x < WIDTH; x++) {
            int wx = cx * 16 + x, wz = cz * 16 + z; var chunk = chunks.get(ChunkPos.asLong(wx >> 4, wz >> 4));
            int top = Math.min(chunk.getMaxBuildHeight() - 1, chunk.getHighestSectionPosition() + 15);
            while (top > chunk.getMinBuildHeight() && chunk.getBlockState(new BlockPos(wx, top, wz)).isAir()) top--;
            tops[z][x] = top; var topState = chunk.getBlockState(new BlockPos(wx, top, wz));
            String id = BuiltInRegistries.BLOCK.getKey(topState.getBlock()).toString();
            if (!paletteIndex.containsKey(id)) { paletteIndex.put(id, palette.size()); palette.add(id); } materials[z][x] = paletteIndex.get(id);
            boolean biome = chunk.getNoiseBiome(wx >> 2, top >> 2, wz >> 2).is(SMOLDERING); if (biome) smoldering++;
            if (biome && x >= 8 && z >= 8 && x < WIDTH - 8 && z < WIDTH - 8 && topState.getFluidState().isEmpty()
                    && SceneryClusters.groveRichness(seed, wx, wz) > .15) gapColumns.add(ChunkPos.asLong(wx, wz));
            for (int y = top; y >= Math.max(chunk.getMinBuildHeight() + 1, top - 12); y--) {
                BlockPos root = new BlockPos(wx, y, wz); if (!vertical(chunk.getBlockState(root))) continue;
                var below = root.below(); var support = chunk.getBlockState(below);
                if (vertical(support) || support.hasBlockEntity() || !support.getFluidState().isEmpty()
                        || !support.isFaceSturdy(chunk, below, Direction.UP) || rootsSet.contains(root)) continue;
                var connected = component(chunks, root); int height = connected.stream().mapToInt(BlockPos::getY).max().orElse(y) - y + 1;
                if (connected.size() < 3 || height < 3) continue;
                rootsSet.add(root); logs.addAll(connected); List<List<Integer>> blocks = new ArrayList<>(); Set<Long> treeColumns = new LinkedHashSet<>();
                for (var pos : connected) { blocks.add(List.of(pos.getX(), pos.getY(), pos.getZ())); treeColumns.add(ChunkPos.asLong(pos.getX(), pos.getZ())); }
                footprint.addAll(treeColumns); anchors.add(Map.of("x", wx, "y", y, "z", wz, "height", height,
                        "material", BURNT.toString(), "log_blocks", blocks, "footprint_columns", treeColumns.size()));
            }
        }
        Map<String, Object> report = new LinkedHashMap<>(); report.put("profile", profile); report.put("standing_trunks", rootsSet.size());
        report.put("standing_log_blocks", logs.size()); report.put("standing_footprint_columns", footprint.size()); report.put("anchors", anchors);
        report.put("smoldering_columns", smoldering); report.put("gap_eligible_columns", gapColumns.size()); report.put("palette", palette);
        report.put("top_material_indices", materials); report.put("actual_top_y", tops);
        return new Observation(report, List.copyOf(rootsSet), footprint, gapColumns);
    }
    private static boolean vertical(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(BURNT) && state.hasProperty(RotatedPillarBlock.AXIS)
                && state.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.Y;
    }
    private static Set<BlockPos> component(Map<Long, ChunkAccess> chunks, BlockPos root) {
        Set<BlockPos> found = new LinkedHashSet<>(), seen = new LinkedHashSet<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(root);
        while (!queue.isEmpty()) {
            var pos = queue.remove();
            if (Math.abs(pos.getX() - root.getX()) > 8 || Math.abs(pos.getZ() - root.getZ()) > 8
                    || pos.getY() < root.getY() || pos.getY() >= root.getY() + 12 || !seen.add(pos)) continue;
            var chunk = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            if (chunk == null || !vertical(chunk.getBlockState(pos))) continue;
            found.add(pos); for (Direction d : Direction.values()) queue.add(pos.relative(d));
            for (Direction d : Direction.Plane.HORIZONTAL) { queue.add(pos.relative(d).above()); queue.add(pos.relative(d).below()); }
        }
        return found;
    }
    private static double gap(List<BlockPos> roots, Set<Long> columns, int cx, int cz) {
        double largest = 0;
        for (long packed : columns) {
            int x = ChunkPos.getX(packed), z = ChunkPos.getZ(packed), localX = x - cx * 16, localZ = z - cz * 16;
            double nearest = Math.min(Math.min(localX + 1, WIDTH - localX), Math.min(localZ + 1, WIDTH - localZ));
            for (var root : roots) nearest = Math.min(nearest, Math.hypot(x - root.getX(), z - root.getZ()));
            largest = Math.max(largest, nearest);
        }
        return largest;
    }
}

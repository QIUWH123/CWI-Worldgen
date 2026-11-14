package net.quiwh.cwiworldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Independent terrain sampling: metrics describe these seeds, not universal balance. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeWastelandWorldgenTest {
    private static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(-64, 448);

    private static WorldPreset preset(GameTestHelper helper, String name) throws Exception {
        var resource = helper.getLevel().getServer().getResourceManager().getResourceOrThrow(
                ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "worldgen/world_preset/" + name + ".json"));
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            return WorldPreset.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE,
                    helper.getLevel().registryAccess()), JsonParser.parseReader(reader)).getOrThrow();
        }
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void wastelandPresetAndSeedSamples(GameTestHelper helper) throws Exception {
        var generator = (NoiseBasedChunkGenerator) preset(helper, "wasteland").overworld().orElseThrow().generator();
        helper.assertTrue(generator.getBiomeSource().possibleBiomes().size() == 10,
                "Wasteland preset must include all ten biomes including spider caves");
        helper.assertTrue(generator.getBiomeSource().possibleBiomes().stream().allMatch(b ->
                b.unwrapKey().orElseThrow().location().getNamespace().equals("cwi")),
                "Wasteland preset must use canonical CWI biomes");
        var settings = generator.generatorSettings().value();
        helper.assertTrue(settings.defaultFluid().is(Blocks.WATER)
                && settings.seaLevel() == 63 && settings.aquifersEnabled(),
                "Wasteland Overworld must preserve water oceans, sea level and aquifers");

        Set<String> reachedBiomes = new HashSet<>();
        for (long seed : new long[] {0L, 42L, 8675309L}) {
            var registryAccess = helper.getLevel().registryAccess();
            RandomState state = RandomState.create(registryAccess.asGetterLookup(),
                    ResourceKey.create(Registries.NOISE_SETTINGS, ResourceLocation.withDefaultNamespace("overworld")), seed);
            long started = System.nanoTime();
            TerrainMetrics metrics = sampleTerrain(generator, state, helper);
            helper.assertTrue(metrics.drySamples > 0, "Wasteland terrain has no dry land in sampled grid for seed " + seed);
            helper.assertTrue(metrics.maxHeight > metrics.minHeight,
                    "Wasteland terrain lost all height variation in sampled grid for seed " + seed);
            RandomState repeatedState = RandomState.create(registryAccess.asGetterLookup(),
                    ResourceKey.create(Registries.NOISE_SETTINGS, ResourceLocation.withDefaultNamespace("overworld")), seed);
            helper.assertTrue(generator.getBaseHeight(0, 0, Heightmap.Types.OCEAN_FLOOR_WG, HEIGHT, state)
                    == generator.getBaseHeight(0, 0, Heightmap.Types.OCEAN_FLOOR_WG, HEIGHT, repeatedState),
                    "Base terrain sampling is not deterministic");

            Map<String, Integer> coverage = new TreeMap<>();
            Map<String, Integer> depthCoverage = new TreeMap<>();
            for (int x = -4096; x <= 4096; x += 256) {
                for (int z = -4096; z <= 4096; z += 256) {
                    for (int y : new int[] {-48, -24, 0, 24, 48, 80}) {
                        String biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y),
                                QuartPos.fromBlock(z), state.sampler()).unwrapKey().orElseThrow().location().toString();
                        coverage.merge(biome, 1, Integer::sum);
                        depthCoverage.merge(y + ":" + biome, 1, Integer::sum);
                        reachedBiomes.add(biome);
                    }
                }
            }
            JsonObject report = new JsonObject();
            report.addProperty("seed", seed);
            report.add("wasteland", metrics.json());
            report.add("biome_samples", counts(coverage));
            report.add("biome_samples_by_y", counts(depthCoverage));
            report.addProperty("elapsed_ms", (System.nanoTime() - started) / 1_000_000L);
            System.out.println("CWI_WASTELAND_TERRAIN_SAMPLE=" + report);
        }
        helper.assertTrue(reachedBiomes.size() == 10, "Wasteland climate samples did not reach all ten biomes: " + reachedBiomes);
        System.out.println("CWI_WASTELAND_REACHED_BIOMES=" + reachedBiomes);
        helper.succeed();
    }

    private static TerrainMetrics sampleTerrain(NoiseBasedChunkGenerator generator, RandomState state, GameTestHelper helper) {
        int[] heights = new int[49];
        int index = 0, dry = 0, gentle = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int x = -1536; x <= 1536; x += 512) {
            for (int z = -1536; z <= 1536; z += 512) {
                int h = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, HEIGHT, state);
                helper.assertTrue(h >= -64 && h <= 384, "Base height outside dimension bounds: " + h);
                heights[index++] = h;
                min = Math.min(min, h);
                max = Math.max(max, h);
                if (h > generator.getSeaLevel()) {
                    dry++;
                    int hx = generator.getBaseHeight(x + 32, z, Heightmap.Types.OCEAN_FLOOR_WG, HEIGHT, state);
                    int hz = generator.getBaseHeight(x, z + 32, Heightmap.Types.OCEAN_FLOOR_WG, HEIGHT, state);
                    if (Math.max(Math.abs(h - hx), Math.abs(h - hz)) <= 4) gentle++;
                }
            }
        }
        return new TerrainMetrics(heights, dry, gentle, min, max);
    }

    private record TerrainMetrics(int[] heights, int drySamples, int gentleDrySamples, int minHeight, int maxHeight) {
        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("columns", heights.length);
            result.addProperty("dry_columns", drySamples);
            result.addProperty("dry_columns_with_at_most_4_block_rise_over_32", gentleDrySamples);
            result.addProperty("min_ground_y", minHeight);
            result.addProperty("max_ground_y", maxHeight);
            return result;
        }
    }

    private static JsonObject counts(Map<String, Integer> counts) {
        JsonObject result = new JsonObject();
        counts.forEach(result::addProperty);
        return result;
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void freshWastelandResourceChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "wasteland_test")));
        helper.assertTrue(level != null, "Missing wasteland test dimension");
        Map<String, Integer> blocks = new TreeMap<>();
        Set<String> biomes = new HashSet<>();
        int oreBlocks = 0, depositBlocks = 0, fluidBlocks = 0, structureStarts = 0;
        int sampledChunks = 0;
        final int maxChunks = 64;
        List<int[]> samples = new ArrayList<>(List.of(new int[] {0, 0}, new int[] {24, -16}, new int[] {-32, 20}));
        // Smaller weighted bodies and whole-group thinning legitimately leave several
        // chunks empty. Keep the original three samples, then expand a compact area
        // until an actual production deposit is found, with a fixed generation budget.
        for (int ring = 1; samples.size() < maxChunks; ring++) {
            for (int x = -ring; x <= ring && samples.size() < maxChunks; x++) {
                for (int z = -ring; z <= ring && samples.size() < maxChunks; z++) {
                    if (Math.abs(x) == ring || Math.abs(z) == ring) samples.add(new int[] {x, z});
                }
            }
        }
        long started = System.nanoTime();
        for (int[] sample : samples) {
            var chunk = level.getChunk(sample[0], sample[1], ChunkStatus.FULL, true);
            helper.assertTrue(chunk != null, "Fresh wasteland generation failed");
            structureStarts += chunk.getAllStarts().values().stream().filter(start -> start.isValid()).count();
            for (int x = sample[0] * 16; x < sample[0] * 16 + 16; x++) {
                for (int z = sample[1] * 16; z < sample[1] * 16 + 16; z++) {
                    for (int y = -64; y < 192; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        var state = chunk.getBlockState(pos);
                        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        if (!state.isAir()) blocks.merge(block, 1, Integer::sum);
                        if (block.endsWith("_ore")) oreBlocks++;
                        if (block.endsWith("_deposit")) depositBlocks++;
                        if (!state.getFluidState().isEmpty()) fluidBlocks++;
                    }
                    String biome = level.getBiome(new BlockPos(x, 80, z)).unwrapKey().orElseThrow().location().toString();
                    helper.assertTrue(biome.startsWith("cwi:"), "Unexpected wasteland biome " + biome);
                    biomes.add(biome);
                }
            }
            sampledChunks++;
            if (sampledChunks >= 3 && depositBlocks > 0) break;
        }
        helper.assertTrue(oreBlocks > 0, "No actual ore blocks generated in wasteland sample chunks");
        helper.assertTrue(depositBlocks > 0,
                "No compound deposit blocks generated within " + sampledChunks + " fresh wasteland sample chunks");
        JsonObject report = new JsonObject();
        report.addProperty("chunks", sampledChunks);
        report.addProperty("maximum_chunk_search", maxChunks);
        report.addProperty("ore_blocks", oreBlocks);
        report.addProperty("compound_deposit_blocks", depositBlocks);
        report.addProperty("fluid_blocks", fluidBlocks);
        report.addProperty("valid_structure_starts_in_sample_chunks", structureStarts);
        report.addProperty("elapsed_ms", (System.nanoTime() - started) / 1_000_000L);
        report.add("block_counts", counts(blocks));
        System.out.println("CWI_WASTELAND_CHUNK_SAMPLE=" + report);
        System.out.println("CWI_WASTELAND_CHUNK_BIOMES=" + biomes);
        helper.succeed();
    }
}

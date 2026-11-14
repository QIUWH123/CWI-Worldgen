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
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.ContinentalHydrology;

/** Physical cross-section invariants for the hook called by the native carver. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeRiverMorphologyTest {
    private static final float UNIT = 1F / 384;

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void lowBarsKeepBothChannelsWetAndRespectTheirHeightCap(GameTestHelper helper) {
        int found = 0, untouched = 0;
        for (int reach = 0; reach < 24; reach++) {
            var shape = shape(reach);
            for (int along = 200; along < 1750; along += 7) {
                for (int lateral = -4; lateral <= 4; lateral++) {
                    Cell cell = cell(100, .35F);
                    float height = at(shape, cell, reach, along, lateral, 10, 1, .8F, 85);
                    if (height >= 90 * UNIT) {
                        found++;
                        helper.assertTrue(height <= 91.001F * UNIT, "Emergent bar exceeds one block");
                        for (int side : new int[]{-7, 7}) {
                            helper.assertTrue(at(shape, cell, reach, along, side, 10, 1, .8F, 85) < 90 * UNIT,
                                    "Low bar sealed a side channel");
                        }
                    } else if (height == 85 * UNIT) untouched++;
                    helper.assertTrue(cell.height == 100 * UNIT && cell.waterTable == .35F,
                            "Shape query mutated its cell");
                }
            }
        }
        helper.assertTrue(found > 0 && untouched > found, "Bars are absent or cover most of the river");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void gorgesAreSignedAsymmetricAndKeepTheOriginalUpperEnvelope(GameTestHelper helper) {
        int asymmetric = 0, unchanged = 0;
        for (int reach = 0; reach < 24; reach++) {
            var shape = shape(reach);
            for (int along = 250; along < 1700; along += 17) {
                Cell high = cell(180, .35F);
                float left = at(shape, high, reach, along, -24, 10, 1, .8F, 96);
                float right = at(shape, high, reach, along, 24, 10, 1, .8F, 96);
                helper.assertTrue(left <= high.height && right <= high.height, "Gorge added above native terrain");
                if (Math.abs(left - right) > 2 * UNIT) asymmetric++;
                if (left == 96 * UNIT && right == 96 * UNIT) unchanged++;
            }
        }
        helper.assertTrue(asymmetric > 0 && unchanged > asymmetric, "Gorges lost rarity or signed asymmetry");
        var shape = shape(0);
        Cell high = cell(180, .35F);
        helper.assertTrue(at(shape, high, 0, 1000, 0, 5, 1, .8F, 85) == 85 * UNIT, "Small channel changed");
        helper.assertTrue(at(shape, high, 0, 1000, 0, 10, 1.2F, .8F, 85) == 85 * UNIT, "Lake changed");
        high.continentEdge = .45F;
        helper.assertTrue(at(shape, high, 0, 1000, 0, 10, 1, .8F, 85) == 85 * UNIT, "Coast changed");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void poolsOnlyDeepenTheWetCenterAtActualLowerWaterSteps(GameTestHelper helper) {
        int found = 0;
        var shape = shape(0);
        for (int n = 0; n <= 1000; n++) {
            Cell cell = cell(100, n / 1000F);
            float center = at(shape, cell, 0, 1000, 0, 10, 1, .1F, 85);
            helper.assertTrue(center >= 82 * UNIT - .000001F, "Pool adjustment exceeds three blocks");
            if (center < 85 * UNIT) found++;
            helper.assertTrue(at(shape, cell, 0, 1000, 14, 10, 1, .1F, 96) == 96 * UNIT,
                    "Pool lowered a retaining bank");
        }
        helper.assertTrue(found > 0 && found < 100, "Pools are absent or unrelated to lower native steps");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 1200)
    public static void completedNativeTerrainRetainsTheBarAndReproducesAcrossTileQueries(GameTestHelper helper) {
        try (var forward = new ReferenceTerrain(0); var reverse = new ReferenceTerrain(0)) {
            Cell crest = forward.sample(3277, 4234);
            float water = water(forward, crest);
            helper.assertTrue(crest.height >= water && crest.height <= water + 1.01F * forward.levels().unit,
                    "Production filtered terrain lost the low emergent bar");
            // This native channel runs diagonally. Use quantized, genuinely two-block-deep lanes.
            for (int[] point : new int[][]{{3272, 4236}, {3279, 4233}}) {
                var lane = ReferenceRiverColumn.sample(forward, point[0], point[1]);
                helper.assertTrue(lane.hasWater() && lane.waterY() - lane.solidTopY() >= 2,
                        "Production bar blocks a projected two-block-deep flanking channel at "
                                + point[0] + "," + point[1] + ": " + lane);
            }
            helper.assertTrue(!ReferenceRiverColumn.sample(forward, 3282, 4232).hasWater(),
                    "The old float-only flank fixture no longer demonstrates block quantization");
            int[][] points = {{3277, 4234}, {3201, 4202}, {-128, -128}, {-129, -128},
                    {-128, -129}, {127, 128}, {128, 128}, {128, 127}};
            float[] heights = new float[points.length];
            for (int i = 0; i < points.length; i++) heights[i] = forward.sample(points[i][0], points[i][1]).height;
            for (int i = points.length - 1; i >= 0; i--) {
                int x = points[i][0], z = points[i][1];
                Cell cell = reverse.sample(x, z);
                helper.assertTrue(Float.floatToRawIntBits(cell.height) == Float.floatToRawIntBits(heights[i]),
                        "River morphology depends on tile query order at " + x + "," + z);
                helper.assertTrue(Float.floatToRawIntBits(reverse.prepareChunk(x >> 4, z >> 4).getCell(x, z).height)
                                == Float.floatToRawIntBits(cell.height),
                        "Published chunk reader disagrees at tile boundary");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void actualNativeBlocksKeepAnExposedLowBarAndBothWetChannelsAfterCarving(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_legacy_test")));
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var state = RandomState.create(generator.generatorSettings().value(),
                level.registryAccess().lookupOrThrow(Registries.NOISE), 0L);
        var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        try {
            var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
            Map<Long, ProtoChunk> owned = new HashMap<>();
            for (ChunkPos pos : new ChunkPos[]{new ChunkPos(204, 264), new ChunkPos(205, 264)}) {
                var chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, registry, null);
                complete(level, generator.createBiomes(state, Blender.empty(), level.structureManager(), chunk));
                chunk.setPersistedStatus(ChunkStatus.BIOMES);
                complete(level, generator.fillFromNoise(Blender.empty(), state, level.structureManager(), chunk));
                chunk.setPersistedStatus(ChunkStatus.NOISE);
                diagnoseRiverSection("noise", terrain, chunk);
                owned.put(pos.toLong(), chunk);
            }
            int dryCrests = 0, wetFlanks = 0;
            for (var chunk : owned.values()) {
                var region = new RiverRegion(level, chunk, owned, generator, state);
                var biomes = new BiomeManager(region, BiomeManager.obfuscateSeed(0L));
                generator.buildSurface(chunk, new WorldGenerationContext(generator, level), state,
                        level.structureManager(), biomes, registry, Blender.empty());
                chunk.setPersistedStatus(ChunkStatus.SURFACE);
                diagnoseRiverSection("surface", terrain, chunk);
                // Real configured AIR carvers and the production post-carver gasket run here.
                // Neighbor chunks supply only their carver-biome metadata, just as the native loop does.
                generator.applyCarvers(region, 0L, state, biomes, level.structureManager(), chunk, GenerationStep.Carving.AIR);
                chunk.setPersistedStatus(ChunkStatus.CARVERS);
                diagnoseRiverSection("carvers-gasket", terrain, chunk);
                int secondGasketWrites = ReferenceRiverGasket.place(chunk, state, 0L);
                System.out.println("CWI_RIVER_GASKET_DIAGNOSTIC=" + chunk.getPos()
                        + ",second_pass_writes:" + secondGasketWrites);
                helper.assertTrue(secondGasketWrites == 0,
                        "Actual carver tail did not leave an idempotently sealed river");
                for (int x = 3273; x <= 3281; x++) for (int z = 4228; z <= 4242; z++) {
                    if ((x >> 4) != chunk.getPos().x || (z >> 4) != chunk.getPos().z) continue;
                    Cell cell = terrain.sample(x, z);
                    float water = water(terrain, cell);
                    if (!cell.terrain.isRiver() || cell.height < water || cell.height > water + 1.01F * terrain.levels().unit) continue;
                    int waterY = terrain.levels().scale(water);
                    BlockPos crest = new BlockPos(x, waterY, z);
                    if (chunk.getBlockState(crest).isAir() || !chunk.getBlockState(crest).getFluidState().isEmpty()) continue;
                    helper.assertTrue(chunk.getBlockState(crest.above()).isAir(),
                            "Generated bar has a solid/fluid roof above its one-block cap at " + crest);
                    dryCrests++;
                }
                for (int[] point : new int[][]{{3272, 4236}, {3279, 4233}}) {
                    if ((point[0] >> 4) != chunk.getPos().x || (point[1] >> 4) != chunk.getPos().z) continue;
                    var lane = ReferenceRiverColumn.sample(terrain, point[0], point[1]);
                    helper.assertTrue(lane.hasWater() && lane.waterY() - lane.solidTopY() >= 2,
                            "Wet-channel fixture is not projected two blocks deep: " + lane);
                    BlockPos top = new BlockPos(point[0], lane.waterY(), point[1]);
                    helper.assertTrue(chunk.getBlockState(top).is(Blocks.WATER)
                                    && chunk.getBlockState(top.below()).is(Blocks.WATER),
                            "Actual bar flank lost its vanilla water interval at " + top);
                    helper.assertTrue(chunk.getBlockState(top.above()).isAir(), "Flanking channel acquired a solid roof");
                    wetFlanks++;
                }
                for (var neighbor : region.neighbors.values()) {
                    for (var section : neighbor.getSections()) helper.assertTrue(section.hasOnlyAir(),
                            "Native river/carver integration wrote to an unowned neighbor chunk");
                }
            }
            System.out.println("CWI_RIVER_BAR_BLOCKS=seed:0,surface_carvers_gasket:true,dry_crests:"
                    + dryCrests + ",wet_flanks:" + wetFlanks);
            helper.assertTrue(dryCrests > 0 && wetFlanks == 2,
                    "Actual density/surface/carver generation did not preserve an exposed split-channel bar");
        } finally {
            terrain.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void eligibilityAndNativeWaterStepThresholdsRemainContinuous(GameTestHelper helper) {
        var shape = shape(23);
        Cell high = cell(180, .35F);
        float before = shape.shape(high, 23 * 431 + 488, 161, 488 / 2000F, 24,
                10, 14, 35, 120, 90 * UNIT, 1, .219999F, 96 * UNIT);
        float after = shape.shape(high, 23 * 431 + 488, 161, 488 / 2000F, 24,
                10, 14, 35, 120, 90 * UNIT, 1, .220001F, 96 * UNIT);
        helper.assertTrue(Math.abs(after - before) < .05F * UNIT, "Gorge activation produced a threshold wall");
        shape = shape(0);
        float previous = Float.NaN;
        for (int i = 0; i <= 1000000; i++) {
            float table = i / 1000000F;
            Cell lower = cell(100, table);
            float current = shape.shape(lower, 1000, 137, .5F, 0, 10, 14, 35, 120,
                    90 * UNIT, 1, ContinentalHydrology.getFlatnessFactor(table), 85 * UNIT);
            if (i > 0) helper.assertTrue(Math.abs(current - previous) < .05F * UNIT,
                    "Native water-step/flatness crossing produced a pool seam");
            previous = current;
        }
        helper.succeed();
    }

    private static float water(ReferenceTerrain terrain, Cell cell) {
        return ContinentalHydrology.getComplexWaterHeight(cell.waterTable,
                cell.globalContinentScale, cell.continentSizeModifier) + terrain.levels().water;
    }

    private static void diagnoseRiverSection(String stage, ReferenceTerrain terrain, ProtoChunk chunk) {
        for (int lateral = -10; lateral <= 10; lateral++) {
            int x = 3277 + lateral, z = 4234 - Math.round(lateral * .4F);
            if ((x >> 4) != chunk.getPos().x || (z >> 4) != chunk.getPos().z) continue;
            Cell cell = terrain.sample(x, z);
            var column = ReferenceRiverColumn.sample(terrain, x, z);
            StringBuilder states = new StringBuilder();
            for (int dy = 2; dy >= -3; dy--) {
                if (!states.isEmpty()) states.append(';');
                states.append(column.waterY() + dy).append(':')
                        .append(chunk.getBlockState(new BlockPos(x, column.waterY() + dy, z)));
            }
            System.out.println("CWI_RIVER_SECTION_DIAGNOSTIC=stage:" + stage + ",x:" + x + ",z:" + z
                    + ",height:" + cell.height + ",water:" + water(terrain, cell)
                    + ",zone:" + cell.riverZone + ",column:" + column + ",states:" + states);
        }
    }

    private static void complete(ServerLevel level, CompletableFuture<?> future) {
        level.getServer().managedBlock(() -> { level.getChunkSource().pollTask(); return future.isDone(); });
        future.join();
    }

    /** The native carver visits nearby chunks only to cache their generation biome. */
    private static final class RiverRegion extends WorldGenRegion {
        private final ServerLevel level;
        private final Map<Long, ProtoChunk> owned;
        private final NoiseBasedChunkGenerator generator;
        private final RandomState state;
        private final Map<Long, ProtoChunk> neighbors = new HashMap<>();

        RiverRegion(ServerLevel level, ChunkAccess center, Map<Long, ProtoChunk> owned,
                NoiseBasedChunkGenerator generator, RandomState state) {
            super(level, null, null, center);
            this.level = level;
            this.owned = owned;
            this.generator = generator;
            this.state = state;
        }

        @Override public long getSeed() { return 0L; }
        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            long key = ChunkPos.asLong(x, z);
            ProtoChunk chunk = owned.get(key);
            if (chunk != null) return chunk;
            if (status.isAfter(ChunkStatus.EMPTY)) throw new AssertionError("Carver requested generated neighbor " + x + "," + z);
            return neighbors.computeIfAbsent(key, ignored -> new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY,
                    level, level.registryAccess().registryOrThrow(Registries.BIOME), null));
        }
        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) {
            return generator.getBiomeSource().getNoiseBiome(x, y, z, state.sampler());
        }
    }

    private static ReferenceRiverMorphology shape(int reach) {
        return new ReferenceRiverMorphology(reach * 431, 137, reach * 431 + 2000, 137,
                true, 8, UNIT, 62 * UNIT);
    }

    private static float at(ReferenceRiverMorphology shape, Cell cell, int reach, int along,
            int lateral, float radius, float lake, float flatness, int baseline) {
        return shape.shape(cell, reach * 431 + along, 137 + lateral, along / 2000F,
                Math.abs(lateral), radius, 14, 35, 120, 90 * UNIT, lake, flatness, baseline * UNIT);
    }

    private static Cell cell(int height, float waterTable) {
        Cell cell = new Cell();
        cell.height = height * UNIT;
        cell.continentEdge = .9F;
        cell.waterTable = waterTable;
        cell.globalContinentScale = 3000;
        cell.continentSizeModifier = 1;
        return cell;
    }
}

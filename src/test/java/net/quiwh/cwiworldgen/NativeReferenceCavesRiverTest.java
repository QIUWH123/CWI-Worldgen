package net.quiwh.cwiworldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverGasket;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverGasket.ColumnSampler;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverGasket.RiverColumn;

/** Native cave-noise and post-carver river repair regressions, including negative chunk edges. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeReferenceCavesRiverTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 1200)
    public static void originalNoodlesRemainActiveAboveVanillaCeiling(GameTestHelper helper) {
        ServerLevel level = wasteland(helper);
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var original = generator.generatorSettings().value();
        var registry = level.registryAccess().registryOrThrow(Registries.DENSITY_FUNCTION);
        DensityFunction noodle = registry.get(caveId("noodle"));
        DensityFunction spaghetti = registry.get(caveId("spaghetti_2d"));
        DensityFunction entrances = registry.get(caveId("entrances"));
        helper.assertTrue(noodle != null && spaghetti != null && entrances != null,
                "Dedicated source cave density resources did not load");
        NoiseRouter source = original.noiseRouter();
        NoiseRouter probeRouter = new NoiseRouter(source.barrierNoise(), source.fluidLevelFloodednessNoise(),
                source.fluidLevelSpreadNoise(), source.lavaNoise(), source.temperature(), source.vegetation(),
                source.continents(), source.erosion(), source.depth(), source.ridges(),
                source.initialDensityWithoutJaggedness(), source.finalDensity(), entrances, spaghetti, noodle);
        var probeSettings = new NoiseGeneratorSettings(original.noiseSettings(), original.defaultBlock(),
                original.defaultFluid(), probeRouter, original.surfaceRule(), original.spawnTarget(),
                original.seaLevel(), original.disableMobGeneration(), original.aquifersEnabled(),
                original.oreVeinsEnabled(), original.useLegacyRandomSource());
        RandomState probe = RandomState.create(probeSettings,
                level.registryAccess().asGetterLookup().lookupOrThrow(Registries.NOISE), 424242L);
        int extended = 0;
        for (int x = -128; x <= 128; x += 16) {
            for (int z = -128; z <= 128; z += 16) {
                for (int y : new int[]{336, 360, 384}) {
                    var context = new DensityFunction.SinglePointContext(x, y, z);
                    if (probe.router().veinGap().compute(context) != 64.) extended++;
                    helper.assertTrue(probe.router().veinToggle().compute(context) == 1.,
                            "Source entrance probability zero did not disable entrance density");
                    double value = probe.router().veinRidged().compute(context);
                    helper.assertTrue(value >= -1. && value <= 1., "Source spaghetti left its density clamp");
                }
                for (int y : new int[]{-64, -61, 385, 400}) {
                    helper.assertTrue(probe.router().veinGap().compute(
                                    new DensityFunction.SinglePointContext(x, y, z)) == 64.,
                            "Source noodle escaped its original -60..384 height range at " + y);
                }
            }
        }
        helper.assertTrue(extended > 0, "Noodles still stop at the vanilla Y 320 ceiling");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void generatedUndergroundContainsCavesAndSolidGeology(GameTestHelper helper) {
        ServerLevel level = wasteland(helper);
        ChunkAccess chunk = level.getChunk(4, 4, ChunkStatus.CARVERS, true);
        helper.assertTrue(chunk != null, "Native source-terrain carver generation failed");
        int cavities = 0, geology = 0, bedrock = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = chunk.getPos().getMinBlockX(); x <= chunk.getPos().getMaxBlockX(); x++) {
            for (int z = chunk.getPos().getMinBlockZ(); z <= chunk.getPos().getMaxBlockZ(); z++) {
                for (int y = -48; y <= 0; y++) {
                    var state = chunk.getBlockState(pos.set(x, y, z));
                    if (state.isAir() || !state.getFluidState().isEmpty()) cavities++;
                    else geology++;
                }
                if (chunk.getBlockState(pos.set(x, -64, z)).is(Blocks.BEDROCK)) bedrock++;
            }
        }
        helper.assertTrue(cavities > 0, "Native underground generation contains no carved cavities");
        helper.assertTrue(geology > 0, "Native cave graph removed the entire sampled geological interval");
        helper.assertTrue(bedrock == 256, "Native cave graph or river gasket breached bottom bedrock");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void riverRepairsSourceUndersideAndPreservesOreStructureAndWater(GameTestHelper helper) {
        ProtoChunk chunk = emptyChunk(helper, 0, 0);
        var source = new BlockPos(8, 80, 8);
        chunk.setBlockState(source, Blocks.WATER.defaultBlockState(), false);
        chunk.setBlockState(source.west(), Blocks.DIAMOND_ORE.defaultBlockState(), false);
        chunk.setBlockState(source.east(), Blocks.STONE_BRICKS.defaultBlockState(), false);
        chunk.setBlockState(source.north(), Blocks.ANDESITE.defaultBlockState(), false);
        chunk.setBlockState(source.south().below(), Blocks.WATER.defaultBlockState(), false);
        chunk.setBlockState(source.north().north(), Blocks.CAVE_AIR.defaultBlockState(), false);
        ColumnSampler columns = (x, z) -> x == 8 && z == 8
                ? new RiverColumn(80, 80, 80) : RiverColumn.dry(80);
        int repairs = ReferenceRiverGasket.seal(chunk, 42L, columns);
        helper.assertTrue(repairs > 0, "Post-carver gasket made no source-water repairs");
        helper.assertTrue(chunk.getBlockState(source.below()).is(Blocks.STONE), "Air under source water stayed open");
        helper.assertTrue(chunk.getBlockState(source.north().north()).is(Blocks.STONE), "Nearby cave air stayed open");
        helper.assertTrue(chunk.getBlockState(source).is(Blocks.WATER), "Gasket replaced source water");
        helper.assertTrue(chunk.getBlockState(source.west()).is(Blocks.DIAMOND_ORE), "Gasket damaged ore");
        helper.assertTrue(chunk.getBlockState(source.east()).is(Blocks.STONE_BRICKS), "Gasket damaged structure blocks");
        helper.assertTrue(chunk.getBlockState(source.north()).is(Blocks.ANDESITE), "Gasket damaged solid geology");
        helper.assertTrue(chunk.getBlockState(source.south()).isAir(), "Gasket filled an air block above existing water");
        helper.assertTrue(ReferenceRiverGasket.seal(chunk, 42L, columns) == 0, "Repeated gasket pass changed repaired chunk");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void flowingWaterAndDryTerrainDoNotAcquireGaskets(GameTestHelper helper) {
        ProtoChunk chunk = emptyChunk(helper, 0, 0);
        BlockPos pos = new BlockPos(8, 80, 8);
        chunk.setBlockState(pos, Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 1), false);
        ColumnSampler single = (x, z) -> x == 8 && z == 8
                ? new RiverColumn(80, 80, 80) : RiverColumn.dry(80);
        helper.assertTrue(ReferenceRiverGasket.seal(chunk, 42L, single) == 0,
                "Flowing water was treated as a source-water gasket anchor");
        chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), false);
        helper.assertTrue(ReferenceRiverGasket.seal(chunk, 42L, (x, z) -> RiverColumn.dry(80)) == 0,
                "Source water outside river hydrology acquired a gasket");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void chunkEdgeSealingIsOwnedAndIndependentOfNeighborGenerationOrder(GameTestHelper helper) {
        for (int edgeX : new int[]{-1, 15}) {
            int leftX = edgeX >> 4;
            ProtoChunk left = emptyChunk(helper, leftX, 0);
            ProtoChunk right = emptyChunk(helper, leftX + 1, 0);
            ProtoChunk reverseLeft = emptyChunk(helper, leftX, 0);
            ProtoChunk reverseRight = emptyChunk(helper, leftX + 1, 0);
            BlockPos source = new BlockPos(edgeX, 80, 8);
            left.setBlockState(source, Blocks.WATER.defaultBlockState(), false);
            reverseLeft.setBlockState(source, Blocks.WATER.defaultBlockState(), false);
            ColumnSampler columns = (x, z) -> x == edgeX && z == 8
                    ? new RiverColumn(80, 80, 80) : RiverColumn.dry(80);
            ReferenceRiverGasket.seal(left, 52L, columns);
            helper.assertTrue(right.getBlockState(source.east()).isAir(), "Left gasket wrote into its neighbor");
            ReferenceRiverGasket.seal(right, 52L, columns);
            ReferenceRiverGasket.seal(reverseRight, 52L, columns);
            ReferenceRiverGasket.seal(reverseLeft, 52L, columns);
            helper.assertTrue(right.getBlockState(source.east()).is(Blocks.STONE),
                    "Terrain halo did not seal river cave air across a chunk edge");
            for (int x = edgeX - 7; x <= edgeX + 7; x++) {
                for (int z = 1; z <= 15; z++) {
                    for (int y = 79; y <= 81; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        ProtoChunk first = x <= edgeX ? left : right;
                        ProtoChunk second = x <= edgeX ? reverseLeft : reverseRight;
                        helper.assertTrue(first.getBlockState(pos).equals(second.getBlockState(pos)),
                                "Neighbor generation order changed the gasket at " + pos);
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void gasketRadiusRetainsSourceRangeAndOtherDimensionRoutersStayUntouched(GameTestHelper helper) {
        boolean[] radii = new boolean[5];
        for (int x = -128; x <= 128; x++) {
            int radius = ReferenceRiverGasket.radiusFor(42L, x, 80, 17);
            helper.assertTrue(radius >= 3 && radius <= 7, "Source gasket radius left 3..7");
            radii[radius - 3] = true;
            helper.assertTrue(radius == ReferenceRiverGasket.radiusFor(42L, x, 80, 17), "Source radius was unstable");
        }
        for (boolean present : radii) helper.assertTrue(present, "Source gasket radius lost a source size category");
        var lookup = helper.getLevel().registryAccess().asGetterLookup();
        ProtoChunk chunk = emptyChunk(helper, 0, 0);
        BlockPos source = new BlockPos(8, 80, 8);
        chunk.setBlockState(source, Blocks.WATER.defaultBlockState(), false);
        for (String dimension : new String[]{"nether", "end"}) {
            RandomState state = RandomState.create(lookup, ResourceKey.create(Registries.NOISE_SETTINGS,
                    ResourceLocation.withDefaultNamespace(dimension)), 42L);
            helper.assertTrue(ReferenceRiverGasket.place(chunk, state, 42L) == 0,
                    "Reference river gasket ran for " + dimension);
            helper.assertTrue(chunk.getBlockState(source.below()).isAir(), "Other dimension river floor changed");
        }
        helper.succeed();
    }

    private static ProtoChunk emptyChunk(GameTestHelper helper, int chunkX, int chunkZ) {
        ServerLevel level = wasteland(helper);
        return new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
    }

    private static ServerLevel wasteland(GameTestHelper helper) {
        return helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "wasteland_test")));
    }

    private static ResourceLocation caveId(String name) {
        return ResourceLocation.fromNamespaceAndPath("cwi", "terrain/caves/" + name);
    }

    private NativeReferenceCavesRiverTest() {}
}

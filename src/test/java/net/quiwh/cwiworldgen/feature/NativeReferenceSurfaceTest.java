package net.quiwh.cwiworldgen.feature;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.RiverBankProfiles;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.Presets;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noises;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeReferenceSurfaceTest {
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void completedWatershedGeometrySelectsRealBankAndWashEdits(GameTestHelper helper) {
        try (var terrain = new ReferenceTerrain(42L, 2)) {
            for (int[] coordinates : new int[][] {{5377, -1318}, {5931, -1681}, {3851, 684}}) {
                int x = coordinates[0], z = coordinates[1];
                var cell = terrain.sample(x, z);
                var water = terrain.watershedSample(x, z);
                int y = terrain.levels().scale(cell.height);
                var action = ReferenceSurfaceExposureFeature.bankAction(terrain, water, cell, x, z, y, 42L);
                var expected = x == 3851 ? RiverBankProfiles.Action.DEPLETED_SEDIMENT : RiverBankProfiles.Action.EXPOSE_HOST;
                helper.assertTrue(action == expected,
                        "Real filtered watershed no longer selects bank/wash treatment at " + x + "," + z);
                var chunk = new ProtoChunk(new ChunkPos(x >> 4, z >> 4), UpgradeData.EMPTY, helper.getLevel(),
                        helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
                var pos = new BlockPos.MutableBlockPos(x, y, z);
                chunk.setBlockState(pos, Blocks.DIRT.defaultBlockState(), false);
                chunk.setBlockState(pos.setY(y - 1), Blocks.DIRT.defaultBlockState(), false);
                chunk.setBlockState(pos.setY(y - 2), Blocks.TUFF.defaultBlockState(), false);
                BlockState material = expected == RiverBankProfiles.Action.EXPOSE_HOST ? Blocks.TUFF.defaultBlockState()
                        : BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:depleted_dirt")).defaultBlockState();
                helper.assertTrue(ReferenceSurfaceExposureFeature.finishBankColumn(chunk, pos, y, action)
                                && chunk.getBlockState(pos.setY(y)) == material,
                        "Selected real bank/wash treatment did not change actual blocks");
                System.out.println("CWI_BANK_NATIVE=" + x + "/" + z + ",dry:" + water.dry() + ",action:" + action);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bendFinishingUsesLocalGeologyAndRejectsProtectedCover(GameTestHelper helper) {
        var chunk = chunk(helper);
        BlockState soil = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:ashen_depleted_dirt")).defaultBlockState();
        BlockState depleted = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:depleted_dirt")).defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(3, 100, 3);
        for (int dy = 0; dy < 3; dy++) chunk.setBlockState(pos.setY(100 - dy), soil, false);
        chunk.setBlockState(pos.setY(97), Blocks.TUFF.defaultBlockState(), false);
        helper.assertTrue(ReferenceSurfaceExposureFeature.finishBankColumn(chunk, pos, 100,
                RiverBankProfiles.Action.EXPOSE_HOST), "Outer bank did not expose its host");
        helper.assertTrue(chunk.getBlockState(pos.setY(100)).is(Blocks.TUFF), "Outer bank invented unrelated rock");
        for (int dy = 0; dy < 3; dy++) chunk.setBlockState(pos.setY(100 - dy), soil, false);
        helper.assertTrue(ReferenceSurfaceExposureFeature.finishBankColumn(chunk, pos, 100,
                RiverBankProfiles.Action.DEPLETED_SEDIMENT), "Inner bend did not retain sediment");
        helper.assertTrue(chunk.getBlockState(pos.setY(100)) == depleted && chunk.getBlockState(pos.setY(99)) == depleted,
                "Sediment introduced buried ashen caps or unrelated material");
        for (BlockState protectedState : List.of(Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState())) {
            for (var action : List.of(RiverBankProfiles.Action.EXPOSE_HOST, RiverBankProfiles.Action.DEPLETED_SEDIMENT)) {
                chunk.setBlockState(pos.setY(100), soil, false);
                chunk.setBlockState(pos.setY(99), protectedState, false);
                helper.assertTrue(!ReferenceSurfaceExposureFeature.finishBankColumn(chunk, pos, 100, action),
                        "Bank finishing crossed a protected layer");
                helper.assertTrue(chunk.getBlockState(pos.setY(100)) == soil && chunk.getBlockState(pos.setY(99)) == protectedState,
                        "Rejected bank finishing left a partial edit");
            }
        }
        chunk.setBlockState(pos.setY(99), soil, false);
        chunk.setBlockState(pos.setY(101), Blocks.WATER.defaultBlockState(), false);
        helper.assertTrue(!ReferenceSurfaceExposureFeature.finishBankColumn(chunk, pos, 100,
                RiverBankProfiles.Action.DEPLETED_SEDIMENT), "Submerged bank was decorated");
        helper.assertTrue(!ReferenceSurfaceExposureFeature.finishBankColumn(chunk, pos.set(19, 100, 3), 100,
                RiverBankProfiles.Action.EXPOSE_HOST), "Bank finishing escaped the owning chunk");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void erosionExposesTheUnderlyingRegisteredRockAndStopsAtProtectedStates(GameTestHelper helper) {
        var chunk = chunk(helper);
        BlockState soil = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:depleted_dirt")).defaultBlockState();
        BlockState rock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:quartzite")).defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(4, 128, 4);
        for (int dy = 0; dy < 3; dy++) chunk.setBlockState(pos.setY(128 - dy), soil, false);
        chunk.setBlockState(pos.setY(125), rock, false);
        var cell = cell(.7F, .9F, 0);
        var config = Presets.makeDefault().surface().erosion();
        helper.assertTrue(ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos, 128, cell, config, Noises.zero(),
                Blocks.STONE.defaultBlockState()), "Steep slope did not expose underlying rock");
        for (int dy = 0; dy < 4; dy++) helper.assertTrue(chunk.getBlockState(pos.setY(128 - dy)) == rock,
                "Exposed slope lost its natural rock material");

        for (BlockState protectedState : List.of(Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.CHEST.defaultBlockState(),
                Blocks.STONE_BRICKS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState())) {
            chunk.setBlockState(pos.setY(128), soil, false);
            chunk.setBlockState(pos.setY(127), protectedState, false);
            chunk.setBlockState(pos.setY(126), soil, false);
            ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos, 128, cell, config, Noises.zero(), rock);
            helper.assertTrue(chunk.getBlockState(pos.setY(127)) == protectedState, "Erosion replaced an ore or structure state");
            helper.assertTrue(chunk.getBlockState(pos.setY(126)) == soil, "Erosion continued beneath a protected state");
            chunk.setBlockState(pos.setY(128), protectedState, false);
            helper.assertTrue(!ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos, 128, cell, config, Noises.zero(), rock),
                    "Erosion accepted a protected top state");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sedimentScreeAndIntermediateGroundFollowIndependentSourceThresholds(GameTestHelper helper) {
        var chunk = chunk(helper);
        var config = Presets.makeDefault().surface().erosion();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(5, 128, 5);
        for (int dy = 0; dy < 5; dy++) chunk.setBlockState(pos.setY(128 - dy), Blocks.DIRT.defaultBlockState(), false);
        var cell = cell(.7F, .5F, 100);
        helper.assertTrue(ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos, 128, cell, config, Noises.zero(),
                Blocks.STONE.defaultBlockState()), "High sediment slope did not produce scree");
        helper.assertTrue(chunk.getBlockState(pos.setY(128)).is(Blocks.GRAVEL)
                && chunk.getBlockState(pos.setY(127)).is(Blocks.GRAVEL)
                && chunk.getBlockState(pos.setY(126)).is(Blocks.DIRT), "Scree must be exactly two blocks deep");
        for (int dy = 0; dy < 5; dy++) chunk.setBlockState(pos.setY(128 - dy), Blocks.GRASS_BLOCK.defaultBlockState(), false);
        cell.sediment = 0;
        ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos, 128, cell, config, Noises.zero(), Blocks.STONE.defaultBlockState());
        for (int dy = 0; dy < 4; dy++) helper.assertTrue(chunk.getBlockState(pos.setY(128 - dy)).is(Blocks.COARSE_DIRT),
                "Intermediate erosion must convert four ground blocks without inventing scree");
        helper.assertTrue(chunk.getBlockState(pos.setY(124)).is(Blocks.GRASS_BLOCK), "Intermediate erosion exceeded four blocks");
        chunk.setBlockState(pos.setY(128), Blocks.DIRT.defaultBlockState(), false);
        cell.terrain = TerrainType.WETLAND;
        cell.gradient = 1;
        cell.sediment = 100;
        helper.assertTrue(!ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos, 128, cell, config, Noises.zero(),
                Blocks.STONE.defaultBlockState()), "Excluded wetland column was decorated");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ashenCapErodesToDepletedDirtWithoutSpreadingIntoSubsoil(GameTestHelper helper) {
        var chunk = chunk(helper);
        BlockState ashen = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:ashen_depleted_dirt")).defaultBlockState();
        BlockState depleted = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:depleted_dirt")).defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(7, 128, 7);
        chunk.setBlockState(pos, ashen, false);
        for (int dy = 1; dy < 5; dy++) chunk.setBlockState(pos.setY(128 - dy), depleted, false);
        var config = Presets.makeDefault().surface().erosion();
        helper.assertTrue(!ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos, 128, cell(.7F, .1F, 0),
                        config, Noises.zero(), Blocks.STONE.defaultBlockState()),
                "Gentle terrain unexpectedly removed the exposed ashen cap");
        helper.assertTrue(chunk.getBlockState(pos.setY(128)) == ashen,
                "Gentle terrain lost its grass-like ashen cap");
        helper.assertTrue(ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos, 128, cell(.7F, .5F, 0),
                        config, Noises.zero(), Blocks.STONE.defaultBlockState()),
                "Intermediate erosion did not strip the ashen cap to depleted dirt");
        for (int dy = 0; dy < 5; dy++) helper.assertTrue(chunk.getBlockState(pos.setY(128 - dy)) == depleted,
                "Erosion spread ashen dirt beneath the surface at depth " + dy);
        System.out.println("CWI_ASHEN_EROSION=gentle_cap_preserved:true,intermediate_cap_stripped:true,subsoil_depleted:true");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void loadedAshenPatchOnlyReplacesAnExposedDepletedCap(GameTestHelper helper) {
        var level = helper.getLevel();
        var feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getHolderOrThrow(ResourceKey.create(Registries.CONFIGURED_FEATURE,
                        ResourceLocation.parse("cwi:stones/ashen_depleted_dirt"))).value();
        BlockState ashen = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:ashen_depleted_dirt")).defaultBlockState();
        BlockState depleted = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:depleted_dirt")).defaultBlockState();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 8, 4));
        for (int scenario = 0; scenario < 4; scenario++) {
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -3; y <= 3; y++) {
                level.setBlock(origin.offset(x, y, z), y <= 0 ? depleted : Blocks.AIR.defaultBlockState(), 2);
            }
            BlockState cover = switch (scenario) {
                case 1 -> depleted;
                case 2 -> Blocks.WATER.defaultBlockState();
                default -> Blocks.AIR.defaultBlockState();
            };
            level.setBlock(origin.above(), cover, 2);
            if (scenario == 3) level.setBlock(origin, Blocks.DIAMOND_ORE.defaultBlockState(), 2);
            BlockState target = level.getBlockState(origin);
            feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(42), origin);
            int ashenBlocks = 0;
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -3; y <= 3; y++) {
                BlockPos pos = origin.offset(x, y, z);
                BlockState expected = pos.equals(origin) ? (scenario == 0 ? ashen : target)
                        : pos.equals(origin.above()) ? cover : y <= 0 ? depleted : Blocks.AIR.defaultBlockState();
                BlockState actual = level.getBlockState(pos);
                helper.assertTrue(actual == expected,
                        "Loaded ashen patch changed buried, covered, neighboring or protected ground; scenario="
                                + scenario + " pos=" + pos + " actual=" + actual + " expected=" + expected);
                if (actual == ashen) ashenBlocks++;
            }
            helper.assertTrue(ashenBlocks == (scenario == 0 ? 1 : 0),
                    "Ashen patch must place exactly one exposed cap and no buried blocks; scenario=" + scenario);
        }
        System.out.println("CWI_ASHEN_PATCH=surface_single_cap:true,buried_rejected:true,water_rejected:true,ore_preserved:true");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void desertBandsPreserveBuriedOreAndUnrelatedStructureMaterials(GameTestHelper helper) {
        var chunk = chunk(helper);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(6, 160, 6);
        chunk.setBlockState(pos.setY(160), Blocks.SAND.defaultBlockState(), false);
        chunk.setBlockState(pos.setY(159), Blocks.SANDSTONE.defaultBlockState(), false);
        chunk.setBlockState(pos.setY(158), Blocks.GOLD_ORE.defaultBlockState(), false);
        chunk.setBlockState(pos.setY(157), Blocks.SAND.defaultBlockState(), false);
        var cell = cell(.8F, .8F, 0);
        helper.assertTrue(ReferenceSurfaceExposureFeature.erodeDesert(chunk, pos, 160, cell, new Levels(256, 63)),
                "Desert erosion failed to produce its source slope band");
        helper.assertTrue(chunk.getBlockState(pos.setY(160)).is(Blocks.ORANGE_TERRACOTTA), "Desert band slope thresholds changed");
        helper.assertTrue(chunk.getBlockState(pos.setY(158)).is(Blocks.GOLD_ORE)
                && chunk.getBlockState(pos.setY(157)).is(Blocks.SAND), "Desert erosion crossed a protected ore state");
        chunk.setBlockState(pos.setY(160), Blocks.CUT_SANDSTONE.defaultBlockState(), false);
        helper.assertTrue(!ReferenceSurfaceExposureFeature.erodeDesert(chunk, pos, 160, cell, new Levels(256, 63)),
                "Desert erosion accepted crafted structure sandstone");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cleanupRemovesOnlyUnsupportedWhitelistedSmallFoliage(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 4, 4));
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(pos, Blocks.SHORT_GRASS.defaultBlockState(), 2);
        helper.assertTrue(ReferenceSurfaceCleanupFeature.removeUnsupported(level, pos), "Unsupported grass survived on exposed rock");
        level.setBlock(pos.below(), Blocks.DIRT.defaultBlockState(), 2);
        level.setBlock(pos, Blocks.SHORT_GRASS.defaultBlockState(), 2);
        helper.assertTrue(!ReferenceSurfaceCleanupFeature.removeUnsupported(level, pos), "Cleanup removed supported foliage");
        level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(pos, Blocks.TORCH.defaultBlockState(), 2);
        helper.assertTrue(!ReferenceSurfaceCleanupFeature.removeUnsupported(level, pos)
                && level.getBlockState(pos).is(Blocks.TORCH), "Cleanup removed a non-foliage structure state");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void cleanupScansFoliagePlacedAfterTheWorldgenHeightmapWasPrimed(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "wasteland_test")));
        helper.assertTrue(level != null, "Missing production-equivalent wasteland test world");
        var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null, "Wasteland noise router has no reference terrain");
        var levels = terrain.levels();
        BlockPos ground = null;
        // Keep this fixture separate from the origin-area production terrain probes.
        search: for (int z = 4104; z <= 6152; z += 128) for (int x = 4104; x <= 6152; x += 128) {
            var raw = terrain.sampleRaw(x, z);
            if (raw.height < .35F || raw.terrain.isSubmerged() || raw.terrain.isWetland()) continue;
            var cell = terrain.sample(x, z);
            int y = levels.scale(cell.height);
            if (cell.terrain.isSubmerged() || cell.terrain.isWetland()
                    || y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight() - 9) continue;
            ground = new BlockPos(x, y, z);
            break search;
        }
        helper.assertTrue(ground != null, "No dry surface fixture found for late foliage cleanup");
        var chunk = level.getChunk(ground.getX() >> 4, ground.getZ() >> 4);
        List<BlockState> original = new ArrayList<>();
        for (int y = ground.getY(); y < chunk.getMaxBuildHeight(); y++) {
            original.add(chunk.getBlockState(new BlockPos(ground.getX(), y, ground.getZ())));
        }
        try {
            for (int y = ground.getY() + 1; y < chunk.getMaxBuildHeight(); y++) {
                chunk.setBlockState(new BlockPos(ground.getX(), y, ground.getZ()), Blocks.AIR.defaultBlockState(), false);
            }
            chunk.setBlockState(ground, Blocks.STONE.defaultBlockState(), false);
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG));
            // As during FEATURES, block placement updates final heightmaps but leaves the WG map behind.
            BlockPos foliage = ground.above();
            chunk.setBlockState(foliage, Blocks.SHORT_GRASS.defaultBlockState(), false);
            helper.assertTrue(chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, ground.getX(), ground.getZ()) == ground.getY(),
                    "Fixture did not retain its pre-decoration WG heightmap");
            helper.assertTrue(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, ground.getX(), ground.getZ()) == foliage.getY(),
                    "Fixture foliage did not update the final surface heightmap");
            var feature = new ReferenceSurfaceCleanupFeature(NoneFeatureConfiguration.CODEC);
            helper.assertTrue(feature.place(new FeaturePlaceContext<>(Optional.empty(), level,
                            level.getChunkSource().getGenerator(), RandomSource.create(42), ground,
                            NoneFeatureConfiguration.INSTANCE)),
                    "Late cleanup did not scan foliage above the stale WG heightmap");
            helper.assertTrue(chunk.getBlockState(foliage).isAir(), "Unsupported feature-stage foliage survived cleanup");
            helper.assertTrue(chunk.getBlockState(ground).is(Blocks.STONE), "Foliage cleanup modified terrain");
        } finally {
            for (int i = 0; i < original.size(); i++) {
                chunk.setBlockState(ground.above(i), original.get(i), false);
            }
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG));
        }
        helper.succeed();
    }


    @GameTest(template = "empty")
    public static void weatheringVariesSoilRetreatOnMarginalSlopesWithoutCoatingGentleGround(GameTestHelper helper) {
        var chunk = chunk(helper);
        var config = new net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.SurfaceSettings.Erosion(
                0, 1000, 0, 0, .55F, .2F, .9F);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 4; x <= 6; x++) {
            for (int dy = 0; dy < 3; dy++) chunk.setBlockState(pos.set(x, 128 - dy, 4), Blocks.GRASS_BLOCK.defaultBlockState(), false);
            chunk.setBlockState(pos.set(x, 125, 4), Blocks.TUFF.defaultBlockState(), false);
        }
        ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos.set(4, 128, 4), 128, cell(.7F, .55F, 0),
                config, Noises.zero(), Blocks.GRANITE.defaultBlockState(), .1);
        ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos.set(5, 128, 4), 128, cell(.7F, .55F, 0),
                config, Noises.zero(), Blocks.GRANITE.defaultBlockState(), .9);
        helper.assertTrue(chunk.getBlockState(new BlockPos(4, 128, 4)).is(Blocks.COARSE_DIRT),
                "Sheltered marginal slope did not retain intermediate soil");
        helper.assertTrue(chunk.getBlockState(new BlockPos(5, 128, 4)).is(Blocks.TUFF),
                "Weathered marginal slope did not expose its own host rock");
        ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos.set(6, 128, 4), 128, cell(.7F, .1F, 0),
                config, Noises.zero(), Blocks.GRANITE.defaultBlockState(), .9);
        helper.assertTrue(chunk.getBlockState(new BlockPos(6, 128, 4)).is(Blocks.GRASS_BLOCK),
                "Weathering created a random mineral patch on gentle low ground");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void exposureRejectsWholeSoilCoverAboveShallowOresDepositsAndFluids(GameTestHelper helper) {
        var chunk = chunk(helper);
        var config = Presets.makeDefault().surface().erosion();
        BlockState deposit = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:hematite_deposit")).defaultBlockState();
        helper.assertTrue(!deposit.isAir(), "Required compound deposit is missing from the verification registry");
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(6, 128, 6);
        for (BlockState protectedState : List.of(Blocks.DIAMOND_ORE.defaultBlockState(), deposit,
                Blocks.CHEST.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState())) {
            for (int depth = 1; depth <= 3; depth++) {
                for (int dy = 0; dy < 4; dy++) chunk.setBlockState(pos.setY(128 - dy), Blocks.DIRT.defaultBlockState(), false);
                chunk.setBlockState(pos.setY(124), Blocks.TUFF.defaultBlockState(), false);
                chunk.setBlockState(pos.setY(128 - depth), protectedState, false);
                helper.assertTrue(!ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos, 128, cell(.7F, 1F, 0),
                                config, Noises.zero(), Blocks.STONE.defaultBlockState()),
                        "Exposure crossed a shallow protected layer or replaced the soil covering it");
                helper.assertTrue(chunk.getBlockState(pos.setY(128 - depth)) == protectedState,
                        "Weathering changed a shallow ore, compound deposit, structure block or fluid");
                for (int dy = 0; dy < depth; dy++) helper.assertTrue(chunk.getBlockState(pos.setY(128 - dy)).is(Blocks.DIRT),
                        "Rejected shallow protected layer left a partial rock exposure");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void weatheringNeverInventsRockWithoutANativeHostOrCrossesTheOwningChunk(GameTestHelper helper) {
        var chunk = chunk(helper);
        var config = Presets.makeDefault().surface().erosion();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(8, 128, 8);
        for (int dy = 0; dy < 12; dy++) chunk.setBlockState(pos.setY(128 - dy), Blocks.DIRT.defaultBlockState(), false);
        helper.assertTrue(!ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos, 128, cell(.7F, 1F, 0),
                        config, Noises.zero(), Blocks.GRANITE.defaultBlockState()),
                "Weathering invented fallback rock above a cave or deep soil without native geology");
        for (int dy = 0; dy < 12; dy++) helper.assertTrue(chunk.getBlockState(pos.setY(128 - dy)).is(Blocks.DIRT),
                "Rejected exposure changed the soil above missing native geology");
        chunk.setBlockState(pos.setY(125), Blocks.TUFF.defaultBlockState(), false);
        chunk.setBlockState(pos.setY(129), Blocks.STONE_BRICKS.defaultBlockState(), false);
        helper.assertTrue(!ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos, 128, cell(.7F, 1F, 0),
                        config, Noises.zero(), Blocks.STONE.defaultBlockState()),
                "Weathering stripped covered soil underneath a structure");
        chunk.setBlockState(pos.setY(129), Blocks.AIR.defaultBlockState(), false);
        helper.assertTrue(!ReferenceSurfaceExposureFeature.erodeColumn(chunk, pos.set(24, 128, 8), 128,
                        cell(.7F, 1F, 0), config, Noises.zero(), Blocks.STONE.defaultBlockState()),
                "Exposure accepted a position belonging to a different chunk");
        helper.succeed();
    }

    private static ProtoChunk chunk(GameTestHelper helper) {
        return new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
    }

    private static Cell cell(float height, float gradient, float sediment) {
        Cell cell = new Cell();
        cell.terrain = TerrainType.HILLS;
        cell.height = height;
        cell.gradient = gradient;
        cell.sediment = sediment;
        return cell;
    }
}

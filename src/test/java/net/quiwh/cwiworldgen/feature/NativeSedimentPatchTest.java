package net.quiwh.cwiworldgen.feature;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedBlockStateProvider;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeSedimentPatchTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void fallingSedimentRequiresContinuousSupportedGround(GameTestHelper helper) {
        var level = helper.getLevel();
        var config = new DiskConfiguration(RuleBasedBlockStateProvider.simple(Blocks.SAND),
                BlockPredicate.matchesBlocks(Blocks.DIRT), ConstantInt.of(4), 2);
        BlockPos floor = helper.absolutePos(new BlockPos(4, 12, 4));
        level.setBlock(floor, Blocks.DIRT.defaultBlockState(), 2);
        level.setBlock(floor.below(), Blocks.AIR.defaultBlockState(), 2);
        for (int above = 1; above <= 4; above++) level.setBlock(floor.above(above), Blocks.WATER.defaultBlockState(), 2);
        prime(helper, floor);
        helper.assertTrue(!IrregularSedimentFeature.placeColumn(level, config, RandomSource.create(42),
                        floor.getX(), floor.getZ(), 3), "Falling sediment was placed above an unsupported cave opening");
        helper.assertTrue(level.getBlockState(floor).is(Blocks.DIRT), "Unsupported sediment edit was not atomic");
        BlockPos supported = floor.offset(2, 0, 0);
        level.setBlock(supported, Blocks.DIRT.defaultBlockState(), 2);
        level.setBlock(supported.below(), Blocks.DIRT.defaultBlockState(), 2);
        level.setBlock(supported.below(2), Blocks.STONE.defaultBlockState(), 2);
        for (int above = 1; above <= 4; above++) level.setBlock(supported.above(above), Blocks.WATER.defaultBlockState(), 2);
        prime(helper, supported);
        helper.assertTrue(IrregularSedimentFeature.placeColumn(level, config, RandomSource.create(42),
                        supported.getX(), supported.getZ(), 3), "Supported sediment lens above non-target stone was rejected");
        helper.assertTrue(level.getBlockState(supported).is(Blocks.SAND)
                        && level.getBlockState(supported.below()).is(Blocks.SAND)
                        && level.getBlockState(supported.below(2)).is(Blocks.STONE),
                "Sediment lens failed to stop at its supported target boundary");
        System.out.println("CWI_SEDIMENT_SUPPORT=unsupported_rejected:true,supported_lens:true");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void sedimentFollowsSlopingSubmergedFloorsWithoutChangingWaterOrDeepStrata(GameTestHelper helper) {
        var level = helper.getLevel();
        var config = new DiskConfiguration(RuleBasedBlockStateProvider.simple(Blocks.GRAVEL),
                BlockPredicate.matchesBlocks(Blocks.DIRT), ConstantInt.of(4), 2);
        BlockPos origin = helper.absolutePos(new BlockPos(2, 12, 2));
        for (int index = 0; index < 3; index++) {
            BlockPos floor = origin.offset(index * 2, index, 0);
            for (int depth = 0; depth < 4; depth++) level.setBlock(floor.below(depth), Blocks.DIRT.defaultBlockState(), 2);
            for (int above = 1; above <= 4; above++) level.setBlock(floor.above(above), Blocks.WATER.defaultBlockState(), 2);
            prime(helper, floor);
            if (index == 0) System.out.println("CWI_SEDIMENT_WATER=actual_water:true,fluid_tag:"
                    + level.getFluidState(floor.above()).is(net.minecraft.tags.FluidTags.WATER));
            helper.assertTrue(IrregularSedimentFeature.placeColumn(level, config, RandomSource.create(42),
                            floor.getX(), floor.getZ(), 99), "Sloping underwater sediment column was rejected");
            for (int depth = 0; depth < 3; depth++) helper.assertTrue(level.getBlockState(floor.below(depth)).is(Blocks.GRAVEL),
                    "Sediment lens failed to follow the actual exposed sloping floor");
            helper.assertTrue(level.getBlockState(floor.below(3)).is(Blocks.DIRT),
                    "Sediment lens exceeded its three-block surface limit");
            for (int above = 1; above <= 4; above++) helper.assertTrue(level.getBlockState(floor.above(above)).is(Blocks.WATER)
                            && level.getFluidState(floor.above(above)).isSource(),
                    "Sediment patch displaced or changed source water");
        }
        System.out.println("CWI_SEDIMENT_SLOPE=columns:3,depth_cap:3,water_unchanged:true");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void sedimentRejectsDryLandRoofsOresAndBuiltBlocksEvenWithBroadTargets(GameTestHelper helper) {
        var level = helper.getLevel();
        var config = new DiskConfiguration(RuleBasedBlockStateProvider.simple(Blocks.CLAY),
                BlockPredicate.alwaysTrue(), ConstantInt.of(4), 3);
        BlockPos origin = helper.absolutePos(new BlockPos(2, 12, 2));
        BlockState[] material = {Blocks.DIRT.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.CHEST.defaultBlockState()};
        for (int index = 0; index < material.length; index++) {
            BlockPos floor = origin.offset(index * 2, 0, 0);
            level.setBlock(floor, material[index], 2);
            level.setBlock(floor.below(), Blocks.DIRT.defaultBlockState(), 2);
            for (int above = 1; above <= 4; above++) level.setBlock(floor.above(above),
                    index == 0 ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState(), 2);
            if (index == 1) level.setBlock(floor.above(5), Blocks.STONE.defaultBlockState(), 2);
            prime(helper, floor);
            helper.assertTrue(!IrregularSedimentFeature.placeColumn(level, config, RandomSource.create(42),
                            floor.getX(), floor.getZ(), 3), "Sediment patch changed dry, roofed or protected terrain");
            helper.assertTrue(level.getBlockState(floor).equals(material[index]),
                    "Sediment patch overwrote a protected floor block");
        }
        System.out.println("CWI_SEDIMENT_GUARDS=dry:true,roofed:true,ore:true,built:true,block_entity:true");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void clayFormsDeepLensesDirectlyOverDirtAndGravelWithoutSand(GameTestHelper helper) {
        var level = helper.getLevel();
        var config = new DiskConfiguration(RuleBasedBlockStateProvider.simple(Blocks.CLAY),
                BlockPredicate.matchesBlocks(Blocks.DIRT, Blocks.GRAVEL), ConstantInt.of(2), 4);
        BlockPos origin = helper.absolutePos(new BlockPos(3, 12, 3));
        for (int index = 0; index < 2; index++) {
            BlockPos floor = origin.offset(index * 3, 0, 0);
            for (int depth = 0; depth < 5; depth++) level.setBlock(floor.below(depth),
                    (index == 0 ? Blocks.DIRT : Blocks.GRAVEL).defaultBlockState(), 2);
            level.setBlock(floor.below(5), Blocks.STONE.defaultBlockState(), 2);
            for (int above = 1; above <= 4; above++) level.setBlock(floor.above(above), Blocks.WATER.defaultBlockState(), 2);
            prime(helper, floor);
            helper.assertTrue(IrregularSedimentFeature.placeColumn(level, config, RandomSource.create(42),
                            floor.getX(), floor.getZ(), 5), "Clay still depends on an existing sand patch");
            for (int depth = 0; depth < 5; depth++) helper.assertTrue(level.getBlockState(floor.below(depth)).is(Blocks.CLAY),
                    "Fine sediment never forms the configured five-block core");
            helper.assertTrue(level.getBlockState(floor.below(5)).is(Blocks.STONE)
                            && level.getBlockState(floor.above()).is(Blocks.WATER),
                    "Deep clay changed support or displaced water");
        }
        System.out.println("CWI_SEDIMENT_FINE_LENS=clay_over:dirt,gravel,depth:5,water:unchanged");
        helper.succeed();
    }

    private static void prime(GameTestHelper helper, BlockPos pos) {
        Heightmap.primeHeightmaps(helper.getLevel().getChunkAt(pos),
                EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
    }
}

package net.quiwh.cwiworldgen.feature;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.SimpleWeightedRandomList;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeFeatureTest {
    @GameTest(template = "empty")
    public static void thermalLayerHeightBoundsRejectWritesAboveTheAllowedBand(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 4, 4));
        var empty = new PlacedFeature(Holder.direct(new ConfiguredFeature<>(Feature.NO_OP, NoneFeatureConfiguration.INSTANCE)), List.of());
        var base = new CorrespondentLayersConfig(BlockTags.BASE_STONE_OVERWORLD,
            List.of(BlockStateProvider.simple(Blocks.DIRT)), Holder.direct(empty), CaveSurface.FLOOR,
            ConstantInt.of(1), 0, 5, 0, ConstantInt.of(1), 0, false);
        var encoded = CorrespondentLayersConfig.CODEC.encodeStart(JsonOps.INSTANCE, base).getOrThrow().getAsJsonObject();
        var bounds = new com.google.gson.JsonObject();
        bounds.addProperty("min_y", origin.getY() - 8);
        bounds.addProperty("max_y", origin.getY() - 1);
        encoded.add("height_bounds", bounds);
        var bounded = CorrespondentLayersConfig.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        level.setBlock(origin, Blocks.STONE.defaultBlockState(), 2);
        var feature = new CorrespondentLayersFeature(CorrespondentLayersConfig.CODEC);
        feature.placeGround(level, bounded, state -> state.is(Blocks.STONE), RandomSource.create(42), origin.mutable(), 1);
        check(level.getBlockState(origin).is(Blocks.STONE), "Thermal layers escaped their configured actual-Y cap");
        level.setBlock(origin.below(), Blocks.STONE.defaultBlockState(), 2);
        feature.placeGround(level, bounded, state -> state.is(Blocks.STONE), RandomSource.create(42), origin.below().mutable(), 1);
        check(level.getBlockState(origin.below()).is(Blocks.DIRT), "Height cap rejected valid thermal terrain inside its band");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void surfaceLayersStopAtUnreplaceableBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 4, 4));
        var empty = new PlacedFeature(Holder.direct(new ConfiguredFeature<>(Feature.NO_OP, NoneFeatureConfiguration.INSTANCE)), List.of());
        var config = new CorrespondentLayersConfig(BlockTags.BASE_STONE_OVERWORLD,
            List.of(BlockStateProvider.simple(Blocks.DIRT), BlockStateProvider.simple(Blocks.SANDSTONE)),
            Holder.direct(empty), CaveSurface.FLOOR, ConstantInt.of(1), 0, 5, 0, ConstantInt.of(1), 0, false);
        var feature = new CorrespondentLayersFeature(CorrespondentLayersConfig.CODEC);
        level.setBlock(origin, Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(origin.below(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(origin.below(2), Blocks.STONE.defaultBlockState(), 2);
        check(feature.placeGround(level, config, state -> state.is(Blocks.STONE), RandomSource.create(42), origin.mutable(), 1), "surface was not placed");
        check(level.getBlockState(origin).is(Blocks.DIRT), "first layer must be the surface palette state");
        check(level.getBlockState(origin.below()).is(Blocks.SANDSTONE), "second layer must follow even when sampled depth is one");
        check(level.getBlockState(origin.below(2)).is(Blocks.STONE), "ground beyond configured layers must remain intact");
        level.setBlock(origin, Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(origin.below(), Blocks.BEDROCK.defaultBlockState(), 2);
        feature.placeGround(level, config, state -> state.is(Blocks.STONE), RandomSource.create(42), origin.mutable(), 8);
        check(level.getBlockState(origin.below()).is(Blocks.BEDROCK), "surface layers must stop at unreplaceable blocks");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void floorPlacementHonorsItsSuccessfulPlacementLimit(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 3, 4));
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(origin.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 2);
        }
        var config = new RandomFloorPlacementConfig(new WeightedStateProvider(SimpleWeightedRandomList.single(Blocks.MOSS_CARPET.defaultBlockState())),
            HolderSet.direct(Blocks.STONE.builtInRegistryHolder()), 40, 2, 0, 1);
        var feature = new RandomFloorPlacementFeature(RandomFloorPlacementConfig.CODEC);
        boolean placed = feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(), RandomSource.create(61), origin, config));
        int count = 0;
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) if (level.getBlockState(origin.offset(x, 0, z)).is(Blocks.MOSS_CARPET)) count++;
        check(placed && count == 1, "expected exactly one successful floor placement, got " + count);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void gemstoneRetainsWaterAndFacesItsSupport(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(4, 4, 4));
        level.setBlock(origin, Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(origin.below(), Blocks.STONE.defaultBlockState(), 2);
        var config = new PaletteConfiguration(Map.of("amber_cluster", Blocks.AMETHYST_CLUSTER.defaultBlockState()));
        var feature = new GemstoneFeature(PaletteConfiguration.codec("amber_cluster"));
        check(feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(), RandomSource.create(61), origin, config)), "gemstone did not place");
        var result = level.getBlockState(origin);
        check(result.getValue(AmethystClusterBlock.FACING) == Direction.UP, "gemstone must face away from its supporting floor");
        check(result.getValue(AmethystClusterBlock.WATERLOGGED), "submerged gemstone must retain its water");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void paletteCodecRejectsMissingRequiredBlocks(GameTestHelper helper) {
        var result = PaletteConfiguration.codec("amber_cluster").parse(JsonOps.INSTANCE, JsonParser.parseString("{\"palette\":{}}"));
        check(!result.isSuccess(), "missing palette blocks must fail during data loading");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mappedTerrainRetainsOriginalWorldgenTags(GameTestHelper helper) {
        for (String name : List.of("darkslate", "phyllite", "quartzite", "conglomerate", "mossy_phyllite")) {
            var id = ResourceLocation.fromNamespaceAndPath("kubejs", name);
            check(BuiltInRegistries.BLOCK.containsKey(id), "missing mapped terrain block " + id);
            var state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
            check(state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES), id + " must receive original Darker Depths ores");
            check(state.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES), id + " must receive original cave carvers");
            if (name.equals("darkslate")) {
                check(state.is(BlockTags.BASE_STONE_OVERWORLD), "darkslate must support the original base-stone features");
            } else {
                check(state.is(BlockTags.LUSH_GROUND_REPLACEABLE), id + " must retain its original lush-ground membership");
            }
        }
        var quartziteDeepslate = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("kubejs", "quartzite_deepslate"));
        check(quartziteDeepslate.defaultBlockState().is(BlockTags.OVERWORLD_CARVER_REPLACEABLES),
            "quartzite deepslate must receive original cave carvers");
        check(Blocks.PACKED_MUD.defaultBlockState().is(BlockTags.DEEPSLATE_ORE_REPLACEABLES),
            "original packed-mud ore replacement must survive dependency removal");
        helper.succeed();
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}

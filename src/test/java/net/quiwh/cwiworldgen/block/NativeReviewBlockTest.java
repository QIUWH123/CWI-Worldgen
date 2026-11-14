package net.quiwh.cwiworldgen.block;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.feature.HugeGlowshroomFeature;
import net.quiwh.cwiworldgen.feature.PaletteConfiguration;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeReviewBlockTest {
    @GameTest(template = "empty")
    public static void mossySproutsSurviveOnTheActualGrimeFloorPalette(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(3, 3, 3));
        var sprouts = WorldgenBlocks.get("mossy_sprouts").defaultBlockState();
        for (String id : List.of("kubejs:phyllite", "kubejs:mossy_phyllite", "minecraft:dirt", "minecraft:moss_block")) {
            var floor = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
            helper.assertTrue(!floor.isAir(), "Missing intended sprouts floor " + id);
            level.setBlock(origin.below(), floor, 2);
            helper.assertTrue(sprouts.canSurvive(level, origin), "Mossy sprouts rejected grime floor " + id);
            level.setBlock(origin, sprouts, 2);
            var updated = sprouts.updateShape(Direction.DOWN, floor, level, origin, origin.below());
            helper.assertTrue(updated.is(sprouts.getBlock()), "Mossy sprouts broke on a floor neighbor update: " + id);
        }
        for (Block floor : List.of(Blocks.AIR, Blocks.STONE, Blocks.LAVA)) {
            level.setBlock(origin.below(), floor.defaultBlockState(), 2);
            helper.assertTrue(!sprouts.canSurvive(level, origin), "Mossy sprouts accepted unrelated floor " + floor);
        }
        var dry = WorldgenBlocks.get("dry_sprouts").defaultBlockState();
        level.setBlock(origin.below(), BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:quartzite")).defaultBlockState(), 2);
        helper.assertTrue(dry.canSurvive(level, origin), "Dry sprouts lost their arid rock support");
        level.setBlock(origin.below(), BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:phyllite")).defaultBlockState(), 2);
        helper.assertTrue(!dry.canSurvive(level, origin), "Wet rock support leaked into dry sprouts");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oreConversionInitializesTheActivatedHuskHeatBeforeItsFirstGrowthTick(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(3, 3, 3));
        var living = (LivingCrystalBlock) WorldgenBlocks.get("living_crystal");
        var husk = (CrystalHuskBlock) WorldgenBlocks.get("dead_living_crystal");
        level.setBlock(origin, living.defaultBlockState(), 2);
        for (Direction direction : Direction.values()) {
            level.setBlock(origin.relative(direction), Blocks.DIAMOND_ORE.defaultBlockState(), 2);
            level.setBlock(origin.relative(direction, 2), Blocks.LAVA.defaultBlockState(), 2);
        }
        helper.assertTrue(HeatPropagation.neighborHeat(level, origin) == 0, "Living source must be outside direct heat range");
        var random = RandomSource.create(765);
        BlockPos converted = null;
        for (int tick = 0; tick < 100 && converted == null; tick++) {
            living.randomTick(level.getBlockState(origin), level, origin, random);
            for (Direction direction : Direction.values()) {
                var target = origin.relative(direction);
                if (level.getBlockState(target).is(husk)) converted = target;
            }
        }
        helper.assertTrue(converted != null, "Seed did not exercise the actual living-crystal ore conversion");
        var activated = level.getBlockState(converted);
        helper.assertTrue(activated.getValue(CrystalHuskBlock.GROWTH) == 1, "Ore conversion failed to activate the husk");
        helper.assertTrue(activated.getValue(CrystalHuskBlock.HEAT) == 4,
                "Converted husk started cold beside lava before its scheduled heat update");
        husk.randomTick(activated, level, converted, random);
        helper.assertTrue(level.getBlockState(converted).getValue(CrystalHuskBlock.GROWTH) == 1,
                "Activated hot husk grew before heat recalculation");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void slagSlabLootReturnsBothHalvesOfADoubleSlab(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(3, 3, 3));
        var slab = WorldgenBlocks.get("slag_bricks_slab");
        for (SlabType type : SlabType.values()) {
            var state = slab.defaultBlockState().setValue(SlabBlock.TYPE, type);
            var drops = Block.getDrops(state, level, origin, null, null, new ItemStack(Items.DIAMOND_PICKAXE));
            int count = drops.stream().filter(stack -> stack.is(slab.asItem())).mapToInt(ItemStack::getCount).sum();
            helper.assertTrue(count == (type == SlabType.DOUBLE ? 2 : 1),
                    "Actual loaded slab loot returned " + count + " items for " + type);
            helper.assertTrue(drops.stream().allMatch(stack -> stack.is(slab.asItem())), "Slab loot returned an unrelated item");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void obstructedHugeGlowshroomsRejectWithoutAnyBlockWrites(GameTestHelper helper) {
        var feature = new HugeGlowshroomFeature(PaletteConfiguration.CODEC);
        var palette = glowshroomPalette();
        for (int height = 2; height <= 4; height++) {
            long seed = seedForHeight(height);
            var clear = new MushroomFixture();
            helper.assertTrue(feature.place(new FeaturePlaceContext<>(Optional.empty(), clear.level,
                    helper.getLevel().getChunkSource().getGenerator(), RandomSource.create(seed), clear.origin, palette)),
                    "Clear mushroom of height " + height + " failed to grow");
            helper.assertTrue(clear.writes > 15 && clear.blocks.get(clear.origin).is(WorldgenBlocks.get("glowshroom_stem")),
                    "Successful mushroom omitted its stem or canopy");
            for (BlockPos obstacle : List.of(clear.origin.above(), clear.origin.offset(height == 4 ? 2 : 1, height == 4 ? 2 : height, 0))) {
                var blocked = new MushroomFixture();
                blocked.blocks.put(obstacle, Blocks.STONE.defaultBlockState());
                var before = Map.copyOf(blocked.blocks);
                helper.assertTrue(!feature.place(new FeaturePlaceContext<>(Optional.empty(), blocked.level,
                        helper.getLevel().getChunkSource().getGenerator(), RandomSource.create(seed), blocked.origin, palette)),
                        "Obstructed mushroom of height " + height + " reported success");
                helper.assertTrue(blocked.writes == 0 && before.equals(blocked.blocks),
                        "Rejected mushroom wrote a partial stem or canopy");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bonemealUnderALowCeilingPreservesTheOriginalGlowshroom(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(3, 2, 3));
        var mushroom = (GlowshroomBlock) WorldgenBlocks.get("glowshroom");
        for (BlockPos cell : BlockPos.betweenClosed(origin.offset(-2, 0, -2), origin.offset(2, 7, 2))) {
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), 2);
        }
        level.setBlock(origin.below(), Blocks.STONE.defaultBlockState(), 2);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            level.setBlock(origin.offset(x, 2, z), Blocks.STONE.defaultBlockState(), 2);
        }
        var state = mushroom.defaultBlockState();
        level.setBlock(origin, state, 2);
        Map<BlockPos, BlockState> before = new HashMap<>();
        for (BlockPos cell : BlockPos.betweenClosed(origin.offset(-2, 0, -2), origin.offset(2, 7, 2))) {
            before.put(cell.immutable(), level.getBlockState(cell));
        }
        mushroom.performBonemeal(level, RandomSource.create(98), origin, state);
        for (var entry : before.entrySet()) helper.assertTrue(level.getBlockState(entry.getKey()).equals(entry.getValue()),
                "Low-ceiling bonemeal replaced the original mushroom or left a partial huge mushroom at " + entry.getKey());
        helper.succeed();
    }

    private static long seedForHeight(int height) {
        for (long seed = 0; seed < 100; seed++) if (RandomSource.create(seed).nextInt(3) + 2 == height) return seed;
        throw new AssertionError("No deterministic mushroom height seed");
    }

    private static PaletteConfiguration glowshroomPalette() {
        return new PaletteConfiguration(Map.of("mossy_grimestone", Blocks.MOSS_BLOCK.defaultBlockState(),
                "glowshroom", WorldgenBlocks.get("glowshroom").defaultBlockState(),
                "glowshroom_stem", WorldgenBlocks.get("glowshroom_stem").defaultBlockState(),
                "glowshroom_block", WorldgenBlocks.get("glowshroom_block").defaultBlockState(),
                "glimmering_vines", WorldgenBlocks.get("glimmering_vines").defaultBlockState(),
                "glowshroom_heart", WorldgenBlocks.get("glowshroom_heart").defaultBlockState()));
    }

    /** Isolated native feature region: count writes, including writes later overwritten. */
    private static final class MushroomFixture {
        final BlockPos origin = new BlockPos(8, 80, 8);
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        int writes;
        final WorldGenLevel level = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getBlockState" -> state((BlockPos) args[0]);
                    case "isStateAtPosition" -> ((Predicate<BlockState>) args[1]).test(state((BlockPos) args[0]));
                    case "isOutsideBuildHeight" -> args[0] instanceof BlockPos pos ? pos.getY() < -64 || pos.getY() >= 320
                            : (int) args[0] < -64 || (int) args[0] >= 320;
                    case "getMinBuildHeight" -> -64;
                    case "getHeight" -> 384;
                    case "setBlock" -> { writes++; blocks.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]); yield true; }
                    default -> throw new UnsupportedOperationException("Unexpected mushroom region operation " + method.getName());
                });

        MushroomFixture() {
            blocks.put(origin.below(), Blocks.MOSS_BLOCK.defaultBlockState());
            blocks.put(origin, WorldgenBlocks.get("glowshroom").defaultBlockState());
        }

        private BlockState state(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    }
}

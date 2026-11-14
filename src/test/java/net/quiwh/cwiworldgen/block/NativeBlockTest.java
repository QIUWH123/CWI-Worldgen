package net.quiwh.cwiworldgen.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeBlockTest {
    @GameTest(template = "empty")
    public static void oldPetrifiedIdsDecodeToCoreWithoutDuplicateBlocks(GameTestHelper helper) {
        for (String name : java.util.List.of("petrified_log", "stripped_petrified_log", "stripped_petrified_wood",
                "porous_petrified_log", "petrified_roots", "petrified_roots_plant", "amber_cluster", "rope")) {
            var old = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cwi_worldgen", name);
            var target = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cwi", name.equals("rope") ? "reinforced_rope" : name);
            var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
            check(registry.get(old) == registry.get(target), "Legacy block alias " + old);
            check(registry.getKey(registry.get(old)).equals(target), "Core owns canonical block ID " + target);
            var tag = new net.minecraft.nbt.CompoundTag(); tag.putString("Name", old.toString());
            var decoded = net.minecraft.world.level.block.state.BlockState.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).getOrThrow();
            check(decoded.is(registry.get(target)), "Saved palette decodes legacy ID " + old);
            if (!name.equals("petrified_roots_plant")) {
                var item = new net.minecraft.nbt.CompoundTag(); item.putString("id", old.toString()); item.putInt("count", 2);
                var stack = ItemStack.parse(helper.getLevel().registryAccess(), item).orElseThrow();
                check(stack.is(registry.get(target).asItem()) && stack.getCount() == 2, "Saved inventory decodes legacy ID " + old);
            }
        }
        var amber = net.minecraft.core.registries.BuiltInRegistries.ITEM;
        check(amber.get(net.minecraft.resources.ResourceLocation.parse("cwi_worldgen:amber"))
                == amber.get(net.minecraft.resources.ResourceLocation.parse("cwi:amber")), "Amber item alias");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void quartzGrowthRetainsWaterAndStopsAtCrystal(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(3, 3, 3));
        var budding = (FamilyBuddingBlock) WorldgenBlocks.get("budding_nether_quartz");
        level.setBlock(origin, budding.defaultBlockState(), 2);
        for (Direction direction : Direction.values()) level.setBlock(origin.relative(direction), Blocks.WATER.defaultBlockState(), 2);
        var random = RandomSource.create(98);
        for (int i = 0; i < 2000; i++) budding.randomTick(budding.defaultBlockState(), level, origin, random);
        for (Direction direction : Direction.values()) {
            var state = level.getBlockState(origin.relative(direction));
            check(state.is(WorldgenBlocks.get("nether_quartz_crystal")), "growth did not reach the proper crystal family");
            check(state.getValue(AmethystClusterBlock.FACING) == direction, "crystal lost its direction");
            check(state.getValue(BlockStateProperties.WATERLOGGED), "crystal lost its source water");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void hangingPlantSupportsItsOwnBodyAndRejectsAir(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(3, 4, 3));
        var head = WorldgenBlocks.get("petrified_roots").defaultBlockState();
        var body = WorldgenBlocks.get("petrified_roots_plant").defaultBlockState();
        level.setBlock(origin.above(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(origin, body, 2);
        check(head.canSurvive(level, origin.below()), "root head must attach to its root body");
        check(!head.canSurvive(level, origin.below(2)), "root head must not float unsupported");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void magmaPadRequiresSourceLavaBelow(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(3, 3, 3));
        var state = WorldgenBlocks.get("magma_pad").defaultBlockState();
        level.setBlock(origin.below(), Blocks.LAVA.defaultBlockState(), 2);
        check(state.canSurvive(level, origin), "magma pad must survive above source lava");
        level.setBlock(origin.below(), Blocks.WATER.defaultBlockState(), 2);
        check(!state.canSurvive(level, origin), "magma pad must reject water as a substitute");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void crystalReactivationAndMelonCycleRemainFunctional(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(new BlockPos(3, 3, 3));
        var husk = (CrystalHuskBlock) WorldgenBlocks.get("dead_living_crystal");
        var random = RandomSource.create(765);
        level.setBlock(origin,husk.defaultBlockState().setValue(CrystalHuskBlock.GROWTH,1),2);
        for (int i=0;i<3;i++) husk.randomTick(level.getBlockState(origin),level,origin,random);
        check(level.getBlockState(origin).is(WorldgenBlocks.get("living_crystal")),"activated husk did not become a living crystal");
        var living = (LivingCrystalBlock) WorldgenBlocks.get("living_crystal");
        for (Direction direction : Direction.values()) level.setBlock(origin.relative(direction),Blocks.MELON.defaultBlockState(),2);
        for (int i=0;i<1000 && level.getBlockState(origin).is(living);i++) living.randomTick(level.getBlockState(origin),level,origin,random);
        check(level.getBlockState(origin).is(husk),"completed crystal melon failed to exhaust the living crystal");
        boolean found = false;
        for (Direction direction : Direction.values()) found |= level.getBlockState(origin.relative(direction)).is(WorldgenBlocks.get("crystal_melon"));
        check(found,"living crystal did not complete the melon growth cycle");
        helper.succeed();
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}

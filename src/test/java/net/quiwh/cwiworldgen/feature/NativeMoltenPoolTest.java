package net.quiwh.cwiworldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.quiwh.cwiworldgen.block.WorldgenBlocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeMoltenPoolTest {
    // Production pools reach at most eight blocks from their center; leave a solid rim.
    private static final int ARENA_RADIUS = 10;
    private static final int TRIALS = 16;

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void productionMoltenPoolsRetainLavaAndSourceSupportedMagmaPads(GameTestHelper helper) {
        var level = helper.getLevel();
        var configured = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        var removedBlockId = ResourceLocation.parse("cwi_worldgen:geyser");
        check(!BuiltInRegistries.BLOCK.containsKey(removedBlockId), "Removed geyser block remains registered");
        check(!BuiltInRegistries.ITEM.containsKey(removedBlockId), "Removed geyser item remains registered");
        check(!BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(removedBlockId), "Removed geyser block entity remains registered");
        check(!BuiltInRegistries.FEATURE.containsKey(ResourceLocation.parse("cwi_worldgen:geyser_under_fluid")),
            "Removed geyser feature remains registered");
        check(configured.getOptional(ResourceLocation.parse("cwi_worldgen:geyser_placement")).isEmpty(),
            "Removed geyser configured feature remains registered");

        var featureId = ResourceLocation.parse("cwi_worldgen:molten_pool");
        var feature = configured.getOptional(featureId)
            .orElseThrow(() -> new AssertionError("Missing production molten pool " + featureId));
        var darkslate = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:darkslate"));
        check(darkslate != Blocks.AIR, "Missing darkslate for the molten-pool floor");
        BlockPos floor = new BlockPos(30008, -32, -29992);
        RandomSource seeds = RandomSource.create(0x4D4F4C54454EL);
        int lava = 0;
        int pads = 0;
        int poolsWithPads = 0;
        for (int attempt = 0; attempt < TRIALS; attempt++) {
            prepareArena(helper, floor, darkslate.defaultBlockState());
            check(feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seeds.nextLong()), floor.above()),
                "Production molten pool failed on the even darkslate floor, trial " + attempt);
            Counts counts = countAndCheck(helper, floor);
            check(counts.lava() > 0, "Production molten pool placed no lava, trial " + attempt);
            lava += counts.lava();
            pads += counts.pads();
            if (counts.pads() > 0) poolsWithPads++;
        }
        check(pads > 0, "No magma pads across " + TRIALS + " production pools containing " + lava + " lava cells");

        // The retained pool algorithm rejects odd floor heights before vegetation placement.
        BlockPos oddFloor = floor.above();
        prepareArena(helper, oddFloor, darkslate.defaultBlockState());
        check(!feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(0x4D4F4C54454EL), oddFloor.above()),
            "Production molten pool accepted an odd floor height");
        Counts rejected = countAndCheck(helper, oddFloor);
        check(rejected.lava() == 0 && rejected.pads() == 0,
            "Rejected odd floor retained lava or magma pads from generation");
        System.out.println("CWI_MOLTEN_POOL=lava_cells:" + lava + "; magma_pads:" + pads
            + "; pools_with_pads:" + poolsWithPads + "/" + TRIALS + "; floor_y:" + floor.getY());
        helper.succeed();
    }

    private static void prepareArena(GameTestHelper helper, BlockPos floor, BlockState darkslate) {
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-ARENA_RADIUS, -3, -ARENA_RADIUS),
                floor.offset(ARENA_RADIUS, 5, ARENA_RADIUS))) {
            helper.getLevel().setBlock(pos, pos.getY() <= floor.getY() ? darkslate : Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static Counts countAndCheck(GameTestHelper helper, BlockPos floor) {
        var level = helper.getLevel();
        var magmaPad = WorldgenBlocks.get("magma_pad");
        int lava = 0;
        int pads = 0;
        for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-ARENA_RADIUS, -3, -ARENA_RADIUS),
                floor.offset(ARENA_RADIUS, 5, ARENA_RADIUS))) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.LAVA)) lava++;
            if (!state.is(magmaPad)) continue;
            pads++;
            check(pos.getY() == floor.getY() + 1, "Generated magma pad is outside the pool surface at " + pos);
            BlockState support = level.getBlockState(pos.below());
            check(support.is(Blocks.LAVA) && support.getFluidState().isSource(),
                "Generated magma pad has no source lava directly below at " + pos);
        }
        return new Counts(lava, pads);
    }

    private record Counts(int lava, int pads) {}

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}

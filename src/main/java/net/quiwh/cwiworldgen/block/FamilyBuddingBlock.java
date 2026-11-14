package net.quiwh.cwiworldgen.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AmethystBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;

/** Geode Plus growth rates and four-stage, direction-sensitive crystal families. */
public final class FamilyBuddingBlock extends AmethystBlock {
    private final String family;
    public FamilyBuddingBlock(Properties properties, String family) { super(properties); this.family = family; }
    @Override public MapCodec<? extends AmethystBlock> codec() {
        return simpleCodec(properties -> new FamilyBuddingBlock(properties, family));
    }
    @Override public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (random.nextInt(family.equals("echo") ? 15 : 5) != 0) return;
        Direction direction = Direction.values()[random.nextInt(6)];
        BlockPos target = pos.relative(direction);
        BlockState current = level.getBlockState(target);
        Block next = null;
        if (BuddingAmethystBlock.canClusterGrowAtState(current)) next = WorldgenBlocks.get("small_" + family + "_bud");
        else if (current.hasProperty(AmethystClusterBlock.FACING) && current.getValue(AmethystClusterBlock.FACING) == direction) {
            if (current.is(WorldgenBlocks.get("small_" + family + "_bud"))) next = WorldgenBlocks.get("medium_" + family + "_bud");
            else if (current.is(WorldgenBlocks.get("medium_" + family + "_bud"))) next = WorldgenBlocks.get("large_" + family + "_bud");
            else if (current.is(WorldgenBlocks.get("large_" + family + "_bud"))) next = WorldgenBlocks.get(family.equals("echo") ? "echo_crystal" : "nether_quartz_crystal");
        }
        if (next != null) {
            if (family.equals("echo")) {
                int radius = next == WorldgenBlocks.get("small_echo_bud") ? 5 : next == WorldgenBlocks.get("medium_echo_bud") ? 10
                    : next == WorldgenBlocks.get("large_echo_bud") ? 15 : 20;
                Warden.applyDarknessAround(level,Vec3.atCenterOf(pos),null,radius);
            }
            level.setBlockAndUpdate(target, next.defaultBlockState().setValue(AmethystClusterBlock.FACING, direction)
                .setValue(AmethystClusterBlock.WATERLOGGED, current.getFluidState().getType() == Fluids.WATER));
        }
    }
}

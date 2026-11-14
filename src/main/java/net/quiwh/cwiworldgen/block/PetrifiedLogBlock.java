package net.quiwh.cwiworldgen.block;

import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

public final class PetrifiedLogBlock extends RotatedPillarBlock {
    private final boolean strippable;
    public PetrifiedLogBlock(Properties properties, boolean strippable) { super(properties); this.strippable = strippable; }
    @Override public BlockState getToolModifiedState(BlockState state, UseOnContext context, ItemAbility ability, boolean simulate) {
        if (strippable && ability == ItemAbilities.AXE_STRIP && context.getItemInHand().canPerformAction(ability))
            return WorldgenBlocks.get("stripped_petrified_log").defaultBlockState().setValue(AXIS, state.getValue(AXIS));
        return super.getToolModifiedState(state, context, ability, simulate);
    }
}

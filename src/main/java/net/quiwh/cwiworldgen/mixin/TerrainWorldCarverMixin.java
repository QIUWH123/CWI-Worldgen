package net.quiwh.cwiworldgen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.core.BlockPos;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The context comes from the current carve call, so worker threads cannot leak scope. */
@Mixin(WorldCarver.class)
public abstract class TerrainWorldCarverMixin {
    @WrapOperation(method = "carveBlock", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/levelgen/carver/WorldCarver;canReplaceBlock(Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean cwiWorldgen$protectSourceWater(WorldCarver<?> carver, CarverConfiguration configuration,
            BlockState state, Operation<Boolean> original, @Local(argsOnly = true) CarvingContext context,
            @Local(argsOnly = true, ordinal = 0) BlockPos.MutableBlockPos position) {
        var terrain = ((TerrainRandomState) (Object) context.randomState()).cwiWorldgen$terrain();
        if (terrain != null) {
            if (terrain.generationVersion() >= 12 && terrain.waterProtection()
                    .column(position.getX(), position.getZ()).protects(position.getY())) return false;
            var fluid = state.getFluidState();
            if (fluid.isSourceOfType(Fluids.WATER) || fluid.isSourceOfType(Fluids.FLOWING_WATER)) return false;
        }
        return original.call(carver, configuration, state);
    }
}

package net.quiwh.cwiworldgen.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Climate;
import net.quiwh.cwiworldgen.terrain.ReferenceSpawnFinder;
import net.quiwh.cwiworldgen.terrain.TerrainClimateSampler;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Climate.Sampler.class)
public abstract class TerrainClimateSamplerMixin implements TerrainClimateSampler {
    @Unique private ReferenceTerrain cwiWorldgen$referenceTerrain;

    @Override public void cwiWorldgen$bindTerrain(@Nullable ReferenceTerrain terrain) {
        cwiWorldgen$referenceTerrain = terrain;
    }

    @Override @Nullable public ReferenceTerrain cwiWorldgen$terrain() { return cwiWorldgen$referenceTerrain; }

    @Inject(method = "findSpawnPosition", at = @At("HEAD"), cancellable = true)
    private void cwiWorldgen$findSourceSpawn(CallbackInfoReturnable<BlockPos> cir) {
        if (cwiWorldgen$referenceTerrain != null) {
            cir.setReturnValue(ReferenceSpawnFinder.find(cwiWorldgen$referenceTerrain, (Climate.Sampler) (Object) this));
        }
    }
}

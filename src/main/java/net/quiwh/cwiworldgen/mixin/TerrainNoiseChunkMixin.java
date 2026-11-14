package net.quiwh.cwiworldgen.mixin;

import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.block.state.BlockState;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.WaterProtectionColumn;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.ReferenceTerrainDensity;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscapeDensity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Full chunk density and biome sampling use filtered tiles; sparse columns stay inexpensive. */
@Mixin(NoiseChunk.class)
public abstract class TerrainNoiseChunkMixin {
    @Unique private boolean cwiWorldgen$fullChunk;
    @Unique private ReferenceTerrain cwiWorldgen$terrain;
    @Unique private BlockState cwiWorldgen$nativeRock;
    @Unique private WaterProtectionColumn[] cwiWorldgen$waterColumns;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter cwiWorldgen$chunkMode(NoiseRouter router, DensityFunction.Visitor wrapper,
            int cellCountXZ, RandomState state, int minBlockX, int minBlockZ,
            NoiseSettings settings, DensityFunctions.BeardifierOrMarker beardifier, NoiseGeneratorSettings generatorSettings,
            Aquifer.FluidPicker fluidPicker, Blender blender) {
        cwiWorldgen$fullChunk = cellCountXZ > 1;
        cwiWorldgen$terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        if (cwiWorldgen$fullChunk && cwiWorldgen$terrain != null && cwiWorldgen$terrain.generationVersion() >= 12) {
            cwiWorldgen$nativeRock = generatorSettings.defaultBlock();
            cwiWorldgen$waterColumns = new WaterProtectionColumn[256];
        }
        return router.mapAll(wrapper);
    }

    /** Protect actual native floors before the aquifer decides whether a cave contains fluid. */
    @Inject(method = "getInterpolatedState", at = @At("HEAD"), cancellable = true)
    private void cwiWorldgen$protectWaterFloor(CallbackInfoReturnable<BlockState> cir) {
        if (cwiWorldgen$waterColumns == null) return;
        NoiseChunk self = (NoiseChunk) (Object) this;
        int x = self.blockX(), z = self.blockZ();
        int index = ((x & 15) << 4) | (z & 15);
        WaterProtectionColumn column = cwiWorldgen$waterColumns[index];
        if (column == null) cwiWorldgen$waterColumns[index] = column = cwiWorldgen$terrain.waterProtection().column(x, z);
        if (column.protects(self.blockY())) cir.setReturnValue(cwiWorldgen$nativeRock);
    }

    @Inject(method = "wrapNew", at = @At("HEAD"), cancellable = true)
    private void cwiWorldgen$useFilteredTerrain(DensityFunction function, CallbackInfoReturnable<DensityFunction> cir) {
        if (cwiWorldgen$fullChunk && function instanceof ReferenceTerrainDensity.Seeded seeded) {
            cir.setReturnValue(seeded.accurate());
        }
        if (cwiWorldgen$fullChunk && function instanceof ReferenceLandscapeDensity.Seeded seeded) {
            cir.setReturnValue(seeded.accurate());
        }
    }
}

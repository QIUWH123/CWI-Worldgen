package net.quiwh.cwiworldgen.mixin;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverGasket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs after carving has opened cave leaks, before any feature or structure decoration. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class TerrainRiverGasketMixin {
    @Inject(method = "applyCarvers", at = @At("TAIL"))
    private void cwiWorldgen$sealRiver(WorldGenRegion region, long seed, RandomState state,
            BiomeManager biomes, StructureManager structures, ChunkAccess chunk,
            GenerationStep.Carving step, CallbackInfo ci) {
        if (step == GenerationStep.Carving.AIR) ReferenceRiverGasket.place(chunk, state, seed);
    }
}

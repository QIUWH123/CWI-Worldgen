package net.quiwh.cwiworldgen.mixin;

import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverSurface;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SurfaceSystem.class)
public abstract class TerrainSurfaceSystemMixin {
    @Inject(method = "buildSurface", at = @At("HEAD"))
    private void cwiWorldgen$riverWater(RandomState state, BiomeManager biomes, Registry<Biome> registry,
            boolean legacyRandom, WorldGenerationContext context, ChunkAccess chunk, NoiseChunk noise,
            SurfaceRules.RuleSource rules, CallbackInfo ci) {
        var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        if (terrain != null) ReferenceRiverSurface.place(chunk, biomes, terrain);
    }
}

package net.quiwh.cwiworldgen.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource;
import net.quiwh.cwiworldgen.terrain.TerrainGenerationProfile;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apply saved, inline biome-source identity before ChunkMap constructs the RandomState. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class TerrainGeneratorProfileMixin {
    @Shadow @Final @Mutable private Holder<NoiseGeneratorSettings> settings;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cwiWorldgen$restoreProfile(BiomeSource biomes, Holder<NoiseGeneratorSettings> input, CallbackInfo ci) {
        if (biomes instanceof BoundedCaveBiomeSource caves) {
            var selected = TerrainGenerationProfile.settingsFor(input.value(), caves.generationVersion());
            if (selected != input.value()) settings = Holder.direct(selected);
        }
    }
}

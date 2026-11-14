package net.quiwh.cwiworldgen.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.quiwh.cwiworldgen.terrain.ReferenceGenerationHeight;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class TerrainGenerationHeightMixin {
    @Shadow @Final private Holder<NoiseGeneratorSettings> settings;
    @Unique private volatile Boolean cwiWorldgen$boundedGraph;

    @Redirect(method = "fillFromNoise", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/levelgen/NoiseSettings;height()I"))
    private int cwiWorldgen$skipEmptyUpperSpan(NoiseSettings noise, Blender blender,
            RandomState state, StructureManager structures, ChunkAccess chunk) {
        var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        if (terrain == null) return noise.height();
        Boolean supported = cwiWorldgen$boundedGraph;
        if (supported == null) cwiWorldgen$boundedGraph = supported = ReferenceGenerationHeight.supports(settings.value());
        return ReferenceGenerationHeight.height(noise, settings.value(), terrain,
                chunk.getPos(), blender, structures, supported);
    }
}

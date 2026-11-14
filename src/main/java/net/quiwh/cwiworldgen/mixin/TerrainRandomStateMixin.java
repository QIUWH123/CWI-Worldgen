package net.quiwh.cwiworldgen.mixin;

import java.util.EnumMap;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.quiwh.cwiworldgen.terrain.ReferenceTerrainDensity;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscapeDensity;
import net.quiwh.cwiworldgen.terrain.TerrainClimateSampler;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.TerrainGenerationProfile;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Binds only explicit CWI terrain markers; other dimension routers pass through. */
@Mixin(RandomState.class)
public abstract class TerrainRandomStateMixin implements TerrainRandomState {
    @Unique private ReferenceTerrain cwiWorldgen$referenceTerrain;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter cwiWorldgen$bindTerrain(NoiseRouter router, DensityFunction.Visitor vanilla,
            NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises, long seed) {
        var fields = new EnumMap<ReferenceTerrainDensity.Field, DensityFunction>(ReferenceTerrainDensity.Field.class);
        int generationVersion = TerrainGenerationProfile.fromRouter(router);
        return router.mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction function) {
                if (function instanceof ReferenceLandscapeDensity marker) {
                    if (cwiWorldgen$referenceTerrain == null) cwiWorldgen$referenceTerrain = new ReferenceTerrain(seed, generationVersion);
                    cwiWorldgen$referenceTerrain.configureLandscape(marker.caves());
                    return new ReferenceLandscapeDensity.Seeded(cwiWorldgen$referenceTerrain, false);
                }
                if (function instanceof ReferenceTerrainDensity marker) {
                    if (cwiWorldgen$referenceTerrain == null) cwiWorldgen$referenceTerrain = new ReferenceTerrain(seed, generationVersion);
                    return fields.computeIfAbsent(marker.field(), field ->
                            new ReferenceTerrainDensity.Seeded(cwiWorldgen$referenceTerrain, field, false));
                }
                return vanilla.apply(function);
            }
            @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
                return vanilla.visitNoise(noise);
            }
        });
    }

    @Override @Nullable public ReferenceTerrain cwiWorldgen$terrain() { return cwiWorldgen$referenceTerrain; }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void cwiWorldgen$bindSpawnSampler(NoiseGeneratorSettings settings,
            HolderGetter<NormalNoise.NoiseParameters> noises, long seed, CallbackInfo ci) {
        ((TerrainClimateSampler) (Object) ((RandomState) (Object) this).sampler())
                .cwiWorldgen$bindTerrain(cwiWorldgen$referenceTerrain);
        if (cwiWorldgen$referenceTerrain != null && cwiWorldgen$referenceTerrain.landscape() != null) {
            cwiWorldgen$referenceTerrain.landscape().bindClimate(((RandomState) (Object) this).sampler());
        }
    }
}

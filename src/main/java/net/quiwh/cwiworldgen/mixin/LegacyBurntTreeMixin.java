package net.quiwh.cwiworldgen.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.quiwh.cwiworldgen.feature.OriginalBurntTreePlacement;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep saved profiles' exact configured-feature execution without changing feature order or seed. */
@Mixin(ConfiguredFeature.class)
public abstract class LegacyBurntTreeMixin {
    @Unique private static final ResourceLocation cwiWorldgen$publicBurnt = ResourceLocation.parse("cwi:natures/burnt_log");
    @Unique private static final ResourceLocation cwiWorldgen$legacyBurnt = ResourceLocation.parse("cwi_worldgen:legacy/burnt_log");

    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void cwiWorldgen$preserveSavedBurntTree(WorldGenLevel level, ChunkGenerator generator,
            RandomSource random, BlockPos origin, CallbackInfoReturnable<Boolean> result) {
        var configured = (ConfiguredFeature<?, ?>) (Object) this;
        if (configured.feature() != Feature.TREE) return;
        if (!((Object) level.getLevel().getChunkSource().randomState() instanceof TerrainRandomState state)
                || state.cwiWorldgen$terrain() == null) return;
        var registry = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        if (registry.get(cwiWorldgen$publicBurnt) != (Object) this) return;
        if (state.cwiWorldgen$terrain().generationVersion() >= 13) {
            if (!(configured.config() instanceof TreeConfiguration tree))
                throw new IllegalStateException("Public burnt tree must retain its original vanilla tree configuration");
            result.setReturnValue(OriginalBurntTreePlacement.place(level, generator, random, origin, tree));
            return;
        }
        var legacy = registry.get(cwiWorldgen$legacyBurnt);
        if (legacy == null) throw new IllegalStateException("Missing saved-profile burnt tree configuration");
        result.setReturnValue(legacy.place(level, generator, random, origin));
    }
}

package net.quiwh.cwiworldgen.terrain;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** A saved router selects one coherent geometry provider before any marker is seed-bound. */
public final class TerrainGenerationProfile {
    private TerrainGenerationProfile() {}

    public static int requireSupported(int version) {
        if (version < 1 || version > 22) throw new IllegalArgumentException("Unsupported CWI generation version: " + version);
        return version;
    }

    public static int fromRouter(NoiseRouter router) {
        int[] selected = {0};
        router.mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction function) {
                int version = function instanceof ReferenceTerrainDensity terrain ? terrain.generationVersion()
                        : function instanceof ReferenceLandscapeDensity landscape ? landscape.generationVersion() : 0;
                if (version != 0) {
                    requireSupported(version);
                    if (selected[0] != 0 && selected[0] != version)
                        throw new IllegalArgumentException("Mixed CWI generation versions in one noise router: "
                                + selected[0] + " and " + version);
                    selected[0] = version;
                }
                return function;
            }
        });
        return selected[0] == 0 ? 1 : selected[0];
    }

    /** Saved biome-source identity wins over reloaded registry resource defaults. */
    public static NoiseGeneratorSettings settingsFor(NoiseGeneratorSettings source, int version) {
        requireSupported(version);
        if (fromRouter(source.noiseRouter()) == version) return source;
        var router = source.noiseRouter().mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction function) {
                if (function instanceof ReferenceTerrainDensity marker)
                    return new ReferenceTerrainDensity(marker.field(), version);
                if (function instanceof ReferenceLandscapeDensity marker)
                    return new ReferenceLandscapeDensity(marker.caves(), version);
                return function;
            }
        });
        return new NoiseGeneratorSettings(source.noiseSettings(), source.defaultBlock(), source.defaultFluid(),
                router, source.surfaceRule(), source.spawnTarget(), source.seaLevel(), source.disableMobGeneration(),
                source.aquifersEnabled(), source.oreVeinsEnabled(), source.useLegacyRandomSource());
    }
}

package net.quiwh.cwiworldgen.terrain.reference;

import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.Preset;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.Presets;

/** CWI's terrain settings, applied before the imported engine builds its noise graph. */
final class CwiTerrainPreset {
    private CwiTerrainPreset() {}

    static Preset make() {
        return make(1);
    }

    static Preset make(int generationVersion) {
        int baseVersion = net.quiwh.cwiworldgen.terrain.BaseTerrainProfile.version(generationVersion);
        Preset preset = Presets.makeDefault();
        preset.rivers().wetlands.chance = 0F;
        var smoothing = preset.filters().smoothing;
        smoothing.smoothingRadius = 1.25F;
        smoothing.smoothingRate = 0.35F;

        var terrain = preset.terrain();
        terrain.general.globalVerticalScale = 1.15F;
        terrain.steppe.horizontalScale = 0.92F;
        terrain.plains.horizontalScale = 0.90F;
        // These landforms build their own amplitudes without the global vertical scale.
        terrain.dales.verticalScale = 1.15F;
        terrain.badlands.verticalScale = 1.15F;
        terrain.torridonian.verticalScale = 1.15F;
        if (baseVersion >= 3) {
            // Separate horizontal extent from height and retain the existing local relief.
            terrain.general.terrainRegionSize = 840;
            terrain.mountains.horizontalScale = 0.62F;
            // The source applies this twice to overriding chains, once to regional peaks.
            terrain.mountains.verticalScale = 0.94F;
        }
        if (baseVersion >= 7) {
            // UPLIFT continents use four times this setting for their tectonic lattice.
            preset.world().continent.continentScale = 1800;
            // Keep ocean coverage near the old balance while shortening long crossings.
            preset.world().continent.continentSkipping = 0.20F;
            // Vary continental spacing/size and give intermediate coastal scales more weight.
            preset.world().continent.continentJitter = 0.85F;
            preset.world().continent.continentSizeVariance = 0.35F;
            preset.world().continent.continentNoiseGain = 0.34F;
            preset.world().continent.continentNoiseLacunarity = 2.70F;
            terrain.general.terrainRegionSize = 560;
            terrain.mountains.horizontalScale = 0.43F;
            // Keep the existing small-scale relief while lowering widespread flat ground.
            terrain.steppe.baseScale = 1.10F;
            terrain.plains.baseScale = 1.10F;
        }
        if (generationVersion == 21 || generationVersion == 22) {
            // Keep the original mountain sources, with moderately shorter wavelengths.
            terrain.mountains.horizontalScale = 0.80F;
            // Original graphs apply this once to regional peaks and twice to chains.
            terrain.mountains.verticalScale = 0.90F;
        }
        if (generationVersion == 22) {
            // Range ownership shrinks independently; each peak keeps its old wavelength.
            // The chain caller compensates for the classic graph's second scale factor.
            terrain.mountains.verticalScale = 0.72F;
        }
        return preset;
    }
}

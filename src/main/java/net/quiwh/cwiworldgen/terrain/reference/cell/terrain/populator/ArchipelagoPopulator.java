package net.quiwh.cwiworldgen.terrain.reference.cell.terrain.populator;

import net.minecraft.client.Minecraft;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.IslandSettings;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.WorldSettings;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.WorldSettings.ControlPoints;
import net.quiwh.cwiworldgen.terrain.reference.biome.Erosion;
import net.quiwh.cwiworldgen.terrain.reference.biome.Weirdness;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.CellPopulator;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.LocalTerraces;
import net.quiwh.cwiworldgen.terrain.reference.noise.NoiseUtil;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noises;
import net.quiwh.cwiworldgen.terrain.reference.util.Seed;
import java.util.function.DoubleBinaryOperator;
import java.util.LinkedHashMap;
import java.util.Map;

public class ArchipelagoPopulator implements CellPopulator {
    private IslandSettings settings;
    private Levels levels;
    private ControlPoints controlPoints;
    private final LocalTerraces.Field localTerraces;
    private final DoubleBinaryOperator continentalEdgeSampler;
    private final ThreadLocal<Map<Long, Float>> coastalSpans = ThreadLocal.withInitial(LinkedHashMap::new);
    private final int spanOffsetX;
    private final int spanOffsetZ;

    private Noise sizeNoise;
    private Noise densityNoise;
    private Noise ridgeHeight;
    private Noise hillHeight;
    private Noise volcanoHeight;
    private Noise mountainSelector;
    private Noise volcanoSelector;

    // New Organic Detail Noises
    private Noise beachVariance;
    private Noise baseTerrainDetail;
    private Noise mountainGullies;

    private Noise islandErosion;
    private Noise islandWeirdness;
    private Noise beachErosion;
    private Noise beachWeirdness;

    public ArchipelagoPopulator(IslandSettings settings, Levels levels, ControlPoints controlPoints, Seed seed) {
        this(settings, levels, controlPoints, seed, 1);
    }

    public ArchipelagoPopulator(IslandSettings settings, Levels levels, ControlPoints controlPoints, Seed seed, int generationVersion) {
        this(settings, levels, controlPoints, seed, generationVersion, null);
    }

    public ArchipelagoPopulator(IslandSettings settings, Levels levels, ControlPoints controlPoints, Seed seed, int generationVersion, DoubleBinaryOperator continentalEdgeSampler) {
        this.settings = settings;
        this.levels = levels;
        this.controlPoints = controlPoints;
        int salt = seed.get();
        this.localTerraces = generationVersion >= 8 ? LocalTerraces.field(salt, 7) : null;
        this.continentalEdgeSampler = continentalEdgeSampler;
        this.spanOffsetX = salt >>> 3 & 31;
        this.spanOffsetZ = salt >>> 11 & 31;

        int size = Math.round(settings.islandSize);
        float hScale = Math.max(0.1F, settings.islandHorizontalScale);

        // Coastline & Falloff Distortion
        // A high-frequency warp at the end to "ruffle" the edges, preventing smooth, perfect arcs.
        Noise sizeN = Noises.simplex(1273 + salt, Math.max(1, Math.round(size * 3.5F / hScale)), 3);
        sizeN = Noises.warpPerlin(sizeN, 1273 + salt, Math.max(1, Math.round(size * 2.0F / hScale)), 2, size * 0.5F / hScale);
        sizeN = Noises.warpPerlin(sizeN, 4830 + salt, Math.max(1, Math.round(size * 0.5F / hScale)), 1, size * 0.3F / hScale);
        sizeN = Noises.warpPerlin(sizeN, 8932 + salt, Math.max(1, Math.round(size * 0.08F / hScale)), 2, size * 0.15F / hScale);
        sizeN = Noises.clamp(sizeN, 0.0F, 1.0F);
        this.sizeNoise = sizeN;

        Noise densityN = Noises.simplex(9735 + salt, 4000, 3);
        densityN = Noises.warpPerlin(densityN, 9735 + salt, 2000, 2, 1000.0F);
        densityN = Noises.clamp(densityN, 0.0F, 1.0F);
        this.densityNoise = densityN;

        Noise ridge = Noises.perlinRidge(4829 + salt, Math.max(1, Math.round(size * 1.6F / hScale)), 4, 2.1F, 0.82F);
        ridge = Noises.warpPerlin(ridge, 4830 + salt, Math.max(1, Math.round(size * 0.9F / hScale)), 2, size * 0.35F / hScale);
        ridge = Noises.clamp(ridge, 0.0F, 1.0F);
        this.ridgeHeight = ridge;

        Noise hills = Noises.billow(3811 + salt, Math.max(1, Math.round(size * 0.35F / hScale)), 3, 2.25F, 0.55F);
        hills = Noises.warpPerlin(hills, 3812 + salt, Math.max(1, Math.round(size * 0.7F / hScale)), 1, size * 0.2F / hScale);
        hills = Noises.clamp(hills, 0.0F, 1.0F);
        this.hillHeight = hills;

        Noise volcano = Noises.perlinRidge(6721 + salt, Math.max(1, Math.round(size * 0.45F / hScale)), 3, 2.4F, 0.9F);
        volcano = Noises.powCurve(volcano, 1.8F);
        volcano = Noises.clamp(volcano, 0.0F, 1.0F);
        this.volcanoHeight = volcano;

        // Ebbing & Flowing Beaches
        this.beachVariance = Noises.simplex(5541 + salt, Math.max(1, Math.round(size * 0.15F / hScale)), 2);

        // Rolling Base Terrain (breaks up flatness)
        this.baseTerrainDetail = Noises.simplex(7712 + salt, Math.max(1, Math.round(size * 0.1F / hScale)), 3);

        // Mountain Gullies / Erosion (adds rivulets)
        this.mountainGullies = Noises.perlinRidge(9912 + salt, Math.max(1, Math.round(size * 0.06F / hScale)), 3, 2.2F, 0.8F);

        this.mountainSelector = Noises.clamp(Noises.simplex(11867 + salt, Math.max(1, Math.round(size * 1.25F / hScale)), 2), 0.0F, 1.0F);
        this.volcanoSelector = Noises.clamp(Noises.simplex(22193 + salt, Math.max(1, Math.round(size * 1.75F / hScale)), 2), 0.0F, 1.0F);

        this.islandErosion = Erosion.LEVEL_4.source();
        this.islandWeirdness = Weirdness.MID_SLICE_NORMAL_DESCENDING.source();
        this.beachErosion = Erosion.LEVEL_4.source();
        this.beachWeirdness = Weirdness.MID_SLICE_NORMAL_DESCENDING.source();
    }

    @Override
    public void apply(Cell cell, float x, float z) {
        // Capture the continent edge value before it gets overridden
        float originalContinentEdge = cell.continentEdge;

        float sizeValue = this.sizeNoise.compute(x, z, 0);
        float densityValue = this.densityNoise.compute(x, z, 0);
        float densityThreshold = NoiseUtil.clamp(1.0F - this.settings.islandDensity * 0.8F, 0.05F, 0.98F);

        float shapeAlpha = smoothStep(0.5F, 1.0F, sizeValue);
        float densityFade = NoiseUtil.clamp((1.0F - densityThreshold) * 0.5F, 0.04F, 0.12F);
        float densityAlpha = smoothStep(densityThreshold, densityThreshold + densityFade, densityValue);

        // Calculate a fade factor based on proximity to the main continent
        float fadeStart = this.controlPoints.islandCoast;
        float fadeEnd = this.controlPoints.deepOcean;
        float continentFade = 1.0F - smoothStep(fadeStart, fadeEnd, originalContinentEdge);

        // Multiply islandAlpha by the fade factor
        float islandAlpha = shapeAlpha * densityAlpha * continentFade;
        if (islandAlpha <= 0.001F) {
            return;
        }

        float beachWidth = NoiseUtil.clamp(Math.max(0.05F, this.settings.beachWidth), 0.05F, 0.45F);
        float beachCoverage = NoiseUtil.clamp(this.settings.beachCoverage, 0.0F, 1.0F);
        float shelfEnd = NoiseUtil.clamp(beachWidth * 0.65F, 0.04F, 0.35F);

        // Dynamic Beach Ebb & Cliff Flow
        float rawVariance = this.beachVariance.compute(x, z, 0);

        // Map raw variance into a cliff tendency factor (0.0 = wide gentle beach, 1.0 = steep cliff face)
        float cliffFactor = smoothStep(-0.2F, 0.6F, rawVariance);

        /// Dynamically compress the beach width down toward zero in cliff zones
        float activeBeachWidth = NoiseUtil.lerp(beachWidth, 0.01F, cliffFactor);

        // Establish where the terrain actually emerges from the water (constant slope)
        float coastEnd = NoiseUtil.clamp(shelfEnd + (activeBeachWidth * 0.5F), shelfEnd + 0.005F, 0.85F);

        // Add the beach coverage to define how far inland the flat sandy area extends
        float baseBeachEnd = coastEnd + (activeBeachWidth * beachCoverage * 1.5F);

        // Retain normal organic beach variations only in non-cliff areas
        float bVariance = rawVariance * 0.15F * (1.0F - cliffFactor) - 0.05F * (1.0F - cliffFactor);
        float dynamicBeachEnd = NoiseUtil.clamp(baseBeachEnd + bVariance, coastEnd + 0.005F, 0.85F);

        float oceanHeight = cell.height;
        int offshoreDepth = Math.max(2, Math.round(4.0F + this.settings.offshoreDepth * 10.0F));
        float shelfTarget = Math.max(oceanHeight, this.levels.water(-offshoreDepth));
        float shelfAlpha = smoothStep(0.0F, shelfEnd, islandAlpha);
        float shelfHeight = NoiseUtil.lerp(oceanHeight, shelfTarget, shelfAlpha);

        // Interpolate height ONLY up to the coastline.
        // This brings it out of the water consistently, leaving the rest of the beach flat.
        float coastAlpha = smoothStep(shelfEnd, coastEnd, islandAlpha);
        float beachHeight = NoiseUtil.lerp(shelfHeight, this.levels.ground, coastAlpha);

        // Push Mountain Onset Directly Into Water Line
        // Pull the mountain start down to meet the compressed beach line when cliffFactor is active
        float mountainGap = NoiseUtil.lerp(0.08F, 0.005F, cliffFactor);
        float mountainStart = NoiseUtil.clamp(dynamicBeachEnd + mountainGap, shelfEnd + 0.01F, 0.9F);

        // Sharpen the transition window to create steeper mountain slopes facing the water
        float mountainTransition = NoiseUtil.lerp(0.08F, 0.04F, cliffFactor);
        float mountainEnd = Math.max(mountainStart + mountainTransition, 0.72F * (1.0F - cliffFactor * 0.25F));
        float reliefAlpha = islandAlpha;
        float continentalRelief = 1;
        if (this.localTerraces != null) {
            float reliefSpan = (fadeEnd - fadeStart) * 3;
            if (this.continentalEdgeSampler != null) {
                reliefSpan = coastalSpan(x, z, (fadeEnd - fadeStart) * 2);
            }
            reliefAlpha = shapeAlpha * densityAlpha;
            // End the spatial ramp at the existing beach contour. Applying this fade once
            // avoids amplifying it through both land and mountain threshold windows.
            float beachFade = reliefAlpha > dynamicBeachEnd ? dynamicBeachEnd / reliefAlpha : 1;
            float beachEdge = NoiseUtil.lerp(fadeStart, fadeEnd, inverseSmoothStep(1 - beachFade));
            continentalRelief = 1 - smoothStep(beachEdge - reliefSpan, beachEdge, originalContinentEdge);
            mountainEnd = Math.max(mountainEnd, mountainStart + 0.35F);
        }
        float mountainAlpha = smoothStep(mountainStart, mountainEnd, reliefAlpha);

        // Scale Calculations
        // We invert the slider scale so that a higher scale (1.0) multiplies coordinates by a smaller number,
        // making the noise features geographically larger.
        // Slider 0.0 -> Multiplier 2.0 (Small, frequent features)
        // Slider 0.5 -> Multiplier 1.0 (Default size)
        // Slider 1.0 -> Multiplier 0.3 (Massive, wide features)
        float mountainFreqMod = NoiseUtil.lerp(2.0F, 0.3F, this.settings.mountainScale);
        float volcanoFreqMod = NoiseUtil.lerp(2.0F, 0.3F, this.settings.volcanismScale);

        // Apply the frequency scale adjustments to the noise lookup coordinates
        float mountainGate = chanceMask(this.mountainSelector, this.settings.mountainChance, x * mountainFreqMod, z * mountainFreqMod);
        float volcanoGate = chanceMask(this.volcanoSelector, this.settings.volcanoChance, x * volcanoFreqMod, z * volcanoFreqMod);

        float hillValue = this.hillHeight.compute(x * mountainFreqMod, z * mountainFreqMod, 0) * (0.15F + mountainGate * 0.55F);
        float ridgeValue = this.ridgeHeight.compute(x * mountainFreqMod, z * mountainFreqMod, 0) * mountainGate;
        float volcanoValue = this.volcanoHeight.compute(x * volcanoFreqMod, z * volcanoFreqMod, 0) * volcanoGate;
        // --------------------------------

        // Base Mountain Profile
        float mountainValue = NoiseUtil.clamp(hillValue * 0.35F + ridgeValue * 0.5F + volcanoValue * 0.75F, 0.0F, 1.0F);

        // Apply Gullies and Rivulets
        float gullyErosion = this.mountainGullies.compute(x, z, 0) * mountainAlpha * 0.45F;
        mountainValue = Math.max(0.0F, mountainValue - gullyErosion);

        // Apply Terracing to Mountains
        float terraceSteps = 7.0F;
        float terracedMountain = (float) Math.floor(mountainValue * terraceSteps) / terraceSteps;
        if (this.localTerraces != null) terracedMountain = this.localTerraces.sample(mountainValue, LocalTerraces.continuous(mountainValue, 7), x, z);
        mountainValue = NoiseUtil.lerp(mountainValue, terracedMountain, 0.55F);

        float baseHeight = this.settings.islandHeight * (0.015F + this.settings.islandBaseScale * 0.08F);
        float reliefHeight = mountainValue * mountainAlpha * this.settings.islandHeight * this.settings.islandVerticalScale * 0.3F;

        // Land Alpha so Mountain Height isn't suppressed at the shore
        // Instead of taking the whole island radius (1.0F), complete the blend quickly near cliffs
        float landTransitionEnd = NoiseUtil.lerp(1.0F, dynamicBeachEnd + 0.12F, cliffFactor);
        if (this.localTerraces != null) landTransitionEnd = Math.max(landTransitionEnd, dynamicBeachEnd + 0.35F);
        float landAlpha = smoothStep(dynamicBeachEnd, landTransitionEnd, reliefAlpha);

        // Base Terrain Detailing (Rolling Hills)
        float landDetail = this.baseTerrainDetail.compute(x, z, 0) * this.settings.islandHeight * 0.08F * landAlpha;
        float targetHeight = this.levels.ground + baseHeight + reliefHeight + landDetail;

        cell.height = NoiseUtil.lerp(beachHeight, targetHeight, landAlpha * continentalRelief);
        cell.continentEdge = Math.max(originalContinentEdge, continentEdge(islandAlpha, shelfEnd, dynamicBeachEnd));

        if (islandAlpha < shelfEnd) {
            cell.terrain = TerrainType.SHALLOW_OCEAN;
        } else if (islandAlpha < dynamicBeachEnd) {
            cell.terrain = TerrainType.ISLAND_BEACH;
        } else if ((this.localTerraces == null ? mountainAlpha : mountainAlpha * continentalRelief) > 0.35F && mountainValue > 0.35F && (mountainGate > 0.1F || volcanoGate > 0.2F)) {
            cell.terrain = TerrainType.ISLAND_MOUNTAINS;
        } else {
            cell.terrain = TerrainType.ISLAND;
        }

        if (islandAlpha >= shelfEnd) {
            if (cell.terrain == TerrainType.ISLAND_BEACH) {
                cell.erosion = this.beachErosion.compute(x, z, 0);
                cell.weirdness = this.beachWeirdness.compute(x, z, 0);
            } else {
                cell.erosion = this.islandErosion.compute(x, z, 0);
                cell.weirdness = this.islandWeirdness.compute(x, z, 0);
            }
        }
    }

    private static float inverseSmoothStep(float value) {
        return (float) (0.5 - Math.sin(Math.asin(1 - 2 * NoiseUtil.clamp(value, 0, 1)) / 3));
    }

    /** Shared slowly varying spans avoid differentiating a noisy local gradient at every column. */
    private float coastalSpan(float x, float z, float minimum) {
        int gx = (int) Math.floor((x - this.spanOffsetX) / 32);
        int gz = (int) Math.floor((z - this.spanOffsetZ) / 32);
        float ax = smoothStep(0, 1, (x - this.spanOffsetX) / 32 - gx);
        float az = smoothStep(0, 1, (z - this.spanOffsetZ) / 32 - gz);
        float a = spanAt(gx, gz, minimum);
        float b = spanAt(gx + 1, gz, minimum);
        float c = spanAt(gx, gz + 1, minimum);
        float d = spanAt(gx + 1, gz + 1, minimum);
        return NoiseUtil.lerp(NoiseUtil.lerp(a, b, ax), NoiseUtil.lerp(c, d, ax), az);
    }

    private float spanAt(int gx, int gz, float minimum) {
        long key = ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
        Map<Long, Float> spans = this.coastalSpans.get();
        Float found = spans.get(key);
        if (found != null) return found;
        float x = gx * 32F + this.spanOffsetX;
        float z = gz * 32F + this.spanOffsetZ;
        double dx = coastalMargin(x + 16, z) - coastalMargin(x - 16, z);
        double dz = coastalMargin(x, z + 16) - coastalMargin(x, z - 16);
        float span = Math.max(minimum, (float) Math.hypot(dx, dz) * 2.0F);
        if (spans.size() >= 256) spans.remove(spans.keySet().iterator().next());
        spans.put(key, span);
        return span;
    }

    /** Signed threshold-space distance to the same varying beach contour used by apply. */
    private float coastalMargin(float x, float z) {
        float densityThreshold = NoiseUtil.clamp(1.0F - this.settings.islandDensity * 0.8F, 0.05F, 0.98F);
        float densityFade = NoiseUtil.clamp((1.0F - densityThreshold) * 0.5F, 0.04F, 0.12F);
        float shape = smoothStep(0.5F, 1, this.sizeNoise.compute(x, z, 0))
                * smoothStep(densityThreshold, densityThreshold + densityFade, this.densityNoise.compute(x, z, 0));
        float beachWidth = NoiseUtil.clamp(Math.max(0.05F, this.settings.beachWidth), 0.05F, 0.45F);
        float shelfEnd = NoiseUtil.clamp(beachWidth * 0.65F, 0.04F, 0.35F);
        float rawVariance = this.beachVariance.compute(x, z, 0);
        float cliffFactor = smoothStep(-0.2F, 0.6F, rawVariance);
        float activeWidth = NoiseUtil.lerp(beachWidth, 0.01F, cliffFactor);
        float coastEnd = NoiseUtil.clamp(shelfEnd + activeWidth * 0.5F, shelfEnd + 0.005F, 0.85F);
        float baseBeachEnd = coastEnd + activeWidth * NoiseUtil.clamp(this.settings.beachCoverage, 0, 1) * 1.5F;
        float variance = rawVariance * 0.15F * (1 - cliffFactor) - 0.05F * (1 - cliffFactor);
        float beachEnd = NoiseUtil.clamp(baseBeachEnd + variance, coastEnd + 0.005F, 0.85F);
        float beachFade = shape > beachEnd ? beachEnd / shape : 1;
        float beachEdge = NoiseUtil.lerp(this.controlPoints.islandCoast, this.controlPoints.deepOcean,
                inverseSmoothStep(1 - beachFade));
        return beachEdge - (float) this.continentalEdgeSampler.applyAsDouble(x, z);
    }

    private float continentEdge(float islandAlpha, float shelfEnd, float beachEnd) {
        if (islandAlpha < shelfEnd) {
            float alpha = smoothStep(0.0F, shelfEnd, islandAlpha);
            return NoiseUtil.lerp(this.controlPoints.deepOcean, this.controlPoints.shallowOcean, alpha);
        }
        if (islandAlpha < beachEnd) {
            float alpha = smoothStep(shelfEnd, beachEnd, islandAlpha);
            return NoiseUtil.lerp(this.controlPoints.shallowOcean, this.controlPoints.coast, alpha);
        }
        float alpha = smoothStep(beachEnd, 1.0F, islandAlpha);
        return NoiseUtil.lerp(this.controlPoints.coast, this.controlPoints.inland, alpha);
    }

    private static float chanceMask(Noise selector, float chance, float x, float z) {
        chance = NoiseUtil.clamp(chance, 0.0F, 1.0F);
        if (chance <= 0.0F) {
            return 0.0F;
        }
        if (chance >= 1.0F) {
            return 1.0F;
        }
        float threshold = 1.0F - chance;
        return smoothStep(threshold, Math.min(1.0F, threshold + 0.2F), selector.compute(x, z, 0));
    }

    private static float smoothStep(float min, float max, float value) {
        if (max <= min) {
            return value >= max ? 1.0F : 0.0F;
        }
        float alpha = NoiseUtil.clamp((value - min) / (max - min), 0.0F, 1.0F);
        return alpha * alpha * (3.0F - 2.0F * alpha);
    }
}

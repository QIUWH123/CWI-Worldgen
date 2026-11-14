package net.quiwh.cwiworldgen.terrain.reference.cell.terrain.populator;

import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.TerrainSettings;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.CellPopulator;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.Terrain;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;

public record TerrainPopulator(Terrain type, Noise base, Noise height, Noise erosion, Noise weirdness, float baseScale, float heightScale, float weight, MountainSupport supportHeight) implements CellPopulator, WeightedPopulator {

    /** Immutable coast interval; no references to mutable preset settings escape construction. */
    public record CoastBounds(float lower, float upper) {
        public CoastBounds {
            if (!Float.isFinite(lower) || !Float.isFinite(upper) || lower >= upper)
                throw new IllegalArgumentException("Invalid mountain coast interval");
        }
    }

    public record MountainSupport(Noise height, CoastBounds coast, Noise relativeRelief) {
        public MountainSupport(Noise height, CoastBounds coast) { this(height, coast, null); }
    }

    public TerrainPopulator(Terrain type, Noise base, Noise height, Noise erosion, Noise weirdness,
                            float baseScale, float heightScale, float weight) {
        this(type, base, height, erosion, weirdness, baseScale, heightScale, weight, null);
    }

    public TerrainPopulator withSupportHeight(Noise height) {
        return withSupportHeight(height, null);
    }

    public TerrainPopulator withSupportHeight(Noise height, Noise relativeRelief) {
        return new TerrainPopulator(type, base, this.height, erosion, weirdness, baseScale,
                heightScale, weight, height == null ? null : new MountainSupport(height, null, relativeRelief));
    }

    public TerrainPopulator withCoastBounds(float lower, float upper) {
        CoastBounds bounds = new CoastBounds(lower, upper);
        if (supportHeight == null) return this;
        return new TerrainPopulator(type, base, height, erosion, weirdness, baseScale,
                heightScale, weight, new MountainSupport(supportHeight.height, bounds, supportHeight.relativeRelief));
    }
    
	public TerrainPopulator(Terrain type, Noise base, Noise height, Noise erosion, Noise weirdness, float weight) {
		this(type, base, height, erosion, weirdness, 1.0F, 1.0F, weight);
	}
	
    @Override
    public void apply(Cell cell, float x, float z) {
        float base = this.base.compute(x, z, 0) * this.baseScale;
        float height = this.height.compute(x, z, 0) * this.heightScale;

        if (this.supportHeight != null) {
            float ownership = net.quiwh.cwiworldgen.terrain.CompactTerrainTransitions.weight(
                    cell.terrainRegionEdge, false);
            CoastBounds coast = this.supportHeight.coast;
            if (coast != null) {
                float inland = Math.max(0F, Math.min(1F,
                        (cell.continentEdge - coast.lower) / (coast.upper - coast.lower)));
                // The coastline must keep its supporting mass while inland crests taper.
                inland *= inland;
                inland *= inland;
                ownership *= inland;
            }
            if (ownership < 1F) {
                float support = this.supportHeight.height.compute(x, z, 0) * this.heightScale;
                height = ownership <= 0F ? support : support + (height - support) * ownership;
            }
        }
        cell.terrain = this.type;
        cell.height = Math.max(base + height, 0.0F);
        cell.erosion = this.erosion.compute(x, z, 0);
        cell.weirdness = this.weirdness.compute(x, z, 0);
    }
    
    public static TerrainPopulator make(Terrain type, Noise base, Noise height, Noise erosion, Noise weirdness, TerrainSettings.Terrain settings) {
    	return new TerrainPopulator(type, base, height, erosion, weirdness, settings.baseScale, settings.verticalScale, settings.weight);
    }
}

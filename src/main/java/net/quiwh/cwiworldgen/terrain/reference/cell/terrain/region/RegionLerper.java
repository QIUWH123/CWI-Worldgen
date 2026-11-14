package net.quiwh.cwiworldgen.terrain.reference.cell.terrain.region;

import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.CellPopulator;
import net.quiwh.cwiworldgen.terrain.reference.noise.NoiseUtil;

public class RegionLerper implements CellPopulator {
    private CellPopulator lower;
    private CellPopulator upper;
    private final boolean compactTransitions;
    private final boolean broadMountainFoot;
    
    public RegionLerper(CellPopulator lower, CellPopulator upper) {
        this(lower, upper, 1);
    }

    public RegionLerper(CellPopulator lower, CellPopulator upper, int generationVersion) {
        this.compactTransitions = generationVersion >= 12;
        this.broadMountainFoot = generationVersion >= 17;
        this.lower = lower;
        this.upper = upper;
    }
    
    @Override
    public void apply(Cell cell, float x, float y) {
        float alpha = cell.terrainRegionEdge;
        if (alpha == 0.0F) {
            this.lower.apply(cell, x, y);
            return;
        }
        if (alpha == 1.0F) {
            this.upper.apply(cell, x, y);
            return;
        }
        
        if (this.compactTransitions) alpha = net.quiwh.cwiworldgen.terrain.CompactTerrainTransitions.weight(alpha, false);
        // Resolve the same selected source once before applying either branch.
        CellPopulator selectedUpper = this.broadMountainFoot && this.upper instanceof RegionSelector selector
                ? selector.get(cell.terrainRegionId) : this.upper;
        this.lower.apply(cell, x, y);
        float lowerHeight = cell.height;
        float lowerErosion = cell.erosion;
        float lowerWeirdness = cell.weirdness;
        
        selectedUpper.apply(cell, x, y);
        float upperHeight = cell.height;
        float upperErosion = cell.erosion;
        float upperWeirdness = cell.weirdness;

        // Only the lower part of the source can add supporting height.
        float heightAlpha = alpha;
        if (this.broadMountainFoot && upperHeight > lowerHeight
                && cell.terrain.isMountain() && !cell.terrain.isVolcano()) {
            float influence = 1F;
            if (selectedUpper instanceof net.quiwh.cwiworldgen.terrain.reference.cell.terrain.populator.TerrainPopulator mountain
                    && mountain.supportHeight() != null && mountain.supportHeight().relativeRelief() != null) {
                var support = mountain.supportHeight();
                float localRelief = support.relativeRelief().compute(x, y, 0) * alpha;
                if (support.coast() != null) {
                    var coast = support.coast();
                    localRelief *= Math.max(0F, Math.min(1F,
                            (cell.continentEdge - coast.lower()) / (coast.upper() - coast.lower())));
                }
                float crest = Math.max(0F, Math.min(1F, (localRelief - .25F) * 4F));
                influence = 1F - crest * crest * (3F - 2F * crest);
            }
            float broader = net.quiwh.cwiworldgen.terrain.CompactTerrainTransitions.regionalMountainFoot(alpha);
            heightAlpha = alpha + (broader - alpha) * influence;
        }
        
        cell.height = NoiseUtil.lerp(lowerHeight, upperHeight, heightAlpha);
        cell.erosion = NoiseUtil.lerp(lowerErosion, upperErosion, alpha);
        cell.weirdness = NoiseUtil.lerp(lowerWeirdness, upperWeirdness, alpha);
    }
}

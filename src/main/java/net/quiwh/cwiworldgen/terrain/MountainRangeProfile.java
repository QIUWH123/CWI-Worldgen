package net.quiwh.cwiworldgen.terrain;

import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.CellPopulator;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.RegionConfig;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.populator.TerrainPopulator;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.region.RegionSelector;
import net.quiwh.cwiworldgen.terrain.reference.noise.NoiseUtil;
import net.quiwh.cwiworldgen.terrain.reference.noise.domain.Domain;
import net.quiwh.cwiworldgen.terrain.reference.noise.domain.Domains;
import net.quiwh.cwiworldgen.terrain.reference.noise.function.DistanceFunction;
import net.quiwh.cwiworldgen.terrain.reference.noise.function.EdgeFunction;
import net.quiwh.cwiworldgen.terrain.reference.noise.function.Interpolation;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noises;

/** Separates the extent of mountain ranges from the wavelength of their peaks. */
public final class MountainRangeProfile {
    private static final float REGIONAL_EXTENT = .50F;

    private MountainRangeProfile() {}

    public static Noise chainMask(Noise nativeMask, int seed, int generationVersion) {
        if (generationVersion != 22) return nativeMask;
        // Break the old connected chain lattice into ranges. Full-strength cores
        // retain the old transverse mask and all individual mountain sources.
        Noise gaps = Noises.simplex(seed ^ 0x44929375, 900, 2);
        gaps = Noises.warpPerlin(gaps, seed ^ 0x721A6B43, 300, 2, 100F);
        gaps = Noises.curve(Noises.map(Noises.clamp(gaps, .30F, .60F), 0F, 1F),
                Interpolation.CURVE3);
        return Noises.mul(nativeMask, gaps);
    }

    /** Profile22 lowers mountain relief once; the classic chain caller normally doubles it. */
    public static float chainVerticalScale(int generationVersion, float configuredScale) {
        return generationVersion == 22 ? .90F : configuredScale;
    }

    public static CellPopulator regional(CellPopulator delegate, RegionSelector selector,
            RegionConfig config, int generationVersion) {
        return generationVersion == 22 ? new Regional(delegate, selector, config) : delegate;
    }

    /** Shrinks mountain ownership while keeping source coordinates and all region metadata intact. */
    public static final class Regional implements CellPopulator {
        private final CellPopulator delegate;
        private final RegionSelector selector;
        private final int seed;
        private final float frequency;
        private final Domain warp;

        private Regional(CellPopulator delegate, RegionSelector selector, RegionConfig config) {
            this.delegate = delegate;
            this.selector = selector;
            this.seed = config.seed() + 7;
            this.frequency = 1F / config.scale();
            this.warp = Domains.domain(config.warpX(), config.warpZ(),
                    Noises.constant(config.warpStrength()));
        }

        @Override
        public void apply(Cell cell, float x, float z) {
            CellPopulator selected = this.selector.get(cell.terrainRegionId);
            if (!(selected instanceof TerrainPopulator terrain) || !terrain.type().isMountain()
                    || terrain.type().isVolcano()) {
                this.delegate.apply(cell, x, z);
                return;
            }
            float originalEdge = cell.terrainRegionEdge;
            cell.terrainRegionEdge = this.regionalEdge(cell, x, z);
            try {
                this.delegate.apply(cell, x, z);
            } finally {
                cell.terrainRegionEdge = originalEdge;
            }
        }

        /** The same owner cell, contracted around its original nucleus in warped region space. */
        public float regionalEdge(Cell cell, float x, float z) {
            float centerX = cell.terrainRegionCenterX * this.frequency;
            float centerZ = cell.terrainRegionCenterZ * this.frequency;
            float warpedX = (x + this.warp.getOffsetX(x, z, 0)) * this.frequency;
            float warpedZ = (z + this.warp.getOffsetZ(x, z, 0)) * this.frequency;
            float px = centerX + (warpedX - centerX) / REGIONAL_EXTENT;
            float pz = centerZ + (warpedZ - centerZ) / REGIONAL_EXTENT;
            int xi = NoiseUtil.floor(px), zi = NoiseUtil.floor(pz);
            int ownerX = 0, ownerZ = 0;
            float nearest = Float.MAX_VALUE, second = Float.MAX_VALUE;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int candidateX = xi + dx, candidateZ = zi + dz;
                    NoiseUtil.Vec2f vector = NoiseUtil.cell(this.seed, candidateX, candidateZ);
                    float distance = DistanceFunction.NATURAL.apply(
                            candidateX + vector.x() * .7F - px,
                            candidateZ + vector.y() * .7F - pz);
                    if (distance < nearest) {
                        second = nearest;
                        nearest = distance;
                        ownerX = candidateX;
                        ownerZ = candidateZ;
                    } else if (distance < second) {
                        second = distance;
                    }
                }
            }
            NoiseUtil.Vec2f owner = NoiseUtil.cell(this.seed, ownerX, ownerZ);
            float actualCenterX = (ownerX + owner.x() * .7F) / this.frequency;
            float actualCenterZ = (ownerZ + owner.y() * .7F) / this.frequency;
            if (actualCenterX != cell.terrainRegionCenterX
                    || actualCenterZ != cell.terrainRegionCenterZ) return 0F;
            EdgeFunction edge = EdgeFunction.DISTANCE_2_DIV;
            float value = 1F - NoiseUtil.map(edge.apply(nearest, second),
                    edge.min(), edge.max(), edge.range());
            value = NoiseUtil.pow(value, 1.5F);
            return Math.max(0F, Math.min(1F, value / .5F));
        }
    }
}

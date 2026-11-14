package net.quiwh.cwiworldgen.terrain.reference.cell.terrain;

import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.CellPopulator;
import net.quiwh.cwiworldgen.terrain.reference.noise.NoiseUtil;
import net.quiwh.cwiworldgen.terrain.reference.noise.function.Interpolation;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;

public class Blender implements CellPopulator {
	private Noise control;
	private CellPopulator lower;
	private CellPopulator upper;
	private float blendLower;
	private float blendUpper;
	private float blendRange;
	private float midpoint;
	private final boolean compactTransitions;
	private final boolean broadMountainFoot;
	private final boolean widerMountainFoot;

	public Blender(Noise control, CellPopulator lower, CellPopulator upper, float min, float max, float split) {
		this(control, lower, upper, min, max, split, 1);
	}

	public Blender(Noise control, CellPopulator lower, CellPopulator upper, float min, float max, float split, int generationVersion) {
		this.compactTransitions = generationVersion >= 12;
		this.broadMountainFoot = generationVersion >= 15;
		this.widerMountainFoot = generationVersion >= 17;
		this.control = control;
		this.lower = lower;
		this.upper = upper;
		this.blendLower = min;
		this.blendUpper = max;
		this.blendRange = this.blendUpper - this.blendLower;
		this.midpoint = this.blendLower + this.blendRange * split;
	}

	@Override
	public void apply(Cell cell, float x, float y) {
		float select = this.control.compute(x, y, 0);
		if (select < this.blendLower) {
			this.lower.apply(cell, x, y);
			return;
		}
		if (select > this.blendUpper) {
			this.upper.apply(cell, x, y);
			return;
		}
		float alpha = Interpolation.LINEAR.apply((select - this.blendLower) / this.blendRange);
		if (this.compactTransitions) alpha = net.quiwh.cwiworldgen.terrain.CompactTerrainTransitions.weight(alpha, !this.broadMountainFoot);
		this.lower.apply(cell, x, y);
		float lowerHeight = cell.height;
		float lowerErosion = cell.erosion;
		float lowerWeirdness = cell.weirdness;
		Terrain lowerType = cell.terrain;
		// Snapshot the lower layer before applying the chain. Ownership fades
		// at regional borders, so the two mountain feet do not stack at a crest.
		float regionalOwnership = this.widerMountainFoot && lowerType.isMountain()
				? net.quiwh.cwiworldgen.terrain.CompactTerrainTransitions.weight(cell.terrainRegionEdge, false) : 0F;
		this.upper.apply(cell, x, y);
		float upperHeight = cell.height;
		float upperErosion = cell.erosion;
		float upperWeirdness = cell.weirdness;
		float heightAlpha = alpha;
		if (this.widerMountainFoot && upperHeight > lowerHeight) {
			float broader = net.quiwh.cwiworldgen.terrain.CompactTerrainTransitions.broadMountainFoot(alpha);
			heightAlpha = alpha + (broader - alpha) * (1F - regionalOwnership);
		}
		cell.height = NoiseUtil.lerp(lowerHeight, upperHeight, heightAlpha);
		cell.erosion = NoiseUtil.lerp(lowerErosion, upperErosion, alpha);
		cell.weirdness = NoiseUtil.lerp(lowerWeirdness, upperWeirdness, alpha);
		if (select < this.midpoint) {
			cell.terrain = lowerType;
		}
	}
}

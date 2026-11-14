package net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.filter;

import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.FilterSettings;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.noise.NoiseUtil;

public record Smoothing(float smoothingRadius, float smoothingRate, Modifier modifier, boolean sourceBuffered) implements Filter {

	public Smoothing(float smoothingRadius, float smoothingRate, Modifier modifier) {
		this(smoothingRadius, smoothingRate, modifier, false);
	}

	@Override
	public void apply(Filterable map, int seedX, int seedZ, int iterations) {
		if (this.sourceBuffered && iterations > 0) {
			// One transient snapshot per invocation; the existing pass count and halo stay unchanged.
			float[] source = new float[map.getBlockSize().arraySize()];
			while (iterations-- > 0) this.applyBuffered(map, source);
		} else {
			while (iterations-- > 0) {
				this.apply(map);
			}
		}
	}

	private void apply(Filterable cellMap) {
		int radius = NoiseUtil.round(this.smoothingRadius + 0.5F);
		float radiusSq = this.smoothingRadius * this.smoothingRadius;
		
		int maxZ = cellMap.getBlockSize().total() - radius;
		int maxX = cellMap.getBlockSize().total() - radius;
		for (int z = radius; z < maxZ; ++z) {
			for (int x = radius; x < maxX; ++x) {
				Cell cell = cellMap.getCellRaw(x, z);
				if (!cell.erosionMask) {
					float total = 0.0F;
					float weights = 0.0F;
					for (int dz = -radius; dz <= radius; ++dz) {
						for (int dx = -radius; dx <= radius; ++dx) {
							float dist2 = (float) (dx * dx + dz * dz);
							if (dist2 <= radiusSq) {
								int px = x + dx;
								int pz = z + dz;
								Cell neighbour = cellMap.getCellRaw(px, pz);
								if (!neighbour.isAbsent()) {
									float value = neighbour.height;
									float weight = 1.0F - dist2 / radiusSq;
									total += value * weight;
									weights += weight;
								}
							}
						}
					}
					if (weights > 0.0F) {
						float dif = cell.height - total / weights;
						Cell cell2 = cell;
						cell2.height -= this.modifier.modify(cell, dif * this.smoothingRate);
					}
				}
			}
		}
	}
	

	private void applyBuffered(Filterable cellMap, float[] source) {
		int size = cellMap.getBlockSize().total();
		for (int z = 0; z < size; ++z) {
			for (int x = 0; x < size; ++x) {
				source[z * size + x] = cellMap.getCellRaw(x, z).height;
			}
		}
		int radius = NoiseUtil.round(this.smoothingRadius + 0.5F);
		float radiusSq = this.smoothingRadius * this.smoothingRadius;
		int max = size - radius;
		for (int z = radius; z < max; ++z) {
			for (int x = radius; x < max; ++x) {
				Cell cell = cellMap.getCellRaw(x, z);
				if (!cell.erosionMask) {
					double total = 0.0;
					double weights = 0.0;
					for (int dz = -radius; dz <= radius; ++dz) {
						for (int dx = -radius; dx <= radius; ++dx) {
							float dist2 = (float) (dx * dx + dz * dz);
							if (dist2 <= radiusSq) {
								int px = x + dx;
								int pz = z + dz;
								Cell neighbour = cellMap.getCellRaw(px, pz);
								if (!neighbour.isAbsent()) {
									float weight = 1.0F - dist2 / radiusSq;
									total += (double) source[pz * size + px] * weight;
									weights += weight;
								}
							}
						}
					}
					if (weights > 0.0) {
						float height = source[z * size + x];
						float dif = height - (float) (total / weights);
						cell.height = height - this.modifier.modify(cell, dif * this.smoothingRate);
					}
				}
			}
		}
	}

	public static Smoothing make(FilterSettings.Smoothing settings, Levels levels) {
		return make(settings, levels, 1);
	}

	public static Smoothing make(FilterSettings.Smoothing settings, Levels levels, int generationVersion) {
		return new Smoothing(settings.smoothingRadius, settings.smoothingRate, Modifier.range(levels.ground(1), levels.ground(120)).invert(), generationVersion >= 16);
	}
}

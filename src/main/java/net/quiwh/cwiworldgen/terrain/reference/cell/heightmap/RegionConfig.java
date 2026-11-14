package net.quiwh.cwiworldgen.terrain.reference.cell.heightmap;

import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;

public record RegionConfig(int seed, int scale, Noise warpX, Noise warpZ, float warpStrength) {
}

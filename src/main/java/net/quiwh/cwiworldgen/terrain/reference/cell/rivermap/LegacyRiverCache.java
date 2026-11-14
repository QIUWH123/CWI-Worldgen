package net.quiwh.cwiworldgen.terrain.reference.cell.rivermap;

import net.quiwh.cwiworldgen.terrain.reference.noise.NoiseUtil;

public class LegacyRiverCache extends RiverCache {
	
    public LegacyRiverCache(RiverGenerator generator) {
        super(generator);
    }
    
    @Override
    public Rivermap getRivers(int x, int z) {
        return this.cache.computeIfAbsent(NoiseUtil.seed(x, z), id -> this.generator.generateRivers(x, z, id));
    }
}

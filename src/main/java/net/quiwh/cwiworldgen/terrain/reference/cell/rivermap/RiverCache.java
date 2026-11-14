package net.quiwh.cwiworldgen.terrain.reference.cell.rivermap;

import java.util.concurrent.TimeUnit;

import net.quiwh.cwiworldgen.terrain.reference.concurrent.cache.Cache;
import net.quiwh.cwiworldgen.terrain.reference.concurrent.cache.map.StampedLongMap;
import net.quiwh.cwiworldgen.terrain.reference.util.PosUtil;

public class RiverCache {
    protected RiverGenerator generator;
    protected Cache<Rivermap> cache;
    
    public RiverCache(RiverGenerator generator) {
        this.cache = new Cache<>(32, 5L, 1L, TimeUnit.MINUTES, StampedLongMap::new);
        this.generator = generator;
    }
    
    public Rivermap getRivers(int x, int z) {
        return this.cache.computeIfAbsent(PosUtil.pack(x, z), id -> {
        	return this.generator.generateRivers(PosUtil.unpackLeft(id), PosUtil.unpackRight(id), id);
        });
    }
}

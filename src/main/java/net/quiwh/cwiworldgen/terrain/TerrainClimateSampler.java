package net.quiwh.cwiworldgen.terrain;

import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import org.jetbrains.annotations.Nullable;

/** Spawn sampling keeps the terrain instance belonging to the owning noise router. */
public interface TerrainClimateSampler {
    void cwiWorldgen$bindTerrain(@Nullable ReferenceTerrain terrain);
    @Nullable ReferenceTerrain cwiWorldgen$terrain();
}

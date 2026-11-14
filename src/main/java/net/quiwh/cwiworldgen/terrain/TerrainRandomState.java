package net.quiwh.cwiworldgen.terrain;

import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import org.jetbrains.annotations.Nullable;

/** Access to the terrain instance owned by one seeded Minecraft noise router. */
public interface TerrainRandomState {
    @Nullable ReferenceTerrain cwiWorldgen$terrain();
}

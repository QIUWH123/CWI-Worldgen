package net.quiwh.cwiworldgen.terrain.reference.cell.terrain;

/** Package-local construction access for native river geometry fixtures. */
public final class RiverTestTerrain {
    public static Terrain lake() { return new Terrain(-1, "test_lake", TerrainCategory.LAKE); }
    private RiverTestTerrain() {}
}

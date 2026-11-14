package net.quiwh.cwiworldgen.terrain.reference.data.preset.settings;

/** Original settings only; CWI owns registry, biome, feature, and structure data. */
public record Preset(WorldSettings world, SurfaceSettings surface, CaveSettings caves, ClimateSettings climate, TerrainSettings terrain, RiverSettings rivers, IslandSettings island, FilterSettings filters, StructureSettings structures, MiscellaneousSettings miscellaneous) {
    public Preset copy() {
        return new Preset(world.copy(), surface.copy(), caves.copy(), climate.copy(), terrain.copy(), rivers.copy(), island.copy(), filters.copy(), structures.copy(), miscellaneous.copy());
    }
}

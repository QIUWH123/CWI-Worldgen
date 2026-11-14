package net.quiwh.cwiworldgen.landscape;

import java.util.EnumSet;
import java.util.Locale;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverColumn;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainCategory;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;

/** Landform search names describe existing terrain. They are neither biomes nor saved structures. */
public enum LandscapeType {
    PLAINS("Plains"), HILLS("Hills"), PLATEAU("Plateau"), BADLANDS("Badlands"),
    MOUNTAINS("Mountains"), MOUNTAIN_RANGE("Mountain range"), VOLCANO("Volcano"),
    OCEAN("Ocean"), DEEP_OCEAN("Deep ocean"), COAST("Coast"), BEACH("Beach"), ISLAND("Island"),
    RIVER("River"), RIVER_VALLEY("River valley"), GORGE("Gorge"),
    LAKE("Lake"), POND("Pond"), LAGOON("Lagoon"), CLIFF("Cliff"),
    WATERFALL("Waterfall"), RAPIDS("Stepped river reach"), ROCK_SHELF("Rock shelf"),
    ARCH("Rock arch"), OVERHANG("Overhang"), SPIRE("Rock spire"), CAVERN("Cavern");

    private final String title;
    LandscapeType(String title) { this.title = title; }
    public String title() { return title; }
    public String path() { return name().toLowerCase(Locale.ROOT); }
    public String id() { return "cwi_worldgen:" + path(); }
    public int spacing() {
        return switch (this) {
            case ARCH, OVERHANG, SPIRE, CAVERN -> 96;
            case RIVER, RIVER_VALLEY, GORGE, WATERFALL, RAPIDS, POND -> 32;
            default -> 64;
        };
    }
    public static LandscapeType parse(String id) {
        for (var type : values()) if (type.path().equals(id) || type.id().equals(id)) return type;
        return null;
    }

    /** Only inexpensive column facts; local geometry needs the survey's additional confirmation. */
    public static EnumSet<LandscapeType> classify(Cell cell, int seaLevel, int worldHeight) {
        var result = EnumSet.noneOf(LandscapeType.class);
        var terrain = cell.terrain;
        String name = terrain.getName();
        boolean plannedWet = cell.cwiPlannedWater && (int) (cell.height * worldHeight) < (int) (cell.cwiWaterHeight * worldHeight)
                && (cell.cwiBasin || Math.abs(cell.cwiChannelDistance) <= cell.cwiChannelWidth);
        boolean legacyWet = !cell.cwiPlannedWater && ReferenceRiverColumn.isWaterCell(cell)
                && (int) (cell.height * worldHeight) < Math.max(seaLevel - 1,
                        ReferenceRiverColumn.waterTableY(cell, new Levels(worldHeight, seaLevel)));
        if (plannedWet || legacyWet) {
            if (cell.cwiPond) result.add(POND);
            else if (cell.cwiBasin || terrain.isLake()) result.add(LAKE);
            else result.add(RIVER);
        }
        if (!cell.cwiBasin && (cell.cwiChannelWidth > 0 || result.contains(RIVER))) result.add(RIVER_VALLEY);
        if (cell.cwiGorge) result.add(GORGE);
        if ((terrain.isDeepOcean() || terrain.isShallowOcean()) && cell.height * worldHeight < seaLevel) {
            result.add(OCEAN);
            if (terrain.isDeepOcean()) result.add(DEEP_OCEAN);
        }
        if (terrain.isCoast()) result.add(COAST);
        if (terrain == TerrainType.BEACH || terrain == TerrainType.ISLAND_BEACH) result.add(BEACH);
        if (terrain.getCategory() == TerrainCategory.ISLAND || cell.cwiIsland) result.add(ISLAND);
        if (cell.height * worldHeight >= seaLevel && !plannedWet && !legacyWet) {
            if (terrain.isVolcano()) result.add(VOLCANO);
            else if (terrain.isMountain() || name.contains("mountain")) {
                result.add(MOUNTAINS);
                if (name.contains("mountain_chain")) result.add(MOUNTAIN_RANGE);
            }
            if (name.contains("badlands")) result.add(BADLANDS);
            else if (name.contains("plateau")) result.add(PLATEAU);
            else if (name.contains("hills")) result.add(HILLS);
            else if (terrain == TerrainType.FLATS || name.equals("flats")) result.add(PLAINS);
        }
        return result;
    }
}

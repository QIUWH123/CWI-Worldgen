package net.quiwh.cwiworldgen.terrain.reference.cell;

import net.quiwh.cwiworldgen.terrain.reference.concurrent.Resource;
import net.quiwh.cwiworldgen.terrain.reference.concurrent.SimpleResource;
import net.quiwh.cwiworldgen.terrain.reference.concurrent.pool.ThreadLocalPool;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.RiverCarverSettings;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.Terrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;

public class Cell {
    private static final Cell DEFAULTS = new Cell();
    private static final Cell EMPTY = new Cell() {

    	@Override
        public boolean isAbsent() {
            return true;
        }
    };
    private static final ThreadLocalPool<Cell> POOL = new ThreadLocalPool<>(32, Cell::new, Cell::reset);
    public static final ThreadLocal<Resource<Cell>> LOCAL = ThreadLocal.withInitial(() -> {
        return new SimpleResource<>(new Cell(), Cell::reset);
    });
    public boolean cwiIsland;
    public boolean cwiDryChannel;
    public boolean cwiPlannedWater;
    public boolean cwiBasin;
    public boolean cwiShoal;
    public boolean cwiGorge;
    public boolean cwiNaturalRiver;
    public boolean cwiPond;
    public boolean cwiPondProtected;
    public net.quiwh.cwiworldgen.terrain.RockShelfRelief.Sample cwiRockShelf;
    public float cwiOriginalGroundHeight;
    public float cwiBedHeight;
    public float cwiWaterHeight;
    public float cwiChannelWidth;
    public float cwiChannelDistance;
    public float height;
    public float heightErosion;
    public float sediment;
    public float gradient;
    public float regionMoisture;
    public float regionTemperature;
    public float continentId;
    public float continentSizeModifier;
    public float continentEdge;
    public float waterTable;
    public float continentDistance;
    public float terrainRegionId;
    public float terrainRegionEdge;
    public float terrainRegionCenterX;
    public float terrainRegionCenterZ;
    public float biomeRegionId;
    public float biomeRegionEdge;
    public float macroBiomeId;
    public float riverMask;
    public float riverWaterLevel = 0.0F;
    public int continentX;
    public int continentZ;
    public float globalContinentScale;
    public boolean erosionMask;
    public Terrain terrain;

    public float erosion;
    public float weirdness;
    public float temperature;
    public float moisture;

    public float beachNoise;
    public RiverCarverSettings.RiverZone riverZone = RiverCarverSettings.RiverZone.None;

    public Cell() {
        this.regionMoisture = 0.5F;
        this.regionTemperature = 0.5F;
        this.biomeRegionEdge = 1.0F;
        this.riverMask = 1.0F;
        this.erosionMask = false;
        this.terrain = TerrainType.NONE;

        this.waterTable = 0.0F;
        this.continentSizeModifier = 1.0F;
    }
    
    public void copyFrom(Cell other) {
        this.cwiRockShelf = other.cwiRockShelf;
        this.cwiIsland = other.cwiIsland;
        this.cwiDryChannel = other.cwiDryChannel;
        this.cwiPlannedWater = other.cwiPlannedWater;
        this.cwiBasin = other.cwiBasin;
        this.cwiShoal = other.cwiShoal;
        this.cwiGorge = other.cwiGorge;
        this.cwiNaturalRiver = other.cwiNaturalRiver;
        this.cwiPond = other.cwiPond;
        this.cwiPondProtected = other.cwiPondProtected;
        this.cwiOriginalGroundHeight = other.cwiOriginalGroundHeight;
        this.cwiBedHeight = other.cwiBedHeight;
        this.cwiWaterHeight = other.cwiWaterHeight;
        this.cwiChannelWidth = other.cwiChannelWidth;
        this.cwiChannelDistance = other.cwiChannelDistance;
        this.height = other.height;
        this.heightErosion = other.heightErosion;
        this.sediment = other.sediment;
        this.gradient = other.gradient;
        this.regionMoisture = other.regionMoisture;
        this.regionTemperature = other.regionTemperature;
        this.continentId = other.continentId;
        this.continentEdge = other.continentEdge;
        this.continentDistance = other.continentDistance;
        this.terrainRegionId = other.terrainRegionId;
        this.terrainRegionEdge = other.terrainRegionEdge;
        this.terrainRegionCenterX = other.terrainRegionCenterX;
        this.terrainRegionCenterZ = other.terrainRegionCenterZ;
        this.biomeRegionId = other.biomeRegionId;
        this.biomeRegionEdge = other.biomeRegionEdge;
        this.macroBiomeId = other.macroBiomeId;
        this.riverMask = other.riverMask;
        this.continentX = other.continentX;
        this.continentZ = other.continentZ;
        this.erosionMask = other.erosionMask;
        this.terrain = other.terrain;

        this.erosion = other.erosion;
        this.weirdness = other.weirdness;
        this.temperature = other.temperature;
        this.moisture = other.moisture;
        this.beachNoise = other.beachNoise;
        this.continentSizeModifier = other.continentSizeModifier;
        this.riverWaterLevel = other.riverWaterLevel;
        this.riverZone = other.riverZone;
        this.waterTable = other.waterTable;
        this.globalContinentScale = other.globalContinentScale;
    }

    public void clearCwiWatershed() {
        this.cwiIsland = false;
        this.cwiDryChannel = false;
        this.cwiPlannedWater = false;
        this.cwiBasin = false;
        this.cwiShoal = false;
        this.cwiGorge = false;
        this.cwiNaturalRiver = false;
        this.cwiPond = false;
        this.cwiPondProtected = false;
        this.cwiOriginalGroundHeight = 0F;
        this.cwiBedHeight = 0F;
        this.cwiWaterHeight = 0F;
        this.cwiChannelWidth = 0F;
        this.cwiChannelDistance = 0F;
    }

    public Cell reset() {
        this.copyFrom(Cell.DEFAULTS);
        return this;
    }

    public boolean isAbsent() {
        return false;
    }

    public static Cell empty() {
        return Cell.EMPTY;
    }

    public static Resource<Cell> getResource() {
        Resource<Cell> resource = Cell.LOCAL.get();
        if (resource.isOpen()) {
            return Cell.POOL.get();
        }
        return resource;
    }
    
    public interface Visitor {
        void visit(Cell cell, int x, int z);
    }
}

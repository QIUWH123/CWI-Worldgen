package net.quiwh.cwiworldgen.terrain.reference;

import java.util.function.IntFunction;

import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.FilterSettings;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.Tile;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.filter.BeachDetect;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.filter.Erosion;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.filter.Filterable;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.filter.NoiseCorrection;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.filter.Smoothing;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.filter.Steepness;

public class WorldFilters {
    private Smoothing smoothing;
    private Steepness steepness;
    private BeachDetect beach;
    private NoiseCorrection corrections;
    private FilterSettings settings;
    private WorldErosion<Erosion> erosion;
    private int erosionIterations;
    private int smoothingIterations;
    private final net.quiwh.cwiworldgen.terrain.RockFaceReliefFilter rockFaces;
    
    public WorldFilters(GeneratorContext context) {
        this.rockFaces = new net.quiwh.cwiworldgen.terrain.RockFaceReliefFilter(
                context.seed.root(), context.generationVersion, context.levels.worldHeight, context.levels.waterLevel);
        IntFunction<Erosion> factory = Erosion.factory(context);
        this.settings = context.preset.filters();
        this.beach = BeachDetect.make(context);
        this.smoothing = Smoothing.make(context.preset.filters().smoothing, context.levels, context.baseGenerationVersion);
        this.steepness = Steepness.make(1, 10.0F, context.levels, context.generationVersion);
        this.corrections = new NoiseCorrection(context.levels);
        this.erosion = new WorldErosion<>(factory, (e, size) -> e.getSize() == size);
        this.erosionIterations = context.preset.filters().erosion.dropletsPerChunk;
        this.smoothingIterations = context.preset.filters().smoothing.iterations;
    }
    
    public FilterSettings getSettings() {
        return this.settings;
    }
    
    public void apply(Tile tile, boolean optionalFilters) {
        int regionX = tile.getX();
        int regionZ = tile.getZ();
        
        if (optionalFilters) {
            this.applyOptionalFilters(tile, regionX, regionZ);
        }
        if (optionalFilters) this.rockFaces.apply(tile);
        this.applyRequiredFilters(tile, regionX, regionZ);
        if(optionalFilters) {
        	this.applyCorrections(tile, regionX, regionZ);
        }
        for (var cell : tile.getBacking()) if (cell != null) {
            net.quiwh.cwiworldgen.hydrology.watershed.WatershedHydrology.finalizeCell(cell);
            // Do not decorate a shelf if later correction/water protection changed its accepted floor.
            if (cell.cwiRockShelf != null && !cell.cwiRockShelf.retainedAt(cell.height)) cell.cwiRockShelf = null;
        }
    }
    
    private void applyRequiredFilters(Filterable map, int seedX, int seedZ) {
        this.steepness.apply(map, seedX, seedZ, 1);
        this.beach.apply(map, seedX, seedZ, 1);
    }
    
    private void applyOptionalFilters(Filterable map, int seedX, int seedZ) {
        Erosion erosion = this.erosion.get(map.getBlockSize().total());
        erosion.apply(map, seedX, seedZ, this.erosionIterations);
        this.smoothing.apply(map, seedX, seedZ, this.smoothingIterations);
    }
    
    public void applyCorrections(Filterable map, int seedX, int seedZ) {
        this.corrections.apply(map, seedX, seedZ, 1);
    }
}

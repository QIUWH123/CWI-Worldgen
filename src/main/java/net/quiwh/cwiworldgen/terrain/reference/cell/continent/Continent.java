package net.quiwh.cwiworldgen.terrain.reference.cell.continent;

import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.CellPopulator;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.Rivermap;

public interface Continent extends CellPopulator {
    float getEdgeValue(float x, float z);
    
    default float getLandValue(float x, float z) {
        return this.getEdgeValue(x, z);
    }
    
    long getNearestCenter(float x, float z);
    
    Rivermap getRivermap(int x, int z);
    
    default Rivermap getRivermap(Cell cell) {
        return this.getRivermap(cell.continentX, cell.continentZ);
    }
}

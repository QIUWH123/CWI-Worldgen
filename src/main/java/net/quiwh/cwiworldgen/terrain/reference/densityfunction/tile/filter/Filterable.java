package net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.filter;

import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.Size;

public interface Filterable {
    int getBlockX();
    
    int getBlockZ();
    
    Size getBlockSize();
    
    Cell[] getBacking();
    
    Cell getCellRaw(int x, int z);
}

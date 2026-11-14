package net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.filter;

import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.filter.Filter.Visitor;

public record Steepness(int radius, float scaler, float waterLevel, int generationVersion) implements Filter, Visitor {

    public Steepness(int radius, float scaler, float waterLevel) {
        this(radius, scaler, waterLevel, 1);
    }

	@Override
	public void apply(Filterable cellMap, int seedX, int seedZ, int iterations) {
		this.iterate(cellMap, this);
	}

	@Override
	public void visit(Filterable cellMap, Cell cell, int cx, int cz) {
        if (this.generationVersion >= 18) {
            this.visitSymmetric(cellMap, cell, cx, cz);
            return;
        }
		float totalHeightDif = 0.0F;
		for (int dz = -1; dz <= 2; ++dz) {
			for (int dx = -1; dx <= 2; ++dx) {
				if (dx != 0 || dz != 0) {
					int x = cx + dx * this.radius;
					int z = cz + dz * this.radius;
					Cell neighbour = cellMap.getCellRaw(x, z);
					if (!neighbour.isAbsent()) {
						float height = Math.max(neighbour.height, this.waterLevel);
						totalHeightDif += Math.abs(cell.height - height) / this.radius;
					}
				}
			}
		}
		cell.gradient = totalHeightDif * this.scaler;
	}
	

    // Integral over uniformly rotated legacy[-1,2] offsets: (2/pi) * sum(hypot(dx,dz)).
    private static final double LEGACY_ORIENTATION_MEAN = 16.18895642322071;

    private void visitSymmetric(Filterable cellMap, Cell cell, int cx, int cz) {
        if (cell == null) return;
        if (cell.isAbsent() || !Float.isFinite(cell.height) || this.radius <= 0) {
            cell.gradient = 0;
            return;
        }
        double squaredHeightDif = 0;
        int moment = 0;
        int side = cellMap.getBlockSize().total();
        for (int ring = 1; ring <= 2; ring++) {
            double ringDif = 0;
            boolean complete = true;
            for (int dz = -ring; dz <= ring; dz++) for (int dx = -ring; dx <= ring; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                int x = cx + dx * this.radius, z = cz + dz * this.radius;
                // Tile.getCellRaw validates only the flat index; x=-1 can alias
                // the preceding row. Require genuine two-dimensional support.
                if (x < 0 || z < 0 || x >= side || z >= side) {
                    complete = false;
                    continue;
                }
                Cell neighbour = cellMap.getCellRaw(x, z);
                if (neighbour == null || neighbour.isAbsent() || !Float.isFinite(neighbour.height)) {
                    complete = false;
                    continue;
                }
                // Keep the existing neighbor sea clamp and unclamped center policy.
                double difference = cell.height - Math.max(neighbour.height, this.waterLevel);
                ringDif += difference * difference;
            }
            // Whole square rings have isotropic support tensors (moments6 and44).
            // An incomplete ring contributes nothing, including at nonfinite edges.
            if (complete) {
                squaredHeightDif += ringDif;
                moment += ring == 1 ? 6 : 44;
            }
        }
        cell.gradient = moment == 0 ? 0 : (float) (Math.sqrt(squaredHeightDif / moment)
                / this.radius * LEGACY_ORIENTATION_MEAN * this.scaler);
    }

	public static Steepness make(int radius, float scaler, Levels levels) {
		return new Steepness(radius, scaler, levels.water);
	}

    public static Steepness make(int radius, float scaler, Levels levels, int generationVersion) {
        return new Steepness(radius, scaler, levels.water, generationVersion);
    }
}

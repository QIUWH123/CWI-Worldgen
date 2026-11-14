package net.quiwh.cwiworldgen.terrain;

/** Continuous planar fracture recesses. No height snapping, added peaks, or block-scale noise. */
public final class RockFaceRelief {
    public static final double MAX_CUT = 5.5;
    private static final int SPACING = 96;
    private RockFaceRelief() {}

    /** Inputs are normalized rock resistance and broad slope, independent of the relief itself. */
    public static double cut(long seed, double x, double z, double resistance, double steepness) {
        return cut(seed, x, z, resistance, steepness, 1);
    }

    public static double cut(long seed, double x, double z, double resistance, double steepness, int generationVersion) {
        double strength = clamp(resistance) * clamp(steepness);
        if (strength == 0) return 0;
        int cellX = grid(x), cellZ = grid(z);
        double recess = 0;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            long key = key(seed, cellX + dx, cellZ + dz);
            for (int i = 0; i < 3; i++)
                recess = Math.max(recess, Face.make(key, cellX + dx, cellZ + dz, i, generationVersion).cut(x, z));
        }
        return recess * strength;
    }

    /** Local immutable descriptor cache; hot tile loops do no hashing, allocation, or square roots. */
    static final class Field {
        private final int minX, minZ, width;
        private final Face[][] cells;
        Field(long seed, int x0, int z0, int x1, int z1) {
            this(seed, x0, z0, x1, z1, 1);
        }
        Field(long seed, int x0, int z0, int x1, int z1, int generationVersion) {
            minX = grid(x0) - 1; minZ = grid(z0) - 1;
            width = grid(x1) - minX + 2;
            int height = grid(z1) - minZ + 2;
            cells = new Face[width * height][];
            for (int z = 0; z < height; z++) for (int x = 0; x < width; x++) {
                long key = key(seed, x + minX, z + minZ);
                cells[z * width + x] = new Face[]{Face.make(key, x + minX, z + minZ, 0, generationVersion),
                        Face.make(key, x + minX, z + minZ, 1, generationVersion), Face.make(key, x + minX, z + minZ, 2, generationVersion)};
            }
        }
        double cut(double x, double z, double resistance, double steepness) {
            double strength = clamp(resistance) * clamp(steepness);
            if (strength == 0) return 0;
            int cx = grid(x) - minX, cz = grid(z) - minZ;
            double recess = 0;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                for (Face face : cells[(cz + dz) * width + cx + dx])
                    recess = Math.max(recess, face.cut(x, z));
            return recess * strength;
        }
    }

    private record Face(double x, double z, double nx, double nz,
                        double halfLength, double halfWidth, double amplitude) {
        static Face make(long key, int cellX, int cellZ, int index) {
            return make(key, cellX, cellZ, index, 1);
        }
        static Face make(long key, int cellX, int cellZ, int index, int generationVersion) {
            long h = mix(key + index * 0xd1b54a32d192ed03L);
            double x = (cellX + .5) * SPACING + (unit(h) - .5) * 48;
            double z = (cellZ + .5) * SPACING + (unit(mix(h + 1)) - .5) * 48;
            double nx, nz, length;
            if (generationVersion >= 18) {
                double angle = unit(mix(h + 2)) * (2 * Math.PI);
                nx = Math.cos(angle); nz = Math.sin(angle); length = 1;
            } else {
                nx = unit(mix(h + 2)) * 2 - 1; nz = unit(mix(h + 3)) * 2 - 1;
                length = Math.sqrt(nx * nx + nz * nz);
                if (length < .01) { nx = 1; nz = 0; length = 1; }
            }
            return new Face(x, z, nx / length, nz / length, 38 + unit(mix(h + 4)) * 30,
                    3.5 + unit(mix(h + 5)) * 4.5, 3.5 + unit(mix(h + 6)) * (MAX_CUT - 3.5));
        }
        double cut(double px, double pz) {
            px -= x; pz -= z;
            if (Math.abs(px) > 70 || Math.abs(pz) > 70) return 0;
            double along = px * nx + pz * nz, across = pz * nx - px * nz;
            double end = Math.min(1, (1 - Math.abs(along) / halfLength) * 4);
            double side = 1 - Math.abs(across) / halfWidth;
            // min joins planes along sharp finite ridges, rather than rounding intersections.
            return Math.max(0, Math.min(end, side)) * amplitude;
        }
    }

    private static int grid(double position) { return (int) Math.floor(position / SPACING); }
    private static long key(long seed, int x, int z) {
        return mix(seed ^ x * 0x632be59bd9b4e019L ^ z * 0x9e3779b97f4a7c15L);
    }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static double unit(long value) { return (value >>> 11) * 0x1.0p-53; }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}

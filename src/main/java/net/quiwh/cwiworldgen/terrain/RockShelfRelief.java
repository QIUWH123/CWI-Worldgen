package net.quiwh.cwiworldgen.terrain;

/** Rare dipping rocky beds: broad supported treads separated by short, finite risers. */
public final class RockShelfRelief {
    public static final double MAX_CUT = 8;
    static final int SPACING = 384;
    private static final double CHANCE = .22;

    private RockShelfRelief() {}

    /** One immutable winner from the same pre-cut snapshot used by the relief filter. */
    public record Sample(long districtId, double bedOffset, double localThickness,
                         double outlineSupport, double geometryCut, double winnerMargin,
                         float acceptedHeight) {
        public double bedCoordinate(double actualY) { return (actualY + bedOffset) / localThickness; }
        public boolean retainedAt(float height) {
            return Float.floatToRawIntBits(acceptedHeight) == Float.floatToRawIntBits(height);
        }
    }

    /** Caller-owned per-filter scratch; it never escapes into a terrain cell or shared field. */
    static final class Selection {
        private District winner;
        private double cut, margin;

        Sample sample(double x, double z, float acceptedHeight) {
            return winner == null ? null : winner.sample(x, z, cut, margin, acceptedHeight);
        }

        private void clear() {
            winner = null;
            cut = 0;
            margin = 0;
        }
    }

    /** Future-profile descriptor query. Historical scalar paths below remain unchanged. */
    public static Sample sample(long seed, double x, double z, double height, double resistance, double flank) {
        return new Field(seed, (int) Math.floor(x), (int) Math.floor(z),
                (int) Math.floor(x), (int) Math.floor(z)).sample(x, z, height, resistance, flank);
    }

    /** Height is the original broad terrain in blocks; selection never reads already-cut neighbors. */
    public static double cut(long seed, double x, double z, double height, double resistance, double flank) {
        double strength = clamp(resistance) * clamp(flank);
        if (strength == 0) return 0;
        int cellX = grid(x), cellZ = grid(z);
        double cut = 0;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            District district = District.make(seed, cellX + dx, cellZ + dz);
            if (district != null) cut = Math.max(cut, district.cut(x, z, height));
        }
        return cut * strength;
    }

    /** Immutable tile-local descriptors; the column loop performs no hashing or allocation. */
    static final class Field {
        private final int minX, minZ, width;
        private final District[] districts;

        Field(long seed, int x0, int z0, int x1, int z1) {
            minX = grid(x0) - 1;
            minZ = grid(z0) - 1;
            width = grid(x1) - minX + 2;
            int depth = grid(z1) - minZ + 2;
            districts = new District[width * depth];
            for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++)
                districts[z * width + x] = District.make(seed, minX + x, minZ + z);
        }

        double cut(double x, double z, double height, double resistance, double flank) {
            double strength = clamp(resistance) * clamp(flank);
            if (strength == 0) return 0;
            int cellX = grid(x) - minX, cellZ = grid(z) - minZ;
            double cut = 0;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                District district = districts[(cellZ + dz) * width + cellX + dx];
                if (district != null) cut = Math.max(cut, district.cut(x, z, height));
            }
            return cut * strength;
        }

        /** Same scalar and winning bed as cut + sample, with one district traversal. */
        double cut(double x, double z, double height, double resistance, double flank, Selection selection) {
            selection.clear();
            double strength = clamp(resistance) * clamp(flank);
            if (strength == 0) return 0;
            // Preserve the historical scalar's non-finite propagation without retaining metadata.
            if (!Double.isFinite(height)) return cut(x, z, height, resistance, flank);
            int cellX = grid(x) - minX, cellZ = grid(z) - minZ;
            District winner = null;
            double best = 0, second = 0;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                District district = districts[(cellZ + dz) * width + cellX + dx];
                if (district == null) continue;
                double candidate = district.cut(x, z, height);
                if (candidate > best || candidate > 0 && candidate == best
                        && (winner == null || Long.compareUnsigned(district.id, winner.id) < 0)) {
                    second = best;
                    best = candidate;
                    winner = district;
                } else second = Math.max(second, candidate);
            }
            selection.winner = winner;
            selection.cut = best * strength;
            selection.margin = (best - second) * strength;
            return selection.cut;
        }

        Sample sample(double x, double z, double height, double resistance, double flank) {
            return sample(x, z, height, resistance, flank, Float.NaN);
        }

        Sample sample(double x, double z, double height, double resistance, double flank, float acceptedHeight) {
            double strength = clamp(resistance) * clamp(flank);
            if (strength == 0 || !Double.isFinite(height)) return null;
            int cellX = grid(x) - minX, cellZ = grid(z) - minZ;
            District winner = null;
            double best = 0, second = 0;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                District district = districts[(cellZ + dz) * width + cellX + dx];
                if (district == null) continue;
                double candidate = district.cut(x, z, height);
                if (candidate > best || candidate > 0 && candidate == best
                        && (winner == null || Long.compareUnsigned(district.id, winner.id) < 0)) {
                    second = best;
                    best = candidate;
                    winner = district;
                } else second = Math.max(second, candidate);
            }
            return winner == null ? null : winner.sample(x, z, best * strength,
                    (best - second) * strength, acceptedHeight);
        }
    }

    private record District(long id, double x, double z, double nx, double nz, double halfLength, double halfWidth,
                            double dipAlong, double dipAcross, double thickness, double tread,
                            double phase, double foldAmplitude, double foldFrequency, double foldPhase) {
        static District make(long seed, int cellX, int cellZ) {
            long key = mix(seed ^ cellX * 0x632be59bd9b4e019L ^ cellZ * 0x9e3779b97f4a7c15L
                    ^ 0x4357495348454c46L);
            if (unit(key) >= CHANCE) return null;
            double angle = unit(mix(key + 1)) * Math.PI * 2;
            return new District(key, (cellX + .5) * SPACING + (unit(mix(key + 2)) - .5) * 120,
                    (cellZ + .5) * SPACING + (unit(mix(key + 3)) - .5) * 120,
                    Math.cos(angle), Math.sin(angle), 95 + unit(mix(key + 4)) * 60,
                    55 + unit(mix(key + 5)) * 40,
                    (unit(mix(key + 6)) * 2 - 1) * .23,
                    (unit(mix(key + 7)) * 2 - 1) * .10,
                    6.8 + unit(mix(key + 8)) * 2.6, .68 + unit(mix(key + 9)) * .12,
                    unit(mix(key + 10)) * 64, .8 + unit(mix(key + 11)) * 1.4,
                    .018 + unit(mix(key + 12)) * .014, unit(mix(key + 13)) * Math.PI * 2);
        }

        double cut(double px, double pz, double height) {
            px -= x;
            pz -= z;
            if (Math.abs(px) > 205 || Math.abs(pz) > 205) return 0;
            double along = px * nx + pz * nz;
            double across = pz * nx - px * nz;
            double radius = along * along / (halfLength * halfLength)
                    + across * across / (halfWidth * halfWidth);
            // Continuous unequal ends fade the formation instead of clipping it at district edges.
            double outline = 1 + .12 * Math.sin(along * .021 + foldPhase)
                    + .08 * Math.sin(across * .037 - foldPhase);
            double support = smooth((outline - radius) / .38);
            if (support == 0) return 0;
            double fold = foldAmplitude * Math.sin(along * foldFrequency + foldPhase)
                    + .35 * foldAmplitude * Math.sin(across * foldFrequency * .71 - foldPhase);
            double localThickness = thickness * (1 + .06 * Math.sin(along * .013 + foldPhase));
            double bedHeight = height - along * dipAlong - across * dipAcross - fold + phase;
            double fraction = bedHeight / localThickness - Math.floor(bedHeight / localThickness);
            double treadCut = fraction * localThickness;
            if (fraction > tread)
                treadCut = tread * localThickness * (1 - smooth((fraction - tread) / (1 - tread)));
            return Math.min(MAX_CUT, treadCut) * support;
        }

        Sample sample(double px, double pz, double cut, double margin, float acceptedHeight) {
            px -= x;
            pz -= z;
            double along = px * nx + pz * nz;
            double across = pz * nx - px * nz;
            double radius = along * along / (halfLength * halfLength)
                    + across * across / (halfWidth * halfWidth);
            double outline = 1 + .12 * Math.sin(along * .021 + foldPhase)
                    + .08 * Math.sin(across * .037 - foldPhase);
            double support = smooth((outline - radius) / .38);
            double fold = foldAmplitude * Math.sin(along * foldFrequency + foldPhase)
                    + .35 * foldAmplitude * Math.sin(across * foldFrequency * .71 - foldPhase);
            double localThickness = thickness * (1 + .06 * Math.sin(along * .013 + foldPhase));
            double offset = -along * dipAlong - across * dipAcross - fold + phase;
            return new Sample(id, offset, localThickness, support, cut, margin, acceptedHeight);
        }
    }

    private static int grid(double position) { return (int) Math.floor(position / SPACING); }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static double smooth(double value) {
        value = clamp(value);
        return value * value * (3 - 2 * value);
    }
    private static double unit(long value) { return (value >>> 11) * 0x1.0p-53; }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}

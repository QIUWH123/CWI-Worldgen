package net.quiwh.cwiworldgen.terrain;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscapeProfiles.Kind;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.RiverCarverSettings;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.Tile;

/** Seed-local analytic geometry. It never writes blocks or mutates published terrain cells. */
public final class ReferenceLandscape {
    private final ReferenceTerrain terrain;
    private final long seed;
    private final BoundedCaveBiomeSource caves;
    private final ReferenceLandscapeProfiles.ShapePolicy shapePolicy;
    private volatile Climate.Sampler climate;
    private final ThreadLocal<Cache> cache = ThreadLocal.withInitial(Cache::new);

    public ReferenceLandscape(ReferenceTerrain terrain, long seed, BoundedCaveBiomeSource caves) {
        this.terrain = terrain;
        this.seed = seed;
        this.caves = caves;
        this.shapePolicy = ReferenceLandscapeProfiles.select(terrain.generationVersion());
    }

    public void bindClimate(Climate.Sampler sampler) { climate = sampler; }

    public double density(int x, int y, int z, boolean accurate) {
        Cache local = cache.get();
        Formation surface = surfaceAt(x, z, local);
        Formation cavern = cavernAt(x, z, local);
        double a = surface == null ? 64 : surface.density(x, y, z, shapePolicy);
        double b = cavern == null ? 64 : cavern.density(x, y, z, shapePolicy);
        if (a >= 0 && b >= 0) return 64;
        if (terrain.generationVersion() >= 12 && terrain.waterProtection().column(x, z).protects(y)) return 64;
        Cell column = column(x, z, accurate, local);
        if (!dry(column) || !dryHalo(x, z, accurate, local)) return 64;
        // Caverns cannot breach even a low hillside; the roof follows completed terrain.
        if (b < 0 && (y + ReferenceLandscapeProfiles.MIN_CAVERN_ROOF >= column.height * 256
                || !insideDistrict(cavern, x, y, z, accurate, local))) b = 64;
        // Do not open a formation into the source-water/retaining-bank elevation band.
        if (a < 0 && y < terrain.levels().waterLevel + 8) a = 64;
        return Math.min(a, b);
    }

    /** Conservative footprint rejection supplements CWI's existing slope/water screening. */
    public boolean intersectsSurfaceFormation(BoundingBox box) {
        Cache local = cache.get();
        int size = ReferenceLandscapeProfiles.SURFACE_CELL;
        for (int cx = Math.floorDiv(box.minX(), size); cx <= Math.floorDiv(box.maxX(), size); cx++) {
            for (int cz = Math.floorDiv(box.minZ(), size); cz <= Math.floorDiv(box.maxZ(), size); cz++) {
                Formation shape = surfaceAt(cx * size + size / 2, cz * size + size / 2, local);
                if (shape != null && box.maxX() >= shape.x - shape.bound() && box.minX() <= shape.x + shape.bound()
                        && box.maxZ() >= shape.z - shape.bound() && box.minZ() <= shape.z + shape.bound()
                        && box.minY() <= shape.y + shape.ry + 12 && box.maxY() >= shape.y - shape.ry - 12) return true;
            }
        }
        return false;
    }

    /** Diagnostic immutable descriptors, also used by the multi-seed generation tests. */
    public Formation surfaceFormationAt(int x, int z) { return surfaceAt(x, z, cache.get()); }
    public Formation cavernFormationAt(int x, int z) { return cavernAt(x, z, cache.get()); }
    public int cavernCellSize() { return caves.cellSize(); }

    private Formation surfaceAt(int x, int z, Cache local) {
        int size = ReferenceLandscapeProfiles.SURFACE_CELL;
        int cx = Math.floorDiv(x, size), cz = Math.floorDiv(z, size);
        long key = ChunkPos.asLong(cx, cz);
        if (local.lastSurfaceValid && local.lastSurfaceKey == key) return local.lastSurface;
        if (local.surface.containsKey(key)) return local.rememberSurface(key, local.surface.get(key));
        long salt = mix(seed ^ mix(cx * 0x632be59bd9b4e019L) ^ mix(cz * 0x9e3779b97f4a7c15L) ^ 0x43574941524348L);
        Formation shape = null;
        if (unit(salt) < ReferenceLandscapeProfiles.SURFACE_CHANCE) {
            int ax = cx * size + 48 + (int) (unit(salt + 1) * (size - 96));
            int az = cz * size + 48 + (int) (unit(salt + 2) * (size - 96));
            Cell center = terrain.sampleRaw(ax, az);
            double west = terrain.sampleRaw(ax - 16, az).height * 256;
            double east = terrain.sampleRaw(ax + 16, az).height * 256;
            double north = terrain.sampleRaw(ax, az - 16).height * 256;
            double south = terrain.sampleRaw(ax, az + 16).height * 256;
            double relief = Math.max(Math.max(west, east), Math.max(north, south))
                    - Math.min(Math.min(west, east), Math.min(north, south));
            if (dry(center) && center.height * 256 > terrain.levels().waterLevel + 28 && relief >= 12) {
                Kind kind = Kind.values()[(int) (unit(salt + 3) * 3)];
                double rx = 18 + unit(salt + 4) * 16;
                double rz = 8 + unit(salt + 5) * 7;
                double ry = Math.min(18, 7 + relief * .12 + unit(salt + 6) * 4);
                double top = center.height * 256;
                shape = new Formation(kind, ax, top - ry + (kind == Kind.SPIRE ? 6 : -5), az, rx, ry, rz,
                        Math.atan2(south - north, east - west), salt, null);
            }
        }
        local.surface.put(key, shape);
        return local.rememberSurface(key, shape);
    }

    private Formation cavernAt(int x, int z, Cache local) {
        Climate.Sampler sampler = climate;
        if (sampler == null) return null;
        int cx = Math.floorDiv(x, caves.cellSize()), cz = Math.floorDiv(z, caves.cellSize());
        long key = ChunkPos.asLong(cx, cz);
        if (local.lastCavernValid && local.lastCavernKey == key) return local.lastCavern;
        if (local.caverns.containsKey(key)) return local.rememberCavern(key, local.caverns.get(key));
        var district = caves.districtAt(x, z, sampler);
        long salt = mix(seed ^ district.seed() ^ 0x43415645524eL);
        Formation shape = null;
        if (unit(salt) < ReferenceLandscapeProfiles.CAVERN_CHANCE) {
            var region = district.region();
            double ry = Math.min(14, (region.maxY() - region.minY() - 24) * .5);
            if (ry >= 5) {
                ry *= .78 + unit(salt + 1) * .22;
                double low = region.minY() + ry + 10, high = region.maxY() - ry - 10;
                double y = low + unit(salt + 2) * Math.max(0, high - low);
                double rx = Math.min(district.radius() * .32, 19 + unit(salt + 3) * 17);
                double rz = rx * (.65 + unit(salt + 4) * .3);
                shape = new Formation(Kind.CAVERN, district.centerX(), y, district.centerZ(), rx, ry, rz,
                        unit(salt + 5) * Math.PI * 2, salt, region);
            }
        }
        local.caverns.put(key, shape);
        return local.rememberCavern(key, shape);
    }

    private boolean insideDistrict(Formation shape, int x, int y, int z, boolean accurate, Cache local) {
        Climate.Sampler sampler = climate;
        if (sampler == null) return false;
        // The cave source rounds all three coordinates to quart blocks, and
        // every buffer offset is a multiple of four: 64 voxels share this result.
        long key = BlockPos.asLong(x >> 2, y >> 2, z >> 2);
        Map<Long, Boolean> masks = accurate ? local.accurateDistricts : local.rawDistricts;
        Boolean known = masks.get(key);
        if (known != null) return known;
        java.util.function.Supplier<Boolean> test = () -> {
            int buffer = ReferenceLandscapeProfiles.DISTRICT_BUFFER;
            for (int dx : new int[] {-buffer, buffer}) for (int dy : new int[] {-4, 4})
                for (int dz : new int[] {-buffer, buffer}) {
                    if (caves.caveRegionAtBlock(x + dx, y + dy, z + dz, sampler) != shape.region) return false;
                }
            return true;
        };
        boolean accepted = accurate ? ReferenceTerrainDensity.withAccurateTerrain(test) : test.get();
        masks.put(key, accepted);
        return accepted;
    }

    private Cell column(int x, int z, boolean accurate, Cache local) {
        if (local.x != x || local.z != z || local.accurate != accurate || local.column == null) {
            if (accurate) {
                if (local.reader == null || local.reader.getChunkX() != x >> 4 || local.reader.getChunkZ() != z >> 4)
                    local.reader = terrain.prepareChunk(x >> 4, z >> 4);
                local.column = local.reader.getCell(x, z);
            } else local.column = terrain.sampleRaw(x, z);
            local.x = x; local.z = z; local.accurate = accurate;
        }
        return local.column;
    }

    private boolean dryHalo(int x, int z, boolean accurate, Cache local) {
        long key = ChunkPos.asLong(x, z);
        Map<Long, Boolean> masks = accurate ? local.accurateDry : local.rawDry;
        Boolean cached = masks.get(key);
        if (cached != null) return cached;
        int buffer = ReferenceLandscapeProfiles.SURFACE_WATER_BUFFER;
        boolean dry = true;
        for (int dx = -buffer; dx <= buffer && dry; dx += buffer) {
            for (int dz = -buffer; dz <= buffer; dz += buffer) {
                if (!dry(terrain.sampleRaw(x + dx, z + dz))
                        || accurate && !dry(terrain.prepareChunk((x + dx) >> 4, (z + dz) >> 4).getCell(x + dx, z + dz))) {
                    dry = false; break;
                }
            }
        }
        masks.put(key, dry);
        return dry;
    }

    public static boolean dry(Cell cell) {
        return Float.isFinite(cell.height) && cell.terrain != null && cell.terrain != TerrainType.NONE
                && !cell.terrain.isRiver() && !cell.terrain.isLake() && !cell.terrain.isWetland()
                && !cell.terrain.isSubmerged() && !cell.terrain.isDeepOcean() && !cell.terrain.isShallowOcean()
                && cell.terrain != TerrainType.VOLCANO_PIPE && cell.riverMask >= .98F
                && cell.riverZone == RiverCarverSettings.RiverZone.None;
    }

    public record Formation(Kind kind, double x, double y, double z, double rx, double ry, double rz,
            double angle, long seed, BoundedCaveBiomeSource.Region region) {
        public double bound() { return Math.max(rx, rz) * 1.2 + 2; }

        public double density(int blockX, int blockY, int blockZ, ReferenceLandscapeProfiles.ShapePolicy policy) {
            return policy.organic() ? policy.density(kind, blockX - x, blockY - y, blockZ - z,
                    rx, ry, rz, angle, seed) : density(blockX, blockY, blockZ);
        }

        public double density(int blockX, int blockY, int blockZ) {
            double dx = blockX - x, dz = blockZ - z, dy = blockY - y;
            if (Math.abs(dx) > bound() || Math.abs(dz) > bound() || Math.abs(dy) > ry * 1.25 + 4) return 64;
            double u = dx * Math.cos(angle) + dz * Math.sin(angle);
            double v = -dx * Math.sin(angle) + dz * Math.cos(angle);
            double phase = unit(seed + 17) * Math.PI * 2;
            double rough = .07 * Math.sin(u * .24 + phase) * Math.cos(v * .31 - phase)
                    + .035 * Math.sin(dy * .43 + u * .12);
            double q;
            if (kind == Kind.SPIRE) {
                double r = Math.sqrt(u * u + v * v);
                double outer = (r - rx * .62) / (rx * .34);
                q = outer * outer + Math.pow(dy / ry, 4) + rough;
            } else {
                double skew = kind == Kind.OVERHANG ? .28 * u : 0;
                q = u * u / (rx * rx) + v * v / (rz * rz) + (dy + skew) * (dy + skew) / (ry * ry) + rough;
                if (kind == Kind.CAVERN) {
                    // Scalloped floors/ceilings and occasional irregular roof supports are retained host rock.
                    q += .08 * Math.cos(u * .22 + phase) * Math.sin(v * .19 + phase);
                    double pillarX = rx * .23 * Math.sin(phase), pillarZ = rz * .2 * Math.cos(phase);
                    double pillar = Math.hypot(u - pillarX, v - pillarZ);
                    if (unit(seed + 21) < .55 && pillar < 2.2 + .6 * Math.sin(dy * .3 + phase)) return 64;
                }
            }
            return q < 1 ? Math.max(-1, (q - 1) * .8) : 64;
        }
    }

    private static final class Cache {
        final Map<Long, Formation> surface = boundedMap();
        final Map<Long, Formation> caverns = boundedMap();
        boolean lastSurfaceValid, lastCavernValid;
        long lastSurfaceKey, lastCavernKey;
        Formation lastSurface, lastCavern;
        final Map<Long, Boolean> rawDry = boundedMap();
        final Map<Long, Boolean> accurateDry = boundedMap();
        final Map<Long, Boolean> rawDistricts = boundedMap();
        final Map<Long, Boolean> accurateDistricts = boundedMap();
        Tile.Chunk reader;
        int x, z;
        boolean accurate;
        Cell column;

        Formation rememberSurface(long key, Formation shape) {
            lastSurfaceKey = key;
            lastSurface = shape;
            lastSurfaceValid = true;
            return shape;
        }

        Formation rememberCavern(long key, Formation shape) {
            lastCavernKey = key;
            lastCavern = shape;
            lastCavernValid = true;
            return shape;
        }
    }

    private static <V> Map<Long, V> boundedMap() {
        return new LinkedHashMap<>(32, .75F, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
                return size() > ReferenceLandscapeProfiles.CACHE_SIZE;
            }
        };
    }

    private static double unit(long value) { return (mix(value) >>> 11) * 0x1.0p-53; }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}

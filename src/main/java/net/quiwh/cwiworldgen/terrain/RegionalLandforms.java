package net.quiwh.cwiworldgen.terrain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;

/** Regional terrain policy; never samples its own output or writes Minecraft chunks. */
public final class RegionalLandforms {
    @FunctionalInterface public interface Sampler { void sampleInto(Cell destination, int x, int z); }
    public enum Kind { MOUNTAIN, COAST }
    public record Basin(long id, Kind kind, double centerX, double centerZ, double radiusX, double radiusZ,
            double floorY, double spillY, double outletX, double outletZ) {}
    private static final int MOUNTAIN_GRID = 640, COAST_GRID = 512;
    private final long seed;
    private final boolean naturalGeometry;
    private final boolean waterLandscape;
    private final double scale, sea;
    private final Sampler base;
    private final Map<Long, FutureTask<List<Basin>>> mountains = bounded(256);
    private final Map<Long, FutureTask<List<Basin>>> coasts = bounded(256);
    private final Map<Long, FutureTask<List<Basin>>> neighborhoods = bounded(1024);
    private final ThreadLocal<Set<FutureTask<List<Basin>>>> constructing = ThreadLocal.withInitial(HashSet::new);
    private volatile long cacheEpoch;

    public RegionalLandforms(long seed, Levels levels, Sampler base) {
        this(seed, levels, base, 1);
    }

    public RegionalLandforms(long seed, Levels levels, Sampler base, int generationVersion) {
        this.seed = seed;
        this.naturalGeometry = generationVersion >= 8;
        this.waterLandscape = generationVersion >= 10;
        this.scale = levels.worldHeight;
        this.sea = levels.scale(levels.water);
        this.base = Objects.requireNonNull(base);
    }

    /** Only height changes here; the shared watershed decides whether a basin actually contains water. */
    public void apply(Cell cell, int x, int z) {
        if (!Float.isFinite(cell.height) || cell.terrain == null
                || cell.terrain.isVolcano() || cell.terrain.overridesCoast()) return;
        double height = cell.height * scale;
        if (waterLandscape) {
            boolean ocean = cell.terrain.isDeepOcean() || cell.terrain.isShallowOcean();
            height += ocean ? WaterLandscapeRelief.ocean(seed, x, z, height, sea)
                    : WaterLandscapeRelief.land(seed, x, z, height, sea);
            // Native deep-ocean columns receive relief but never basin descriptors.
            height = Math.clamp(height, -52, 373);
            cell.height = (float) (height / scale);
        }
        if (cell.terrain.isDeepOcean()) return;
        for (Basin basin : basinsNear(x, z)) {
            if (basin.kind == Kind.MOUNTAIN) height = mountain(height, basin, x, z);
            else height = coast(height, basin, x, z);
        }
        cell.height = (float) (height / scale);
    }

    /** Detached immutable candidates; neither this query nor descriptor construction samples carved terrain. */
    public List<Basin> basinsNear(int x, int z) {
        int qx = Math.floorDiv(x, 128), qz = Math.floorDiv(z, 128);
        long key = key(qx, qz);
        long epoch = cacheEpoch;
        return cached(neighborhoods, key, epoch, () -> {
            var result = new ArrayList<Basin>(18);
            collect(result, qx * 128, qz * 128, MOUNTAIN_GRID, Kind.MOUNTAIN, mountains, epoch);
            collect(result, qx * 128, qz * 128, COAST_GRID, Kind.COAST, coasts, epoch);
            return List.copyOf(result);
        });
    }

    /** Shared normalized contour for terrain and lake masks; the basin center is always zero. */
    public double basinRadius(Basin basin, int x, int z) { return basinRadius(basin, (double) x, (double) z); }

    public double basinRadius(Basin basin, double x, double z) {
        double dx = x - basin.centerX, dz = z - basin.centerZ;
        if (!naturalGeometry) return Math.hypot(dx / basin.radiusX, dz / basin.radiusZ);
        double rotation = unit(basin.id + 71) * Math.PI * 2;
        double cosine = Math.cos(rotation), sine = Math.sin(rotation);
        double u = (dx * cosine + dz * sine) / basin.radiusX;
        double v = (-dx * sine + dz * cosine) / basin.radiusZ;
        // Subtract the field at the center so metadata and the carved floor stay coincident.
        double wu = u + .12 * (field(basin.id + 72, u * .73, v * .73) - field(basin.id + 72, 0, 0));
        double wv = v + .10 * (field(basin.id + 73, u * .81, v * .81) - field(basin.id + 73, 0, 0));
        if (waterLandscape) {
            double irregular = 1 + .18 * (field(basin.id + 101, u * 2.2 + .37, v * 2.2 - .41)
                    - field(basin.id + 101, .37, -.41))
                    + .07 * field(basin.id + 102, u * 4.7, v * 4.7);
            return Math.hypot(wu, wv) / Math.clamp(irregular, .70, 1.30);
        }
        double angle = Math.atan2(wv, wu);
        double lobes = 1 + .09 * Math.sin(angle * 3 + unit(basin.id + 74) * 6.283185307179586)
                + .04 * Math.cos(angle * 5 + unit(basin.id + 75) * 6.283185307179586)
                + .05 * field(basin.id + 76, u * .9, v * .9);
        return Math.hypot(wu, wv) / lobes;
    }

    /** Supported still-water bed for accepted lakes; shared contour, independently weathered floor. */
    public double basinBed(Basin basin, double x, double z) {
        double r = basinRadius(basin, x, z);
        if (waterLandscape) {
            double u = (x - basin.centerX) / basin.radiusX, v = (z - basin.centerZ) / basin.radiusZ;
            return basin.spillY - WaterLandscapeRelief.basinDepth(basin.id, u, v, r, basin.spillY - basin.floorY);
        }
        if (!naturalGeometry) return basin.floorY + (basin.spillY - 2 - basin.floorY) * smooth(.25, 1, r * r);
        double dx = (x - basin.centerX) / basin.radiusX, dz = (z - basin.centerZ) / basin.radiusZ;
        double weathering = field(basin.id + 91, dx * 1.45, dz * 1.45);
        double bedding = field(basin.id + 92, dx * .95, dz * .95);
        double relief = (1.25 * weathering + .65 * bedding) * smooth(0, .42, r) * (1 - smooth(.65, 1, r));
        double floor = basin.floorY + relief;
        return floor + (basin.spillY - 2 - floor) * smooth(.18 + .05 * weathering, 1 + .035 * bedding, r);
    }

    /** Lifecycle cleanup; descriptors are recomputable and never escape as mutable values. */
    public void close() {
        synchronized (neighborhoods) {
            cacheEpoch++;
            neighborhoods.clear();
        }
        synchronized (mountains) { mountains.clear(); }
        synchronized (coasts) { coasts.clear(); }
    }

    private void collect(List<Basin> output, int x, int z, int size, Kind kind,
            Map<Long, FutureTask<List<Basin>>> cache, long epoch) {
        int cx = Math.floorDiv(x, size), cz = Math.floorDiv(z, size);
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            int gridX = cx + dx, gridZ = cz + dz;
            output.addAll(cached(cache, key(gridX, gridZ), epoch, () -> {
                Basin basin = descriptor(gridX, gridZ, size, kind);
                return basin == null ? List.of() : List.of(basin);
            }));
        }
    }

    private List<Basin> cached(Map<Long, FutureTask<List<Basin>>> cache, long key, long epoch,
            Supplier<List<Basin>> build) {
        FutureTask<List<Basin>> task;
        synchronized (cache) {
            // A pre-clear neighborhood may finish, but cannot repopulate descriptor caches.
            task = epoch == cacheEpoch ? cache.get(key) : null;
            if (task == null) {
                task = new FutureTask<>(build::get);
                if (epoch == cacheEpoch) cache.put(key, task);
            }
        }
        // FutureTask shares cold work at a key without holding any cache monitor while sampling.
        if (!task.isDone()) {
            Set<FutureTask<List<Basin>>> active = constructing.get();
            if (!active.add(task)) throw new IllegalStateException("Recursive regional descriptor construction");
            try { task.run(); }
            finally {
                active.remove(task);
                if (active.isEmpty()) constructing.remove();
            }
        }
        try { return task.get(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for regional descriptors", interrupted);
        } catch (ExecutionException failure) {
            synchronized (cache) { cache.remove(key, task); }
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Could not construct regional descriptors", failure.getCause());
        }
    }

    private Basin descriptor(int gridX, int gridZ, int grid, Kind kind) {
        long salt = mix(seed ^ mix(gridX * 0x632BE59BD9B4E019L)
                ^ mix(gridZ * 0x9E3779B97F4A7C15L) ^ (kind == Kind.MOUNTAIN ? 0x434952515545L : 0x4C41474F4F4EL));
        if (unit(salt) > (kind == Kind.MOUNTAIN ? .52 : .46)) return null;
        for (int attempt = 0; attempt < 4; attempt++) {
            int x = gridX * grid + (int) (grid * (.18 + .64 * unit(salt + 11 + attempt * 2)));
            int z = gridZ * grid + (int) (grid * (.18 + .64 * unit(salt + 12 + attempt * 2)));
            if (naturalGeometry) {
                x += (int) Math.round(grid * .12 * field(seed ^ 0x5C8071L, gridX * .57, gridZ * .57));
                z += (int) Math.round(grid * .12 * field(seed ^ 0x8A3459L, gridX * .57, gridZ * .57));
            }
            Cell center = raw(x, z);
            if (!Float.isFinite(center.height) || center.terrain == null || center.terrain.isVolcano()) continue;
            double y = center.height * scale;
            if (kind == Kind.MOUNTAIN) {
                if (!center.terrain.isMountain() || center.continentEdge < .7 || y < sea + 55 || y > 325) continue;
                double rx = 76 + 39 * unit(salt + 31), rz = rx * (.74 + .23 * unit(salt + 32));
                double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
                double outletX = x, outletZ = z;
                int directions = naturalGeometry ? 16 : 8;
                var contour = new Basin(salt, kind, x, z, rx, rz, 0, 0, x, z);
                for (int i = 0; i < directions; i++) {
                    double angle = naturalGeometry ? (i + unit(salt + 77)) * Math.PI * 2 / directions : i * Math.PI / 4;
                    double distance = naturalGeometry ? contourDistance(contour, angle, 1.6) : 0;
                    int ox = (int) Math.round(x + Math.cos(angle) * (naturalGeometry ? distance : rx * 1.6));
                    int oz = (int) Math.round(z + Math.sin(angle) * (naturalGeometry ? distance : rz * 1.6));
                    Cell around = raw(ox, oz);
                    double ay = around.height * scale;
                    if (!Float.isFinite(around.height) || around.terrain.isSubmerged()) { low = Double.NEGATIVE_INFINITY; break; }
                    high = Math.max(high, ay);
                    if (ay < low) { low = ay; outletX = ox; outletZ = oz; }
                }
                if (high - low < 14 || low < y - 31 || low > y - 5) continue;
                double spill = Math.max(sea + 37, Math.min(y - 5, low + 2));
                double floor = waterLandscape ? spill - 10 - 5 * unit(salt + 33) : spill - 6 - 2 * unit(salt + 33);
                if (y - floor < 8 || y - floor > 36) continue;
                return new Basin(salt, kind, x, z, rx, rz, floor, spill, outletX, outletZ);
            } else {
                if (y < sea + 2 || y > sea + 22 || center.continentEdge > .75 || center.terrain.isMountain()) continue;
                double rx = 105 + 48 * unit(salt + 31), rz = 68 + 33 * unit(salt + 32);
                double low = sea - 3;
                double outletX = Double.NaN, outletZ = Double.NaN;
                boolean cliff = false;
                int directions = naturalGeometry ? 16 : 8;
                var contour = new Basin(salt, kind, x, z, rx, rz, 0, 0, x, z);
                for (int i = 0; i < directions; i++) {
                    double angle = naturalGeometry ? (i + unit(salt + 77)) * Math.PI * 2 / directions : i * Math.PI / 4;
                    double distance = naturalGeometry ? contourDistance(contour, angle, 1.9) : rx * 1.9;
                    int ox = (int) Math.round(x + Math.cos(angle) * distance);
                    int oz = (int) Math.round(z + Math.sin(angle) * distance);
                    Cell around = raw(ox, oz);
                    double ay = around.height * scale;
                    cliff |= ay > sea + 55;
                    if (Float.isFinite(around.height) && around.terrain.isShallowOcean() && ay < low) {
                        low = ay; outletX = ox; outletZ = oz;
                    }
                }
                if (!Double.isFinite(outletX) || cliff) continue;
                return new Basin(salt, kind, x, z, rx, rz, waterLandscape ? sea - 9 - 5 * unit(salt + 33) : sea - 5 - 3 * unit(salt + 33), sea, outletX, outletZ);
            }
        }
        return null;
    }

    private double mountain(double original, Basin basin, int x, int z) {
        if (naturalGeometry) return naturalMountain(original, basin, x, z);
        double dx = x - basin.centerX, dz = z - basin.centerZ;
        double r = Math.hypot(dx / basin.radiusX, dz / basin.radiusZ);
        if (r > 2.1) return original;
        double[] mouth = mouth(basin, x, z);
        double saddle = (1 - smooth(.12, .28, mouth[1] / basin.radiusZ))
                * smooth(.3, .7, mouth[0]);
        // The retaining crest varies coherently along the rim and opens at the downhill saddle.
        double ridge = smooth(.95, 1.15, r) * (1 - smooth(1.34, 1.78, r));
        double irregularity = .65 + .35 * Math.sin(dx / 39 + unit(basin.id + 41) * 6.28)
                * Math.cos(dz / 53 + unit(basin.id + 42) * 6.28);
        double addition = (4 + 6 * unit(basin.id + 43)) * ridge * irregularity * (1 - saddle);
        double height = original + Math.min(Math.max(0, 373 - original), addition);
        double bowl = basin.floorY + (basin.spillY - basin.floorY + 7) * r * r;
        height = lower(height, bowl, 1 - smooth(.83, 1.16, r));
        if (mouth[0] >= -.08 && mouth[0] <= 1.12) {
            double channel = basin.floorY + (basin.spillY - basin.floorY) * smooth(.25, .73, mouth[0]);
            // Beyond the saddle, a lower base outlet stays lower; this never builds a dam.
            channel -= 2 * smooth(.80, 1.05, mouth[0]);
            double influence = (1 - smooth(.12, .25, mouth[1] / basin.radiusZ))
                    * smooth(-.08, .08, mouth[0]) * (1 - smooth(.92, 1.12, mouth[0]));
            height = lower(height, channel, influence);
        }
        return height;
    }

    private double coast(double original, Basin basin, int x, int z) {
        if (naturalGeometry) return naturalCoast(original, basin, x, z);
        double dx = x - basin.centerX, dz = z - basin.centerZ;
        if (Math.hypot(dx, dz) > basin.radiusX * 2.3) return original;
        double r = Math.hypot(dx / basin.radiusX, dz / basin.radiusZ);
        double height = original;
        if (r < 1.7) {
            double bowl = basin.floorY + (sea + 3 - basin.floorY) * Math.pow(r, 1.8);
            height = lower(height, bowl, 1 - smooth(.87, 1.25, r));
            double shore = sea + 1.5 + 4 * smooth(.72, 1.65, r);
            height = lower(height, shore, smooth(.70, .95, r) * (1 - smooth(1.25, 1.7, r)));
        }
        double[] mouth = mouth(basin, x, z);
        if (mouth[0] >= -.08 && mouth[0] <= 1.08) {
            double width = 14 + 8 * unit(basin.id + 44);
            double influence = (1 - smooth(.55, 1, mouth[1] / width))
                    * smooth(-.08, .08, mouth[0]) * (1 - smooth(.95, 1.08, mouth[0]));
            height = lower(height, basin.floorY + (sea - 3 - basin.floorY) * mouth[0], influence);
        }
        return height;
    }

    private double naturalMountain(double original, Basin basin, int x, int z) {
        double r = basinRadius(basin, x, z);
        if (r > 2.1) return original;
        double dx = (x - basin.centerX) / basin.radiusX, dz = (z - basin.centerZ) / basin.radiusZ;
        double weathering = field(basin.id + 81, dx * 1.3, dz * 1.3);
        double bedding = field(basin.id + 82, dx * .85, dz * .85);
        double[] mouth = mouth(basin, x, z);
        double width = basin.radiusZ * (.19 + .035 * field(basin.id + 83, dx, dz));
        double saddle = (1 - smooth(width * .55, width * 1.4, mouth[1])) * smooth(.26, .70, mouth[0]);
        double ridge = smooth(.94, 1.18 + .07 * bedding, r) * (1 - smooth(1.35, 1.78 + .10 * weathering, r));
        double addition = (4 + 6 * unit(basin.id + 43)) * ridge * (.70 + .30 * weathering) * (1 - saddle);
        double height = original + Math.min(Math.max(0, 373 - original), addition);
        double exponent = 2.05 + .46 * bedding;
        double floorRelief = 1.6 * weathering * smooth(0, .5, r) * (1 - smooth(.7, 1.2, r));
        double bowl = basin.floorY + (basin.spillY - basin.floorY + 7 + 2 * weathering) * Math.pow(r, exponent) + floorRelief;
        if (waterLandscape) {
            // The watershed fills the shared r<1 footprint down to basinBed.
            // Outside that footprint, keep the dry rim at the spill plane or
            // higher while returning to the retaining terrain.
            double bed = basinBed(basin, x, z);
            double target = r < 1 ? bed : Math.max(basin.spillY, bed);
            height = lower(height, target, 1 - smooth(1, 1.75, r));
        } else {
            height = lower(height, bowl, 1 - smooth(.80 + .035 * weathering, 1.17 + .04 * bedding, r));
        }
        if (mouth[0] >= -.08 && mouth[0] <= 1.12) {
            double channel = basin.floorY + (basin.spillY - basin.floorY) * smooth(.25, .73, mouth[0]);
            channel -= 2 * smooth(.80, 1.05, mouth[0]);
            double influence = (1 - smooth(width * .6, width * 1.35, mouth[1]))
                    * smooth(-.08, .08, mouth[0]) * (1 - smooth(.92, 1.12, mouth[0]));
            height = lower(height, channel, influence);
        }
        return height;
    }

    private double naturalCoast(double original, Basin basin, int x, int z) {
        double r = basinRadius(basin, x, z);
        double dx = (x - basin.centerX) / basin.radiusX, dz = (z - basin.centerZ) / basin.radiusZ;
        double weathering = field(basin.id + 85, dx * 1.2, dz * 1.2);
        double bedding = field(basin.id + 86, dx * .85, dz * .85);
        double height = original;
        if (r < 1.85) {
            double floorRelief = weathering * 1.1 * smooth(0, .5, r) * (1 - smooth(.65, 1.2, r));
            double bowl = basin.floorY + (sea + 3 - basin.floorY) * Math.pow(r, 1.85 + .38 * bedding) + floorRelief;
            if (waterLandscape) {
                // Match the accepted still-water mask through its outer contour;
                // exterior shelves remain at the spill plane or above.
                double bed = basinBed(basin, x, z);
                double target = r < 1 ? bed : Math.max(basin.spillY, bed);
                height = lower(height, target, 1 - smooth(1, 1.75, r));
            } else {
                height = lower(height, bowl, 1 - smooth(.84 + .035 * weathering, 1.25 + .055 * bedding, r));
            }
            double shore = sea + 1.5 + (4 + 1.2 * weathering) * smooth(.72, 1.65 + .1 * bedding, r);
            height = lower(height, shore, smooth(.68 + .05 * bedding, .96, r)
                    * (1 - smooth(1.24 + .06 * weathering, 1.75 + .08 * bedding, r)));
        }
        double[] mouth = mouth(basin, x, z);
        if (mouth[0] >= -.08 && mouth[0] <= 1.08) {
            double width = (14 + 8 * unit(basin.id + 44)) * (1 + .15 * weathering);
            double influence = (1 - smooth(.55, 1, mouth[1] / width))
                    * smooth(-.08, .08, mouth[0]) * (1 - smooth(.95, 1.08, mouth[0]));
            height = lower(height, basin.floorY + (sea - 3 - basin.floorY) * mouth[0], influence);
        }
        return height;
    }

    private double contourDistance(Basin basin, double angle, double radius) {
        double low = 0, high = Math.max(basin.radiusX, basin.radiusZ) * 3.2;
        for (int i = 0; i < 16; i++) {
            double distance = (low + high) * .5;
            double r = basinRadius(basin, basin.centerX + Math.cos(angle) * distance, basin.centerZ + Math.sin(angle) * distance);
            if (r < radius) low = distance; else high = distance;
        }
        return (low + high) * .5;
    }

    private static double field(long salt, double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double tx = fade(x - ix), tz = fade(z - iz);
        double lower = unit(salt ^ mix(key(ix, iz))) * (1 - tx) + unit(salt ^ mix(key(ix + 1, iz))) * tx;
        double upper = unit(salt ^ mix(key(ix, iz + 1))) * (1 - tx) + unit(salt ^ mix(key(ix + 1, iz + 1))) * tx;
        return (lower * (1 - tz) + upper * tz) * 2 - 1;
    }
    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    /** Longitudinal fraction and world-space distance from the basin's open outlet line. */
    private static double[] mouth(Basin basin, int x, int z) {
        double vx = basin.outletX - basin.centerX, vz = basin.outletZ - basin.centerZ;
        double length2 = vx * vx + vz * vz;
        double dx = x - basin.centerX, dz = z - basin.centerZ;
        double t = (dx * vx + dz * vz) / length2;
        return new double[] {t, Math.abs(dx * vz - dz * vx) / Math.sqrt(length2)};
    }

    private Cell raw(int x, int z) { var cell = new Cell(); base.sampleInto(cell, x, z); return cell; }
    private static double lower(double height, double cap, double amount) { return height + (Math.min(height, cap) - height) * amount; }
    private static double smooth(double a, double b, double value) {
        double t = Math.max(0, Math.min(1, (value - a) / (b - a))); return t * t * (3 - 2 * t);
    }
    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }
    private static double unit(long value) { return (mix(value) >>> 11) * 0x1.0p-53; }
    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }
    private static <V> Map<Long, V> bounded(int maximum) {
        return new LinkedHashMap<>(32, .75F, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, V> entry) { return size() > maximum; }
        };
    }
}

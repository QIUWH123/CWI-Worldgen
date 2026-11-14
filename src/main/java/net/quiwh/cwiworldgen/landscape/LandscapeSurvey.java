package net.quiwh.cwiworldgen.landscape;

import java.util.EnumSet;
import java.util.function.BooleanSupplier;
import net.quiwh.cwiworldgen.terrain.CliffBands;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscape;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverColumn;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;

/** Worker-only queries of immutable seed-owned terrain; no Minecraft chunks are loaded. */
public final class LandscapeSurvey {
    private final ReferenceTerrain terrain;
    private final BooleanSupplier cancelled;
    private final int sea, height;

    public LandscapeSurvey(ReferenceTerrain terrain, BooleanSupplier cancelled) {
        this.terrain = terrain;
        this.cancelled = cancelled;
        this.sea = terrain.levels().waterLevel;
        this.height = terrain.levels().worldHeight;
    }

    /** Directed candidate search; discovery does not alter terrain or load Minecraft chunks. */
    public LandscapeSearch.Result search(LandscapeType type, int originX, int originZ, int radius,
            int maxSamples, long budgetNanos, BooleanSupplier cancelled, java.util.function.IntConsumer progress) {
        return new LandscapeCandidateSearch(this, terrain, originX, originZ, radius, maxSamples,
                budgetNanos, cancelled, progress).run(type);
    }

    boolean cancelled() { return cancelled.getAsBoolean(); }

    LandscapeSearch.Candidate confirmAt(LandscapeType type, int x, int z) {
        if (cancelled.getAsBoolean()) return null;
        if (type == LandscapeType.ARCH || type == LandscapeType.OVERHANG
                || type == LandscapeType.SPIRE || type == LandscapeType.CAVERN) return formation(type, x, z);
        boolean identityQuery = switch (type) {
            case BADLANDS, PLATEAU, HILLS, MOUNTAINS, MOUNTAIN_RANGE, VOLCANO -> true;
            default -> false;
        };
        if (identityQuery) {
            Cell base = new Cell();
            terrain.sampleBaseInto(base, x, z);
            if (cancelled.getAsBoolean() || !baseIdentityMayMatch(type, base)) return null;
        }
        boolean waterQuery = switch (type) {
            case RIVER, RIVER_VALLEY, GORGE, LAKE, POND, LAGOON, WATERFALL, RAPIDS -> true;
            default -> false;
        };
        if (terrain.generationVersion() >= 2 && waterQuery) {
            Cell raw = terrain.sampleRaw(x, z);
            if (cancelled.getAsBoolean() || !waterEnvelope(type, raw)) return null;
        }
        // Base height/wetness cannot establish the erosion-filtered result. Identity-positive
        // land and metadata-positive water candidates still need completed confirmation.
        return confirm(type, x, z, terrain.sample(x, z));
    }

    /** Later climate, water and filter passes can remove these identities, never create them. */
    private static boolean baseIdentityMayMatch(LandscapeType type, Cell base) {
        if (base.terrain == null) return true;
        var identity = base.terrain;
        String name = identity.getName();
        // Height, water and gradients can change after the base pass. Only identity excludes a tile.
        return switch (type) {
            case BADLANDS -> name.contains("badlands");
            case PLATEAU -> !name.contains("badlands") && name.contains("plateau");
            case HILLS -> !name.contains("badlands") && !name.contains("plateau") && name.contains("hills");
            case MOUNTAINS -> !identity.isVolcano() && (identity.isMountain() || name.contains("mountain"));
            case MOUNTAIN_RANGE -> !identity.isVolcano()
                    && (identity.isMountain() || name.contains("mountain")) && name.contains("mountain_chain");
            case VOLCANO -> identity.isVolcano();
            default -> true;
        };
    }

    /** Necessary metadata only: erosion can turn a raw dry planned column into final water. */
    static boolean waterEnvelope(LandscapeType type, Cell raw) {
        if (!raw.cwiPlannedWater && ReferenceRiverColumn.isWaterCell(raw)) return true;
        return switch (type) {
            case RIVER, WATERFALL, RAPIDS -> raw.cwiPlannedWater && !raw.cwiBasin && !raw.cwiPond
                    && Math.abs(raw.cwiChannelDistance) <= raw.cwiChannelWidth;
            case LAKE, LAGOON -> raw.cwiPlannedWater && raw.cwiBasin && !raw.cwiPond;
            case POND -> raw.cwiPond;
            case RIVER_VALLEY -> !raw.cwiBasin && raw.cwiChannelWidth > 0;
            case GORGE -> raw.cwiGorge;
            default -> true;
        };
    }

    public EnumSet<LandscapeType> here(int x, int y, int z) {
        Cell cell = terrain.sample(x, z);
        var result = LandscapeType.classify(cell, sea, height);
        for (var type : new LandscapeType[]{LandscapeType.CLIFF, LandscapeType.WATERFALL,
                LandscapeType.RAPIDS, LandscapeType.ROCK_SHELF, LandscapeType.LAGOON}) {
            if (cancelled.getAsBoolean()) break;
            if (confirm(type, x, z, cell) != null) result.add(type);
        }
        if (!cancelled.getAsBoolean() && terrain.landscape() != null
                && terrain.landscape().density(x, y, z, true) < 0) {
            var shape = terrain.landscape().surfaceFormationAt(x, z);
            var cavern = terrain.landscape().cavernFormationAt(x, z);
            if (shape != null && shape.density(x, y, z,
                    net.quiwh.cwiworldgen.terrain.ReferenceLandscapeProfiles.select(terrain.generationVersion())) < 0)
                result.add(LandscapeType.valueOf(shape.kind().name()));
            if (cavern != null && cavern.density(x, y, z,
                    net.quiwh.cwiworldgen.terrain.ReferenceLandscapeProfiles.select(terrain.generationVersion())) < 0)
                result.add(LandscapeType.CAVERN);
        }
        return result;
    }

    public LandscapeSearch.Candidate find(LandscapeType type, int x, int z) {
        if (cancelled.getAsBoolean()) return null;
        if (type == LandscapeType.ARCH || type == LandscapeType.OVERHANG
                || type == LandscapeType.SPIRE || type == LandscapeType.CAVERN) return formation(type, x, z);
        Cell raw = terrain.sampleRaw(x, z);
        boolean nominate = switch (type) {
            case CLIFF, ROCK_SHELF -> raw.height * height > sea + 8;
            case WATERFALL, RAPIDS -> LandscapeType.classify(raw, sea, height).contains(LandscapeType.RIVER);
            case LAGOON -> raw.cwiBasin && !raw.cwiPond;
            default -> LandscapeType.classify(raw, sea, height).contains(type);
        };
        if (!nominate || cancelled.getAsBoolean()) return null;
        Cell accurate = terrain.sample(x, z);
        if (type == LandscapeType.WATERFALL || type == LandscapeType.RAPIDS) {
            var direct = confirm(type, x, z, accurate);
            if (direct != null) return direct;
            var direction = terrain.queryHydrology(x, z);
            if (!direction.validDirection()) return null;
            int previousX = x, previousZ = z;
            // Transition lips are often one column wide; a coarse locate grid must not skip them.
            for (int distance = 1; distance <= 24; distance++) for (int sign : new int[]{-1, 1}) {
                if (cancelled.getAsBoolean()) return null;
                int sx = x + (int) Math.round(sign * distance * direction.tangentX());
                int sz = z + (int) Math.round(sign * distance * direction.tangentZ());
                if (sx == previousX && sz == previousZ) continue;
                previousX = sx; previousZ = sz;
                if (Math.abs((long) sx) > LandscapeSearch.MAX_COORDINATE
                        || Math.abs((long) sz) > LandscapeSearch.MAX_COORDINATE) continue;
                var nearby = confirm(type, sx, sz, terrain.sample(sx, sz));
                if (nearby != null) return nearby;
            }
            return null;
        }
        return confirm(type, x, z, accurate);
    }

    private LandscapeSearch.Candidate confirm(LandscapeType type, int x, int z, Cell cell) {
        if (cancelled.getAsBoolean()) return null;
        int top = (int) Math.floor(cell.height * height);
        var basic = LandscapeType.classify(cell, sea, height);
        boolean present = switch (type) {
            case CLIFF -> CliffBands.exposureAt(x, z,
                    (sx, sz) -> cancelled.getAsBoolean() ? top
                            : (int) Math.floor(terrain.sample(sx, sz).height * height)).eligible();
            case ROCK_SHELF -> cell.cwiRockShelf != null && cell.cwiRockShelf.retainedAt(cell.height)
                    && cell.cwiRockShelf.geometryCut() > .25 && cell.height * height > sea;
            case LAGOON -> basic.contains(LandscapeType.LAKE) && lagoon(x, z);
            case WATERFALL, RAPIDS -> transition(type, x, z, basic);
            default -> basic.contains(type);
        };
        if (!present || cancelled.getAsBoolean()) return null;
        int water = ReferenceRiverColumn.isWaterCell(cell)
                ? Math.max(sea - 1, ReferenceRiverColumn.waterTableY(cell, terrain.levels())) : sea - 1;
        return new LandscapeSearch.Candidate(x, Math.max(top, water) + 1, z);
    }

    private boolean lagoon(int x, int z) {
        if (cancelled.getAsBoolean()) return false;
        var snapshot = terrain.landformWaterSnapshot(x, z);
        return snapshot != null && !cancelled.getAsBoolean()
                && snapshot.lagoonReaches().contains(terrain.watershedSample(x, z).reachId());
    }

    private boolean transition(LandscapeType type, int x, int z, EnumSet<LandscapeType> basic) {
        if (!basic.contains(LandscapeType.RIVER) || cancelled.getAsBoolean()) return false;
        var column = ReferenceRiverColumn.sample(terrain, x, z);
        return column.hasWater() && column.transition() == (type == LandscapeType.WATERFALL
                ? ReferenceRiverColumn.Transition.DROP : ReferenceRiverColumn.Transition.STEP);
    }

    private LandscapeSearch.Candidate formation(LandscapeType type, int x, int z) {
        if (cancelled.getAsBoolean()) return null;
        var landscape = terrain.landscape();
        if (landscape == null) return null;
        ReferenceLandscape.Formation shape = type == LandscapeType.CAVERN
                ? landscape.cavernFormationAt(x, z) : landscape.surfaceFormationAt(x, z);
        if (shape == null || !shape.kind().name().equals(type.name())) return null;
        return formation(type, shape);
    }

    LandscapeSearch.Candidate formation(LandscapeType type, ReferenceLandscape.Formation shape) {
        if (shape == null || !shape.kind().name().equals(type.name())) return null;
        var landscape = terrain.landscape();
        if (landscape == null) return null;
        // Verify an actual accepted cut below the completed surface, never just its descriptor.
        for (int dz = -1; dz <= 1; dz++) for (int dx = -2; dx <= 2; dx++) {
            if (cancelled.getAsBoolean()) return null;
            double u = dx * shape.rx() * .3, v = dz * shape.rz() * .4;
            int sx = (int) Math.round(shape.x() + u * Math.cos(shape.angle()) - v * Math.sin(shape.angle()));
            int sz = (int) Math.round(shape.z() + u * Math.sin(shape.angle()) + v * Math.cos(shape.angle()));
            int sy = (int) Math.round(shape.y());
            if (terrain.sample(sx, sz).height * height > sy + 2 && !cancelled.getAsBoolean()
                    && shape.density(sx, sy, sz,
                            net.quiwh.cwiworldgen.terrain.ReferenceLandscapeProfiles.select(terrain.generationVersion())) < 0
                    && !cancelled.getAsBoolean() && landscape.density(sx, sy, sz, true) < 0
                    && !cancelled.getAsBoolean())
                return new LandscapeSearch.Candidate(sx, sy, sz);
        }
        return null;
    }
}

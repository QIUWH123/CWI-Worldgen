package net.quiwh.cwiworldgen.terrain;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.hydrology.watershed.EnclosedPondPlanner;
import net.quiwh.cwiworldgen.hydrology.watershed.WatershedSample;
import net.quiwh.cwiworldgen.terrain.reference.GeneratorContext;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.Terrain;

/** Profile-15 real-block controls and bounded completed-terrain reproducibility checks. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeMountainRidgeTest {
    private static final int PROFILE = 15;
    // Different low 32 bits respect the imported engine's documented seed truncation.
    private static final long[] SEEDS = {42L, -987654321L};
    // Four completed tiles per seed, straddling both signs of the 128-block tile boundary.
    private static final List<Point> POINTS = List.of(
            new Point(-129, -129), new Point(-128, -128), new Point(-127, -127),
            new Point(127, 127), new Point(128, 128), new Point(129, 129));
    private static final List<Field> CELL_FIELDS = Arrays.stream(Cell.class.getFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .sorted(Comparator.comparing(Field::getName)).toList();

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void mountainRidgesRetainFullHeightBlockParityAndCarvedRiverWater(GameTestHelper helper) {
        RiverMountainTestSupport.verifyRiverAndMountain(helper, "mountain_ridges_wasteland_test", PROFILE);
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 12000)
    public static void mountainRidgesRemainSeedOwnedAcrossBordersOrderConcurrencyAndEviction(GameTestHelper helper) {
        var expected = new LinkedHashMap<Long, Map<Point, Snapshot>>();
        for (long seed : SEEDS) try (var terrain = new ReferenceTerrain(seed, PROFILE)) {
            expected.put(seed, capture(terrain, POINTS));
            var point = POINTS.get(0);
            Cell detached = terrain.sample(point.x, point.z);
            detached.reset();
            detached.height = -999F;
            equal(expected.get(seed).get(point), snapshot(terrain, point), "detached mutation", point);
        }
        check(!expected.get(SEEDS[0]).equals(expected.get(SEEDS[1])),
                "Distinct profile-15 seeds produced identical completed-terrain fixtures");
        var reversed = new ArrayList<>(POINTS);
        Collections.reverse(reversed);
        try (var first = new ReferenceTerrain(SEEDS[0], PROFILE);
             var second = new ReferenceTerrain(SEEDS[1], PROFILE)) {
            for (Point point : reversed) {
                equal(expected.get(SEEDS[1]).get(point), snapshot(second, point), "reverse second seed", point);
                equal(expected.get(SEEDS[0]).get(point), snapshot(first, point), "reverse first seed", point);
            }
            check(first.surfaceHabitat(127, 127) != second.surfaceHabitat(127, 127),
                    "Profile-15 habitat object leaked between seed owners");
            for (var terrain : List.of(first, second)) {
                var point = POINTS.get(0);
                var pinned = terrain.prepareChunk(point.x >> 4, point.z >> 4);
                var pinnedBefore = cellFields(pinned.getCell(point.x, point.z));
                evict(terrain);
                check(expected.get((long) terrain.seed()).equals(capture(terrain, POINTS)),
                        "Profile-15 terrain, ponds or water protection changed after cache eviction");
                check(pinnedBefore.equals(cellFields(pinned.getCell(point.x, point.z))),
                        "Eviction mutated a completed tile pinned by an existing chunk reader");
            }
        }

        var executor = Executors.newFixedThreadPool(2);
        try (var first = new ReferenceTerrain(SEEDS[0], PROFILE);
             var second = new ReferenceTerrain(SEEDS[1], PROFILE)) {
            var a = CompletableFuture.supplyAsync(() -> capture(first, POINTS), executor);
            var b = CompletableFuture.supplyAsync(() -> capture(second, reversed), executor);
            CompletableFuture.allOf(a, b).orTimeout(180, TimeUnit.SECONDS).join();
            check(expected.get(SEEDS[0]).equals(a.join()), "Concurrent first-seed mountain terrain changed");
            check(expected.get(SEEDS[1]).equals(b.join()), "Concurrent second-seed mountain terrain changed");
            // Swap owners between worker submissions to exercise seed-local thread caches.
            var swappedA = CompletableFuture.supplyAsync(() -> capture(second, POINTS), executor);
            var swappedB = CompletableFuture.supplyAsync(() -> capture(first, reversed), executor);
            CompletableFuture.allOf(swappedA, swappedB).orTimeout(180, TimeUnit.SECONDS).join();
            check(expected.get(SEEDS[1]).equals(swappedA.join()) && expected.get(SEEDS[0]).equals(swappedB.join()),
                    "Concurrent mountain queries contaminated another terrain seed owner");
        } finally {
            executor.shutdownNow();
        }
        System.out.println("CWI_MOUNTAIN_RIDGE_DETERMINISM=profile:15,seeds:2,points_per_seed:6,"
                + "positive_negative_borders:true,reversed:true,concurrent:true,eviction:true,pinned_readers:true");
        helper.succeed();
    }

    private static Map<Point, Snapshot> capture(ReferenceTerrain terrain, List<Point> points) {
        check(terrain.generationVersion() == PROFILE, "Mountain fixture bound the wrong saved generation profile");
        var result = new LinkedHashMap<Point, Snapshot>();
        for (Point point : points) result.put(point, snapshot(terrain, point));
        return Map.copyOf(result);
    }

    private static Snapshot snapshot(ReferenceTerrain terrain, Point point) {
        Cell cell = terrain.sample(point.x, point.z);
        check(Float.isFinite(cell.height) && Float.isFinite(cell.gradient),
                "Mountain fixture produced nonfinite completed terrain at " + point);
        var reader = terrain.prepareChunk(point.x >> 4, point.z >> 4);
        check(cellFields(cell).equals(cellFields(reader.getCell(point.x, point.z))),
                "Prepared chunk reader disagrees with detached completed terrain at " + point);
        var watershed = terrain.watershedSample(point.x, point.z);
        var pond = terrain.pondColumn(point.x, point.z);
        var habitat = terrain.surfaceHabitat(point.x, point.z);
        var protection = terrain.waterProtection().column(point.x, point.z);
        check(habitat.elevation() == cell.height * terrain.levels().worldHeight
                        && habitat.slope() == Math.clamp(cell.gradient, 0., 1.)
                        && habitat.waterProximity() == Math.clamp(cell.cwiPlannedWater ? 1 : 1 - cell.riverMask, 0., 1.),
                "Mountain habitat disagrees with completed terrain at " + point);
        // Changed mountain geography can move ponds; check every observed mask instead of requiring an old site.
        if (pond.protectedColumn()) {
            check(cell.cwiPondProtected && cell.cwiPond == pond.wet()
                            && terrain.levels().scale(cell.height) == pond.groundY()
                            && Float.floatToRawIntBits(cell.height) == Float.floatToRawIntBits(
                                    (float) (pond.originalGroundY() / terrain.levels().worldHeight)),
                    "Mountain terrain disagrees with the immutable protected pond floor at " + point);
            check(protection.protectedColumn() && protection.groundY() == pond.groundY()
                            && protection.waterY() == pond.waterY() && protection.floorY() == pond.protectionFloorY(),
                    "Mountain water-carving protection disagrees with the pond mask at " + point);
            if (pond.wet()) check(watershed.wet() && watershed.basin() && watershed.waterY() == pond.waterY(),
                    "Mountain pond disagrees with authoritative still-water hydrology at " + point);
        }
        return new Snapshot(cellFields(cell), watershed, pond, habitat, protection);
    }

    /** Test-only access avoids adding a production cache-reset API or generating nine extra tiles. */
    private static void evict(ReferenceTerrain terrain) {
        terrain.clearWatershedPlans();
        terrain.waterProtection().clear();
        try {
            Field context = ReferenceTerrain.class.getDeclaredField("context");
            context.setAccessible(true);
            ((GeneratorContext) context.get(terrain)).cache.clear();
            Field habitats = ReferenceTerrain.class.getDeclaredField("surfaceHabitats");
            habitats.setAccessible(true);
            var map = (Map<?, ?>) habitats.get(terrain);
            synchronized (map) { map.clear(); }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot evict profile-15 terrain caches in the native fixture", failure);
        }
        check(terrain.cachedWatershedPlans() == 0 && terrain.waterProtection().size() == 0
                        && terrain.cachedSurfaceHabitats() == 0,
                "Explicit mountain-fixture eviction retained a terrain-derived cache");
    }

    private static Map<String, Object> cellFields(Cell cell) {
        var result = new LinkedHashMap<String, Object>();
        for (Field field : CELL_FIELDS) try {
            Object value = field.get(cell);
            if (value instanceof Float number) value = Float.floatToRawIntBits(number);
            else if (value instanceof Double number) value = Double.doubleToRawLongBits(number);
            else if (value instanceof Terrain terrain) value = terrain.getName();
            result.put(field.getName(), value);
        } catch (IllegalAccessException failure) {
            throw new AssertionError("Cannot snapshot Cell field " + field.getName(), failure);
        }
        return Collections.unmodifiableMap(result);
    }

    private static void equal(Snapshot expected, Snapshot actual, String phase, Point point) {
        check(expected.equals(actual), "Profile-15 mountain terrain changed in " + phase + " at " + point);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Point(int x, int z) {}
    private record Snapshot(Map<String, Object> cell, WatershedSample watershed, EnclosedPondPlanner.Column pond,
                            SurfaceHabitat habitat, WaterProtectionColumn protection) {}
}

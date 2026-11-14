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
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.Terrain;

/** Bounded real-terrain queries; no coordinate discovery scan or Minecraft chunk generation. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeProfile12DeterminismTest {
    // Distinct low 32 bits intentionally avoid asserting away the supplied engine's known seed truncation.
    private static final long[] SEEDS = {42L, -987654321L};
    private static final List<Point> POINTS = List.of(
            new Point(-129, -129), new Point(-128, -128), new Point(-127, -127),
            new Point(-17, -17), new Point(-16, -16), new Point(-1, -1), new Point(0, 0),
            new Point(15, 15), new Point(16, 16), new Point(127, 127),
            new Point(128, 128), new Point(129, 129),
            new Point(-2607, -3029), new Point(-2608, -3029),
            new Point(-2607, -3030), new Point(-2606, -3029));
    private static final List<Field> CELL_FIELDS = Arrays.stream(Cell.class.getFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .sorted(Comparator.comparing(Field::getName)).toList();

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 12000)
    public static void seedOwnedTerrainCachesIgnoreQueryOrderConcurrencyAndExplicitEviction(GameTestHelper helper) {
        var expected = new LinkedHashMap<Long, Map<Point, Snapshot>>();
        for (long seed : SEEDS) try (var terrain = new ReferenceTerrain(seed, 12)) {
            expected.put(seed, capture(terrain, POINTS));
            Cell detached = terrain.sample(-2607, -3029);
            detached.reset(); detached.height = -999F;
            check(expected.get(seed).get(new Point(-2607, -3029)).equals(snapshot(terrain, new Point(-2607, -3029))),
                    "Mutating a detached Cell changed cached terrain for seed " + seed);
        }
        var pond = expected.get(42L).get(new Point(-2607, -3029));
        check(pond.pond.wet() && pond.pond.protectedColumn(), "Known seed42 pond fixture disappeared");
        check(!expected.get(SEEDS[0]).equals(expected.get(SEEDS[1])), "Distinct seed fixtures produced identical terrain");

        var reversed = new ArrayList<>(POINTS);
        Collections.reverse(reversed);
        try (var first = new ReferenceTerrain(SEEDS[0], 12); var second = new ReferenceTerrain(SEEDS[1], 12)) {
            // Both seed-owned caches start cold; alternate seeds while visiting coordinates backwards.
            for (Point point : reversed) {
                equal(expected.get(SEEDS[1]).get(point), snapshot(second, point), "reverse/interleaved second", point);
                equal(expected.get(SEEDS[0]).get(point), snapshot(first, point), "reverse/interleaved first", point);
            }
            check(first.surfaceHabitat(0, 0) != second.surfaceHabitat(0, 0), "Habitat object leaked between seed owners");
            check(first.cachedSurfaceHabitats() == POINTS.size() && second.cachedSurfaceHabitats() == POINTS.size(),
                    "Bounded habitat queries did not remain owned by their terrain instance");
            for (var terrain : List.of(first, second)) {
                terrain.clearWatershedPlans();
                terrain.waterProtection().clear();
                check(terrain.cachedWatershedPlans() == 0 && terrain.waterProtection().size() == 0,
                        "Explicit eviction did not clear accessible plan/protection caches");
                check(expected.get((long) terrain.seed()).equals(capture(terrain, POINTS)),
                        "Plan/pond/protection recomputation changed seeded terrain after eviction");
            }
        }

        // A dedicated bounded executor avoids sharing or shutting down Minecraft's worker pool.
        var executor = Executors.newFixedThreadPool(2);
        try (var first = new ReferenceTerrain(SEEDS[0], 12); var second = new ReferenceTerrain(SEEDS[1], 12)) {
            var a = CompletableFuture.supplyAsync(() -> capture(first, POINTS), executor);
            var b = CompletableFuture.supplyAsync(() -> capture(second, reversed), executor);
            CompletableFuture.allOf(a, b).orTimeout(180, TimeUnit.SECONDS).join();
            check(expected.get(SEEDS[0]).equals(a.join()), "Concurrent cold first-seed queries changed terrain");
            check(expected.get(SEEDS[1]).equals(b.join()), "Concurrent cold second-seed queries changed terrain");
            // Reverse each worker's seed owner on a second pass to catch thread-local state contamination.
            var swappedA = CompletableFuture.supplyAsync(() -> capture(second, POINTS), executor);
            var swappedB = CompletableFuture.supplyAsync(() -> capture(first, reversed), executor);
            CompletableFuture.allOf(swappedA, swappedB).orTimeout(180, TimeUnit.SECONDS).join();
            check(expected.get(SEEDS[1]).equals(swappedA.join()) && expected.get(SEEDS[0]).equals(swappedB.join()),
                    "Interleaved concurrent seed owners contaminated immutable terrain caches");
        } finally {
            executor.shutdownNow();
        }
        helper.succeed();
    }

    private static Map<Point, Snapshot> capture(ReferenceTerrain terrain, List<Point> points) {
        var result = new LinkedHashMap<Point, Snapshot>();
        for (Point point : points) result.put(point, snapshot(terrain, point));
        return Map.copyOf(result);
    }

    private static Snapshot snapshot(ReferenceTerrain terrain, Point point) {
        Cell cell = terrain.sample(point.x, point.z);
        var watershed = terrain.watershedSample(point.x, point.z);
        var pond = terrain.pondColumn(point.x, point.z);
        var habitat = terrain.surfaceHabitat(point.x, point.z);
        var protection = terrain.waterProtection().column(point.x, point.z);
        check(habitat.elevation() == cell.height * terrain.levels().worldHeight
                        && habitat.slope() == Math.clamp(cell.gradient, 0., 1.)
                        && habitat.waterProximity() == Math.clamp(cell.cwiPlannedWater ? 1 : 1 - cell.riverMask, 0., 1.),
                "Habitat disagrees with actual completed terrain at " + point);
        if (pond.protectedColumn()) {
            check(cell.cwiPondProtected && cell.cwiPond == pond.wet()
                            && terrain.levels().scale(cell.height) == pond.groundY()
                            && Float.floatToRawIntBits(cell.height) == Float.floatToRawIntBits(
                                    (float) (pond.originalGroundY() / terrain.levels().worldHeight)),
                    "Pond flags/floor disagree with actual completed terrain at " + point);
            check(protection.protectedColumn() && protection.groundY() == pond.groundY()
                            && protection.waterY() == pond.waterY() && protection.floorY() == pond.protectionFloorY(),
                    "Pond protection disagrees with immutable pond mask at " + point);
            if (pond.wet()) check(watershed.wet() && watershed.basin() && watershed.waterY() == pond.waterY(),
                    "Wet pond disagrees with canonical watershed sample at " + point);
        }
        return new Snapshot(cellFields(cell), watershed, pond, habitat, protection);
    }

    /** Exact primitive bits and stable terrain names include every public nonstatic Cell field. */
    private static Map<String, Object> cellFields(Cell cell) {
        var result = new LinkedHashMap<String, Object>();
        for (Field field : CELL_FIELDS) try {
            Object value = field.get(cell);
            if (value instanceof Float number) value = Float.floatToRawIntBits(number);
            else if (value instanceof Double number) value = Double.doubleToRawLongBits(number);
            else if (value instanceof Terrain terrain) value = terrain.getName();
            result.put(field.getName(), value);
        } catch (IllegalAccessException failure) { throw new AssertionError("Cannot snapshot Cell field " + field.getName(), failure); }
        return Collections.unmodifiableMap(result);
    }

    private static void equal(Snapshot expected, Snapshot actual, String phase, Point point) {
        check(expected.equals(actual), "Profile12 terrain changed in " + phase + " at " + point);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Point(int x, int z) {}
    private record Snapshot(Map<String, Object> cell, WatershedSample watershed, EnclosedPondPlanner.Column pond,
                            SurfaceHabitat habitat, WaterProtectionColumn protection) {}
}

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

/** Profile-16 block controls and bounded full-world-seed terrain/cache regression checks. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeNaturalnessProfileTest {
    private static final int PROFILE = 16;
    // Equal low 32 bits exercise the new profile's full-world-seed diffusion.
    private static final long[] WORLD_SEEDS = {42L, 42L + (1L << 32)};
    // Four completed tiles per seed, on both sides of positive and negative 128-block borders.
    private static final List<Point> POINTS = List.of(
            new Point(-129, -129), new Point(-128, -128), new Point(-127, -127),
            new Point(127, 127), new Point(128, 128), new Point(129, 129));
    private static final List<Field> CELL_FIELDS = Arrays.stream(Cell.class.getFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .sorted(Comparator.comparing(Field::getName)).toList();

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void naturalnessProfileRetainsFullHeightBlockParityAndCarvedRiverWater(GameTestHelper helper) {
        RiverMountainTestSupport.verifyRiverAndMountain(helper, "naturalness_wasteland_test", PROFILE);
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 12000)
    public static void highSeedBitsRemainOwnedAcrossBordersOrderConcurrencyAndEviction(GameTestHelper helper) {
        check((int) WORLD_SEEDS[0] == (int) WORLD_SEEDS[1], "High-bit seed fixture lost its shared low bits");
        check(WorldSeedPolicy.engineSeed(WORLD_SEEDS[0], PROFILE)
                        != WorldSeedPolicy.engineSeed(WORLD_SEEDS[1], PROFILE),
                "Profile-16 high-bit seed fixture collapsed to one imported-engine seed");
        // Expectations remain keyed by original world seeds, never the engine's mixed int seed.
        var expected = new LinkedHashMap<Long, Map<Point, Snapshot>>();
        for (long worldSeed : WORLD_SEEDS) try (var terrain = new ReferenceTerrain(worldSeed, PROFILE)) {
            expected.put(worldSeed, capture(terrain, worldSeed, POINTS));
            var point = POINTS.get(0);
            Cell detached = terrain.sample(point.x, point.z);
            detached.reset();
            detached.height = -999F;
            equal(expected.get(worldSeed).get(point), snapshot(terrain, point), "detached mutation", point);
        }
        check(POINTS.stream().anyMatch(point -> !expected.get(WORLD_SEEDS[0]).get(point).cell()
                        .equals(expected.get(WORLD_SEEDS[1]).get(point).cell())),
                "World seeds differing above bit31 produced identical completed Cell fixtures");

        var reversed = new ArrayList<>(POINTS);
        Collections.reverse(reversed);
        try (var first = new ReferenceTerrain(WORLD_SEEDS[0], PROFILE);
             var second = new ReferenceTerrain(WORLD_SEEDS[1], PROFILE)) {
            for (Point point : reversed) {
                equal(expected.get(WORLD_SEEDS[1]).get(point), snapshot(second, point), "reverse high-bit seed", point);
                equal(expected.get(WORLD_SEEDS[0]).get(point), snapshot(first, point), "reverse first seed", point);
            }
            check(first.surfaceHabitat(127, 127) != second.surfaceHabitat(127, 127),
                    "Profile-16 habitat object leaked between full-world-seed owners");
            check(first.cachedSurfaceHabitats() == POINTS.size() && second.cachedSurfaceHabitats() == POINTS.size(),
                    "Bounded naturalness-fixture habitats escaped their seed owners");
            evictAndCompare(first, WORLD_SEEDS[0], expected.get(WORLD_SEEDS[0]));
            evictAndCompare(second, WORLD_SEEDS[1], expected.get(WORLD_SEEDS[1]));
        }

        // Dedicated single-thread workers guarantee that each worker later changes seed owner.
        var firstWorker = Executors.newSingleThreadExecutor();
        var secondWorker = Executors.newSingleThreadExecutor();
        try (var first = new ReferenceTerrain(WORLD_SEEDS[0], PROFILE);
             var second = new ReferenceTerrain(WORLD_SEEDS[1], PROFILE)) {
            var a = CompletableFuture.supplyAsync(() -> capture(first, WORLD_SEEDS[0], POINTS), firstWorker);
            var b = CompletableFuture.supplyAsync(() -> capture(second, WORLD_SEEDS[1], reversed), secondWorker);
            CompletableFuture.allOf(a, b).orTimeout(180, TimeUnit.SECONDS).join();
            check(expected.get(WORLD_SEEDS[0]).equals(a.join()), "Concurrent first full-world-seed terrain changed");
            check(expected.get(WORLD_SEEDS[1]).equals(b.join()), "Concurrent high-bit full-world-seed terrain changed");
            var swappedA = CompletableFuture.supplyAsync(() -> capture(second, WORLD_SEEDS[1], POINTS), firstWorker);
            var swappedB = CompletableFuture.supplyAsync(() -> capture(first, WORLD_SEEDS[0], reversed), secondWorker);
            CompletableFuture.allOf(swappedA, swappedB).orTimeout(180, TimeUnit.SECONDS).join();
            check(expected.get(WORLD_SEEDS[1]).equals(swappedA.join())
                            && expected.get(WORLD_SEEDS[0]).equals(swappedB.join()),
                    "Worker seed-owner changes contaminated completed naturalness terrain");
        } finally {
            firstWorker.shutdownNow();
            secondWorker.shutdownNow();
        }
        System.out.println("CWI_NATURALNESS_PROFILE_DETERMINISM=profile:16,world_seeds:42/4294967338,points_per_seed:6,"
                + "high_seed_bits:true,positive_negative_borders:true,reversed:true,concurrent:true,"
                + "worker_owner_swap:true,eviction:true,pinned_readers:true");
        helper.succeed();
    }

    private static Map<Point, Snapshot> capture(ReferenceTerrain terrain, long worldSeed, List<Point> points) {
        check(terrain.generationVersion() == PROFILE, "Naturalness fixture bound the wrong saved profile");
        check(terrain.seed() == WorldSeedPolicy.engineSeed(worldSeed, PROFILE),
                "Naturalness fixture bound the wrong mixed engine seed for world seed " + worldSeed);
        var result = new LinkedHashMap<Point, Snapshot>();
        for (Point point : points) result.put(point, snapshot(terrain, point));
        return Map.copyOf(result);
    }

    private static Snapshot snapshot(ReferenceTerrain terrain, Point point) {
        Cell cell = terrain.sample(point.x, point.z);
        check(Float.isFinite(cell.height) && Float.isFinite(cell.gradient),
                "Naturalness fixture produced nonfinite completed terrain at " + point);
        var reader = terrain.prepareChunk(point.x >> 4, point.z >> 4);
        check(cellFields(cell).equals(cellFields(reader.getCell(point.x, point.z))),
                "Prepared chunk disagrees with detached completed naturalness terrain at " + point);
        var watershed = terrain.watershedSample(point.x, point.z);
        var pond = terrain.pondColumn(point.x, point.z);
        var habitat = terrain.surfaceHabitat(point.x, point.z);
        var protection = terrain.waterProtection().column(point.x, point.z);
        check(habitat.elevation() == cell.height * terrain.levels().worldHeight
                        && habitat.slope() == Math.clamp(cell.gradient, 0., 1.)
                        && habitat.waterProximity() == Math.clamp(cell.cwiPlannedWater ? 1 : 1 - cell.riverMask, 0., 1.),
                "Naturalness habitat disagrees with completed terrain at " + point);
        if (pond.protectedColumn()) {
            check(cell.cwiPondProtected && cell.cwiPond == pond.wet()
                            && terrain.levels().scale(cell.height) == pond.groundY()
                            && Float.floatToRawIntBits(cell.height) == Float.floatToRawIntBits(
                                    (float) (pond.originalGroundY() / terrain.levels().worldHeight)),
                    "Naturalness terrain disagrees with its immutable protected pond floor at " + point);
            check(protection.protectedColumn() && protection.groundY() == pond.groundY()
                            && protection.waterY() == pond.waterY() && protection.floorY() == pond.protectionFloorY(),
                    "Naturalness water-carving protection disagrees with its pond mask at " + point);
            if (pond.wet()) check(watershed.wet() && watershed.basin() && watershed.waterY() == pond.waterY(),
                    "Naturalness pond disagrees with authoritative still-water hydrology at " + point);
        }
        return new Snapshot(cellFields(cell), watershed, pond, habitat, protection);
    }

    private static void evictAndCompare(ReferenceTerrain terrain, long worldSeed, Map<Point, Snapshot> expected) {
        var point = POINTS.get(0);
        var pinned = terrain.prepareChunk(point.x >> 4, point.z >> 4);
        var pinnedBefore = cellFields(pinned.getCell(point.x, point.z));
        evict(terrain);
        check(pinnedBefore.equals(cellFields(pinned.getCell(point.x, point.z))),
                "Cache eviction immediately mutated a pinned completed naturalness tile");
        check(expected.equals(capture(terrain, worldSeed, POINTS)),
                "Naturalness terrain/ponds/water/habitats changed after cache eviction for world seed " + worldSeed);
        check(pinnedBefore.equals(cellFields(pinned.getCell(point.x, point.z))),
                "Recomputation mutated a completed naturalness tile pinned before eviction");
    }

    /** Clear test-owned caches directly instead of generating otherwise unnecessary eviction tiles. */
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
            throw new AssertionError("Cannot evict profile-16 terrain caches in the native fixture", failure);
        }
        check(terrain.cachedWatershedPlans() == 0 && terrain.waterProtection().size() == 0
                        && terrain.cachedSurfaceHabitats() == 0,
                "Explicit naturalness-fixture eviction retained a terrain-derived cache");
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
        } catch (IllegalAccessException failure) {
            throw new AssertionError("Cannot snapshot Cell field " + field.getName(), failure);
        }
        return Collections.unmodifiableMap(result);
    }

    private static void equal(Snapshot expected, Snapshot actual, String phase, Point point) {
        check(expected.equals(actual), "Profile-16 naturalness terrain changed in " + phase + " at " + point);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Point(int x, int z) {}
    private record Snapshot(Map<String, Object> cell, WatershedSample watershed, EnclosedPondPlanner.Column pond,
                            SurfaceHabitat habitat, WaterProtectionColumn protection) {}
}

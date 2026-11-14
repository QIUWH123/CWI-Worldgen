package net.quiwh.cwiworldgen.terrain;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
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
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource;
import net.quiwh.cwiworldgen.hydrology.watershed.EnclosedPondPlanner;
import net.quiwh.cwiworldgen.hydrology.watershed.WatershedSample;
import net.quiwh.cwiworldgen.terrain.reference.GeneratorContext;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.Terrain;

/** Profile-17 saved identities, real blocks and bounded completed-terrain reproducibility controls. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeBroadMountainTest {
    private static final int PROFILE = 17;
    private static final String DIMENSION = "broad_mountains_wasteland_test";
    // Equal low bits also protect the full-world-seed policy retained from profile 16.
    private static final long[] WORLD_SEEDS = {42L, 42L + (1L << 32)};
    // Four completed tiles per seed, across positive and negative 128-block borders.
    private static final List<Point> POINTS = List.of(
            new Point(-129, -129), new Point(-128, -128), new Point(-127, -127),
            new Point(127, 127), new Point(128, 128), new Point(129, 129));
    private static final List<Field> CELL_FIELDS = Arrays.stream(Cell.class.getFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .sorted(Comparator.comparing(Field::getName)).toList();

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void broadMountainsRetainSavedProfileAcrossEveryCodec(GameTestHelper helper) {
        var terrainCodec = ReferenceTerrainDensity.CODEC.codec();
        var marker = terrainCodec.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"field\":\"height\",\"generation_version\":17}")).getOrThrow();
        var restoredMarker = terrainCodec.parse(JsonOps.INSTANCE,
                terrainCodec.encodeStart(JsonOps.INSTANCE, marker).getOrThrow()).getOrThrow();
        check(restoredMarker.generationVersion() == PROFILE,
                "Broad-mountain terrain marker lost its saved profile");
        check(terrainCodec.parse(JsonOps.INSTANCE, JsonParser.parseString(
                        "{\"field\":\"height\",\"generation_version\":19}")).error().isPresent(),
                "Terrain marker accepted an unsupported future generation profile");

        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, DIMENSION)));
        check(level != null, "Missing profile-17 broad-mountain test dimension");
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        check(TerrainGenerationProfile.fromRouter(generator.generatorSettings().value().noiseRouter()) == PROFILE,
                "Broad-mountain test dimension bound another terrain profile");
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        var caveCodec = BoundedCaveBiomeSource.CODEC.codec();
        var caves = (BoundedCaveBiomeSource) generator.getBiomeSource();
        var restoredCaves = caveCodec.parse(ops, caveCodec.encodeStart(ops, caves).getOrThrow()).getOrThrow();
        check(restoredCaves.generationVersion() == PROFILE,
                "Broad-mountain cave marker lost its saved profile");
        var landscapeCodec = ReferenceLandscapeDensity.CODEC.codec();
        var restoredLandscape = landscapeCodec.parse(ops, landscapeCodec.encodeStart(ops,
                new ReferenceLandscapeDensity(caves, PROFILE)).getOrThrow()).getOrThrow();
        check(restoredLandscape.generationVersion() == PROFILE
                        && restoredLandscape.caves().generationVersion() == PROFILE,
                "Broad-mountain landscape lost its coherent cave/terrain identity");
        var saved = ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow().getAsJsonObject();
        check(saved.getAsJsonObject("biome_source").get("generation_version").getAsInt() == PROFILE,
                "Broad-mountain generator did not save its profile inline");
        var restoredGenerator = (NoiseBasedChunkGenerator) ChunkGenerator.CODEC.parse(ops, saved).getOrThrow();
        check(((BoundedCaveBiomeSource) restoredGenerator.getBiomeSource()).generationVersion() == PROFILE
                        && TerrainGenerationProfile.fromRouter(
                                restoredGenerator.generatorSettings().value().noiseRouter()) == PROFILE,
                "Broad-mountain generator changed profile after save/reload");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void broadMountainsRetainFullHeightBlockParityAndCarvedRiverWater(GameTestHelper helper) {
        RiverMountainTestSupport.verifyRiverAndMountain(helper, DIMENSION, PROFILE);
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 12000)
    public static void broadMountainsRemainSeedOwnedAcrossBordersOrderConcurrencyAndEviction(GameTestHelper helper) {
        check((int) WORLD_SEEDS[0] == (int) WORLD_SEEDS[1], "High-bit seed fixture lost its shared low bits");
        check(WorldSeedPolicy.engineSeed(WORLD_SEEDS[0], PROFILE)
                        != WorldSeedPolicy.engineSeed(WORLD_SEEDS[1], PROFILE),
                "Broad-mountain high-bit seed fixture collapsed to one imported-engine seed");
        // The int engine seed is mixed; expectations stay keyed by the original world seed.
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
                "World seeds differing above bit31 produced identical broad-mountain Cell fixtures");
        var reversed = new ArrayList<>(POINTS);
        Collections.reverse(reversed);
        try (var first = new ReferenceTerrain(WORLD_SEEDS[0], PROFILE);
             var second = new ReferenceTerrain(WORLD_SEEDS[1], PROFILE)) {
            for (Point point : reversed) {
                equal(expected.get(WORLD_SEEDS[1]).get(point), snapshot(second, point), "reverse high-bit seed", point);
                equal(expected.get(WORLD_SEEDS[0]).get(point), snapshot(first, point), "reverse first seed", point);
            }
            check(first.surfaceHabitat(127, 127) != second.surfaceHabitat(127, 127),
                    "Broad-mountain habitat object leaked between full-world-seed owners");
            check(first.cachedSurfaceHabitats() == POINTS.size() && second.cachedSurfaceHabitats() == POINTS.size(),
                    "Bounded broad-mountain fixture habitats escaped their seed owners");
            evictAndCompare(first, WORLD_SEEDS[0], expected.get(WORLD_SEEDS[0]));
            evictAndCompare(second, WORLD_SEEDS[1], expected.get(WORLD_SEEDS[1]));
        }

        // Dedicated workers guarantee each worker subsequently changes its seed owner.
        var firstWorker = Executors.newSingleThreadExecutor();
        var secondWorker = Executors.newSingleThreadExecutor();
        try (var first = new ReferenceTerrain(WORLD_SEEDS[0], PROFILE);
             var second = new ReferenceTerrain(WORLD_SEEDS[1], PROFILE)) {
            var a = CompletableFuture.supplyAsync(() -> capture(first, WORLD_SEEDS[0], POINTS), firstWorker);
            var b = CompletableFuture.supplyAsync(() -> capture(second, WORLD_SEEDS[1], reversed), secondWorker);
            CompletableFuture.allOf(a, b).orTimeout(180, TimeUnit.SECONDS).join();
            check(expected.get(WORLD_SEEDS[0]).equals(a.join()), "Concurrent first-seed broad-mountain terrain changed");
            check(expected.get(WORLD_SEEDS[1]).equals(b.join()), "Concurrent high-bit broad-mountain terrain changed");
            var swappedA = CompletableFuture.supplyAsync(() -> capture(second, WORLD_SEEDS[1], POINTS), firstWorker);
            var swappedB = CompletableFuture.supplyAsync(() -> capture(first, WORLD_SEEDS[0], reversed), secondWorker);
            CompletableFuture.allOf(swappedA, swappedB).orTimeout(180, TimeUnit.SECONDS).join();
            check(expected.get(WORLD_SEEDS[1]).equals(swappedA.join())
                            && expected.get(WORLD_SEEDS[0]).equals(swappedB.join()),
                    "Worker seed-owner changes contaminated completed broad-mountain terrain");
        } finally {
            firstWorker.shutdownNow();
            secondWorker.shutdownNow();
        }
        System.out.println("CWI_BROAD_MOUNTAIN_DETERMINISM=profile:17,world_seeds:42/4294967338,points_per_seed:6,"
                + "high_seed_bits:true,positive_negative_borders:true,reversed:true,concurrent:true,"
                + "worker_owner_swap:true,eviction:true,pinned_readers:true");
        helper.succeed();
    }

    private static Map<Point, Snapshot> capture(ReferenceTerrain terrain, long worldSeed, List<Point> points) {
        check(terrain.generationVersion() == PROFILE, "Broad-mountain fixture bound the wrong saved profile");
        check(terrain.seed() == WorldSeedPolicy.engineSeed(worldSeed, PROFILE),
                "Broad-mountain fixture bound the wrong mixed engine seed for world seed " + worldSeed);
        var result = new LinkedHashMap<Point, Snapshot>();
        for (Point point : points) result.put(point, snapshot(terrain, point));
        return Map.copyOf(result);
    }

    private static Snapshot snapshot(ReferenceTerrain terrain, Point point) {
        Cell cell = terrain.sample(point.x, point.z);
        check(Float.isFinite(cell.height) && Float.isFinite(cell.gradient),
                "Broad-mountain fixture produced nonfinite completed terrain at " + point);
        var reader = terrain.prepareChunk(point.x >> 4, point.z >> 4);
        check(cellFields(cell).equals(cellFields(reader.getCell(point.x, point.z))),
                "Prepared chunk disagrees with detached completed broad-mountain terrain at " + point);
        var watershed = terrain.watershedSample(point.x, point.z);
        var pond = terrain.pondColumn(point.x, point.z);
        var habitat = terrain.surfaceHabitat(point.x, point.z);
        var protection = terrain.waterProtection().column(point.x, point.z);
        check(habitat.elevation() == cell.height * terrain.levels().worldHeight
                        && habitat.slope() == Math.clamp(cell.gradient, 0., 1.)
                        && habitat.waterProximity() == Math.clamp(cell.cwiPlannedWater ? 1 : 1 - cell.riverMask, 0., 1.),
                "Broad-mountain habitat disagrees with completed terrain at " + point);
        // Geography may relocate ponds; every observed protected mask must remain authoritative.
        if (pond.protectedColumn()) {
            check(cell.cwiPondProtected && cell.cwiPond == pond.wet()
                            && terrain.levels().scale(cell.height) == pond.groundY()
                            && Float.floatToRawIntBits(cell.height) == Float.floatToRawIntBits(
                                    (float) (pond.originalGroundY() / terrain.levels().worldHeight)),
                    "Broad-mountain terrain disagrees with its immutable protected pond floor at " + point);
            check(protection.protectedColumn() && protection.groundY() == pond.groundY()
                            && protection.waterY() == pond.waterY() && protection.floorY() == pond.protectionFloorY(),
                    "Broad-mountain water-carving protection disagrees with its pond mask at " + point);
            if (pond.wet()) check(watershed.wet() && watershed.basin() && watershed.waterY() == pond.waterY(),
                    "Broad-mountain pond disagrees with authoritative still-water hydrology at " + point);
        }
        return new Snapshot(cellFields(cell), watershed, pond, habitat, protection);
    }

    private static void evictAndCompare(ReferenceTerrain terrain, long worldSeed, Map<Point, Snapshot> expected) {
        var point = POINTS.get(0);
        var pinned = terrain.prepareChunk(point.x >> 4, point.z >> 4);
        var pinnedBefore = cellFields(pinned.getCell(point.x, point.z));
        evict(terrain);
        check(pinnedBefore.equals(cellFields(pinned.getCell(point.x, point.z))),
                "Cache eviction immediately mutated a pinned completed broad-mountain tile");
        check(expected.equals(capture(terrain, worldSeed, POINTS)),
                "Broad-mountain terrain/ponds/water/habitats changed after cache eviction for world seed " + worldSeed);
        check(pinnedBefore.equals(cellFields(pinned.getCell(point.x, point.z))),
                "Recomputation mutated a completed broad-mountain tile pinned before eviction");
    }

    /** Test-only access avoids a production cache-reset API and unrelated eviction tiles. */
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
            throw new AssertionError("Cannot evict profile-17 terrain caches in the native fixture", failure);
        }
        check(terrain.cachedWatershedPlans() == 0 && terrain.waterProtection().size() == 0
                        && terrain.cachedSurfaceHabitats() == 0,
                "Explicit broad-mountain fixture eviction retained a terrain-derived cache");
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
        check(expected.equals(actual), "Profile-17 broad-mountain terrain changed in " + phase + " at " + point);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Point(int x, int z) {}
    private record Snapshot(Map<String, Object> cell, WatershedSample watershed, EnclosedPondPlanner.Column pond,
                            SurfaceHabitat habitat, WaterProtectionColumn protection) {}
}

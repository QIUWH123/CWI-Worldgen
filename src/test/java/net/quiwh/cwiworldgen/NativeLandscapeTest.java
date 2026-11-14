package net.quiwh.cwiworldgen;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource;
import net.quiwh.cwiworldgen.terrain.ReferenceGenerationHeight;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscape;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscape.Formation;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscapeDensity;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscapeProfiles;
import net.quiwh.cwiworldgen.terrain.ReferenceLandscapeProfiles.Kind;
import net.quiwh.cwiworldgen.terrain.ReferenceTerrainDensity;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.RiverCarverSettings.RiverZone;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;

/** Positive geometry coverage and the same safety decisions consumed by the live noise router. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeLandscapeTest {
    private static final JsonObject EVIDENCE = new JsonObject();
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void liveLandscapeBindsAllSurfaceKindsAndVariableGeometry(GameTestHelper helper) {
        EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
        Set<Integer> sizes = new HashSet<>();
        int actualCuts = 0, descriptors = 0;
        ServerLevel level = level(helper);
        for (long seed : new long[] {0, 42, -7}) {
            try (World world = world(level, seed)) {
                AtomicInteger seeded = new AtomicInteger(), unbound = new AtomicInteger();
                world.state.router().finalDensity().mapAll(new DensityFunction.Visitor() {
                    @Override public DensityFunction apply(DensityFunction function) {
                        if (function instanceof ReferenceLandscapeDensity.Seeded) seeded.incrementAndGet();
                        if (function instanceof ReferenceLandscapeDensity) unbound.incrementAndGet();
                        return function;
                    }
                });
                helper.assertTrue(seeded.get() > 0 && unbound.get() == 0 && world.landscape() != null,
                        "Production router did not bind the landscape marker to its world");
                int before = kinds.size();
                for (int ring = 0; ring <= 40 && (kinds.size() < 3 || sizes.size() < 8 || actualCuts < 3); ring++) {
                    for (int cx = -ring; cx <= ring; cx++) for (int cz = -ring; cz <= ring; cz++) {
                        if (Math.max(Math.abs(cx), Math.abs(cz)) != ring) continue;
                        Formation shape = world.landscape().surfaceFormationAt(cx * surfaceCell(), cz * surfaceCell());
                        if (shape == null) continue;
                        descriptors++;
                        sizes.add((int) Math.round(shape.rx() * 10));
                        helper.assertTrue(shape.kind() != Kind.CAVERN && shape.region() == null,
                                "Surface descriptor points at a cave region");
                        // Every possible carve belongs to the descriptor's own cell, including at negative coordinates.
                        helper.assertTrue(shape.x() - shape.bound() >= cx * surfaceCell()
                                && shape.x() + shape.bound() < (cx + 1) * surfaceCell()
                                && shape.z() - shape.bound() >= cz * surfaceCell()
                                && shape.z() + shape.bound() < (cz + 1) * surfaceCell(),
                                "A surface shape crosses its analytic cell boundary");
                        helper.assertTrue(shape.equals(world.landscape().surfaceFormationAt(cx * surfaceCell() + surfaceCell() - 1,
                                cz * surfaceCell() + surfaceCell() - 1)), "Cell lookup changes a formation descriptor");
                        if (!kinds.contains(shape.kind())) {
                            int[] p = candidate(world, shape);
                            if (p == null) continue;
                            if (world.landscape().density(p[0], p[1], p[2], false) < 0
                                    && world.landscape().density(p[0], p[1], p[2], true) < 0
                                    && world.terrain.sample(p[0], p[2]).height * 256 > p[1] + 1) {
                                kinds.add(shape.kind()); actualCuts++;
                                helper.assertTrue(world.landscape().density(p[0], level.getMaxBuildHeight() - 1, p[2], true) >= 0,
                                        "Subtractive shape extends to the top of the world");
                                BoundingBox footing = new BoundingBox(p[0] - 2, p[1], p[2] - 2, p[0] + 2, p[1] + 4, p[2] + 2);
                                helper.assertTrue(world.landscape().intersectsSurfaceFormation(footing),
                                        "A real cut did not reserve its structure footprint");
                                helper.assertTrue(!world.landscape().intersectsSurfaceFormation(new BoundingBox(
                                        p[0] - 2, p[1] + 128, p[2] - 2, p[0] + 2, p[1] + 132, p[2] + 2)),
                                        "A distant vertical footprint was incorrectly rejected");
                                System.out.println("CWI_LANDSCAPE_SURFACE=seed=" + seed + " kind=" + shape.kind()
                                        + " x=" + p[0] + " y=" + p[1] + " z=" + p[2]
                                        + " radius=" + shape.rx() + "," + shape.ry() + "," + shape.rz());
                            }
                        }
                    }
                }
                if (kinds.size() == 3 && sizes.size() >= 8) break;
                System.out.println("CWI_LANDSCAPE_SCAN=seed=" + seed + " found=" + kinds + " newKinds=" + (kinds.size() - before));
            }
        }
        helper.assertTrue(kinds.containsAll(EnumSet.of(Kind.ARCH, Kind.OVERHANG, Kind.SPIRE)),
                "No live positive carve was found for every surface formation kind: " + kinds);
        helper.assertTrue(sizes.size() >= 8 && descriptors >= 8, "Landscape dimensions collapsed to fixed templates");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void landscapeUsesHighSeedBitsAndSurvivesCacheEviction(GameTestHelper helper) {
        ServerLevel level = level(helper);
        try (World low = world(level, 19); World high = world(level, 19 + (1L << 40))) {
            List<int[]> keys = new ArrayList<>();
            List<Formation> values = new ArrayList<>();
            int differences = 0;
            for (int cx = -16; cx <= 16; cx++) for (int cz = -16; cz <= 16; cz++) {
                int x = cx * surfaceCell(), z = cz * surfaceCell();
                Formation a = low.landscape().surfaceFormationAt(x, z);
                Formation b = high.landscape().surfaceFormationAt(x, z);
                if (!java.util.Objects.equals(a, b)) differences++;
                if (a != null) { keys.add(new int[] {x, z}); values.add(a); }
            }
            helper.assertTrue(differences > 3 && values.size() > 2, "Landscape ignores the upper 32 seed bits or has no coverage");
            Cell a = low.terrain.sampleRaw(123, -487), b = high.terrain.sampleRaw(123, -487);
            helper.assertTrue(a.height == b.height && a.terrain == b.terrain,
                    "Landscape seed support unexpectedly changed the imported terrain engine's low-32-bit semantics");
            for (int index = keys.size() - 1; index >= 0; index--) {
                int[] p = keys.get(index);
                helper.assertTrue(values.get(index).equals(low.landscape().surfaceFormationAt(p[0], p[1])),
                        "Negative coordinates or bounded cache eviction changed seeded geometry");
            }
            // The shape stays inside each cell, so neighboring cells share an untouched boundary.
            for (int x : new int[] {-surfaceCell() - 1, -surfaceCell(), -1, 0, surfaceCell() - 1, surfaceCell()})
                for (int y : new int[] {80, 128, 192}) {
                    double first = low.landscape().density(x, y, -1, false);
                    double second = low.landscape().density(x, y, -1, false);
                    helper.assertTrue(first == second && first == 64, "Analytic cell boundary was carved or depends on query order");
                    helper.assertTrue(low.landscape().density(x, y, 0, false) == 64,
                            "Neighboring analytic cell does not preserve the shared boundary");
                }
            System.out.println("CWI_LANDSCAPE_SEED=high_bit_differences=" + differences + " cached_shapes=" + values.size());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void rareCavernsStayInsideTheirActualDistrictAndRoof(GameTestHelper helper) {
        ServerLevel level = level(helper);
        int active = 0, candidates = 0, rejectedWet = 0;
        Set<String> biomes = new HashSet<>();
        for (long seed : new long[] {0, 42}) {
            try (World world = world(level, seed)) {
                for (int ring = 0; ring <= 20 && (active < 3 || rejectedWet < 2); ring++) {
                    for (int cx = -ring; cx <= ring; cx++) for (int cz = -ring; cz <= ring; cz++) {
                        if (Math.max(Math.abs(cx), Math.abs(cz)) != ring) continue;
                        Formation shape = world.landscape().cavernFormationAt(cx * world.caves.cellSize(), cz * world.caves.cellSize());
                        if (shape == null) continue;
                        candidates++;
                        helper.assertTrue(shape.kind() == Kind.CAVERN && shape.region() != null
                                && shape.y() - shape.ry() >= shape.region().minY() + 9
                                && shape.y() + shape.ry() <= shape.region().maxY() - 9,
                                "Cavern envelope escaped its actual cave-height profile");
                        int[] p = candidate(world, shape, point -> true);
                        if (p == null) continue;
                        Cell raw = world.terrain.sampleRaw(p[0], p[2]);
                        if (!ReferenceLandscape.dry(raw)) {
                            helper.assertTrue(world.landscape().density(p[0], p[1], p[2], false) >= 0,
                                    "Cavern carved wet/ocean/river terrain");
                            rejectedWet++;
                            continue;
                        }
                        if (active >= 3) continue;
                        int[] accepted = candidate(world, shape);
                        if (accepted == null) continue;
                        p = accepted;
                        active++;
                        biomes.add(shape.region().biome().getRegisteredName());
                        Cell actual = world.terrain.sample(p[0], p[2]);
                        helper.assertTrue(p[1] + ReferenceLandscapeProfiles.MIN_CAVERN_ROOF < actual.height * 256,
                                "Cavern breached its minimum finished-terrain roof");
                        int[] cut = p;
                        ReferenceTerrainDensity.withAccurateTerrain(() -> {
                            for (int dx : new int[] {-8, 8}) for (int dy : new int[] {-4, 4}) for (int dz : new int[] {-8, 8})
                                helper.assertTrue(shape.region().equals(world.caves.caveRegionAtBlock(cut[0] + dx, cut[1] + dy, cut[2] + dz,
                                        world.state.sampler())), "A positive cavern crossed its actual district/height boundary");
                            return null;
                        });
                        for (int y : new int[] {shape.region().minY(), shape.region().maxY(), world.caves.caveCeiling() + 1})
                            helper.assertTrue(world.landscape().density(p[0], y, p[2], true) >= 0,
                                    "Cavern cut outside its biome's Y limits");
                        System.out.println("CWI_LANDSCAPE_CAVERN=seed=" + seed + " biome=" + shape.region().biome().getRegisteredName()
                                + " x=" + p[0] + " y=" + p[1] + " z=" + p[2]
                                + " roof=" + (actual.height * 256 - p[1]) + " radius=" + shape.rx() + "," + shape.ry());
                    }
                }
            }
            if (active >= 3 && rejectedWet >= 2) break;
        }
        helper.assertTrue(active >= 3 && candidates > active && rejectedWet >= 2,
                "Missing positive rare caverns or real wet-site rejection: active=" + active + " candidates=" + candidates + " wet=" + rejectedWet);
        System.out.println("CWI_LANDSCAPE_CAVE_COVERAGE=active=" + active + " descriptors=" + candidates
                + " wet_rejected=" + rejectedWet + " biomes=" + biomes);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 12000)
    public static void generatedSurfaceCutsOpenToOutsideAirAndRetainTheirRoof(GameTestHelper helper) {
        ServerLevel level = level(helper);
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        var baseSettings = baselineSettings(settings);
        var baseline = new NoiseBasedChunkGenerator(generator.getBiomeSource(), Holder.direct(baseSettings));
        int checked = 0;
        for (long seed : new long[] {0, 42, -7}) {
            var baseState = RandomState.create(baseSettings, level.registryAccess().lookupOrThrow(Registries.NOISE), seed);
            try (World world = world(level, seed)) {
                for (int ring = 0; ring <= 40; ring++) {
                    for (int cx = -ring; cx <= ring; cx++) for (int cz = -ring; cz <= ring; cz++) {
                        if (Math.max(Math.abs(cx), Math.abs(cz)) != ring) continue;
                        Formation shape = world.landscape().surfaceFormationAt(cx * surfaceCell(), cz * surfaceCell());
                        if (shape == null || shape.kind() == Kind.SPIRE) continue;
                        checked++;
                        BlockPos opening = findOpening(level, generator, baseline, world, baseState, shape);
                        if (opening == null) continue;
                        ChunkPos owner = new ChunkPos(opening);
                        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
                        var changed = new ProtoChunk(owner, UpgradeData.EMPTY, level, biomes, null);
                        var unchanged = new ProtoChunk(owner, UpgradeData.EMPTY, level, biomes, null);
                        long fillNanos = fill(level, generator, world.state, changed);
                        fill(level, baseline, baseState, unchanged);
                        int solidToAir = 0;
                        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                            for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                                p.set(owner.getBlockX(x), y, owner.getBlockZ(z));
                                var old = unchanged.getBlockState(p);
                                var now = changed.getBlockState(p);
                                helper.assertTrue(!now.isSolid() || old.isSolid(), "Subtractive landscape added solid terrain");
                                if (old.isSolid() && now.isAir()) solidToAir++;
                            }
                        helper.assertTrue(solidToAir > 0 && unchanged.getBlockState(opening).isSolid()
                                && changed.getBlockState(opening).isAir(), "Positive descriptor did not carve actual generated solid blocks");
                        JsonObject evidence = compareOptimizedAndFull(helper, level, generator, settings, world, seed, changed);
                        evidence.addProperty("seed", seed);
                        evidence.addProperty("kind", shape.kind().name());
                        evidence.addProperty("x", opening.getX()); evidence.addProperty("y", opening.getY()); evidence.addProperty("z", opening.getZ());
                        evidence.addProperty("solid_to_air", solidToAir);
                        evidence.addProperty("retained_roof", true); evidence.addProperty("outside_air_connection", true);
                        evidence.addProperty("descriptors_checked", checked);
                        evidence.addProperty("optimized_fill_ms", fillNanos / 1_000_000.);
                        recordEvidence("surface", evidence);
                        System.out.println("CWI_LANDSCAPE_NATIVE_FILL=" + evidence);
                        helper.succeed();
                        return;
                    }
                }
            } finally {
                ((TerrainRandomState) (Object) baseState).cwiWorldgen$terrain().close();
            }
        }
        helper.fail("No actual surface-connected arch/overhang with retained roof in " + checked + " eligible descriptors");
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 12000)
    public static void generatedCavernCutsRealHostRockAndMatchesFullHeight(GameTestHelper helper) {
        ServerLevel level = level(helper);
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var settings = generator.generatorSettings().value();
        var baseSettings = baselineSettings(settings);
        var baseline = new NoiseBasedChunkGenerator(generator.getBiomeSource(), Holder.direct(baseSettings));
        int checked = 0;
        for (long seed : new long[] {0, 42}) {
            var baseState = RandomState.create(baseSettings, level.registryAccess().lookupOrThrow(Registries.NOISE), seed);
            try (World world = world(level, seed)) {
                for (int ring = 0; ring <= 20; ring++) {
                    for (int cx = -ring; cx <= ring; cx++) for (int cz = -ring; cz <= ring; cz++) {
                        if (Math.max(Math.abs(cx), Math.abs(cz)) != ring) continue;
                        Formation shape = world.landscape().cavernFormationAt(cx * world.caves.cellSize(), cz * world.caves.cellSize());
                        if (shape == null) continue;
                        checked++;
                        BlockPos cut = findSolidCavernCut(level, generator, baseline, world, baseState, shape);
                        if (cut == null) continue;
                        ChunkPos owner = new ChunkPos(cut);
                        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
                        var changed = new ProtoChunk(owner, UpgradeData.EMPTY, level, biomes, null);
                        var unchanged = new ProtoChunk(owner, UpgradeData.EMPTY, level, biomes, null);
                        long fillNanos = fill(level, generator, world.state, changed);
                        fill(level, baseline, baseState, unchanged);
                        int removed = 0, toAir = 0, toFluid = 0;
                        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
                        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                            for (int y = shape.region().minY(); y <= shape.region().maxY(); y++) {
                                p.set(owner.getBlockX(x), y, owner.getBlockZ(z));
                                var old = unchanged.getBlockState(p); var now = changed.getBlockState(p);
                                helper.assertTrue(!now.isSolid() || old.isSolid(), "Cavern added solid host terrain");
                                if (old.isSolid() && !now.isSolid()) {
                                    removed++;
                                    if (now.isAir()) toAir++;
                                    if (!now.getFluidState().isEmpty()) toFluid++;
                                    helper.assertTrue(world.landscape().density(p.getX(), y, p.getZ(), true) < 0,
                                            "Cavern removed solid rock outside its protected density field");
                                }
                            }
                        helper.assertTrue(removed > 0 && unchanged.getBlockState(cut).isSolid()
                                && !changed.getBlockState(cut).isSolid(), "Cavern descriptor did not remove any actual generated host rock");
                        JsonObject evidence = compareOptimizedAndFull(helper, level, generator, settings, world, seed, changed);
                        evidence.addProperty("seed", seed); evidence.addProperty("kind", "CAVERN");
                        helper.assertTrue(evidence.get("used_reduced_height").getAsBoolean(),
                                "Active cavern did not exercise reduced noise filling");
                        evidence.addProperty("biome", shape.region().biome().getRegisteredName());
                        evidence.addProperty("x", cut.getX()); evidence.addProperty("y", cut.getY()); evidence.addProperty("z", cut.getZ());
                        evidence.addProperty("host_rock_removed", removed); evidence.addProperty("solid_to_air", toAir);
                        evidence.addProperty("solid_to_fluid", toFluid); evidence.addProperty("descriptors_checked", checked);
                        evidence.addProperty("minimum_roof", ReferenceLandscapeProfiles.MIN_CAVERN_ROOF);
                        evidence.addProperty("optimized_fill_ms", fillNanos / 1_000_000.);
                        recordEvidence("cavern", evidence);
                        System.out.println("CWI_LANDSCAPE_CAVERN_NATIVE_FILL=" + evidence);
                        helper.succeed();
                        return;
                    }
                }
            } finally {
                ((TerrainRandomState) (Object) baseState).cwiWorldgen$terrain().close();
            }
        }
        helper.fail("No cavern removed actual generated host rock in " + checked + " descriptors");
    }

    private static BlockPos findSolidCavernCut(ServerLevel level, NoiseBasedChunkGenerator generator,
            NoiseBasedChunkGenerator baseline, World world, RandomState baseState, Formation shape) {
        int[] center = candidate(world, shape);
        if (center == null) return null;
        if (!ReferenceLandscape.dry(world.terrain.sampleRaw(center[0], center[2]))) return null;
        // A few columns cover different lobes and avoid mistaking an already empty vanilla cave for new generation.
        for (int i = -1; i < 5; i++) {
            double angle = shape.angle() + i * Math.PI * .4;
            int x = i < 0 ? center[0] : (int) Math.round(shape.x() + Math.cos(angle) * shape.rx() * .32);
            int z = i < 0 ? center[2] : (int) Math.round(shape.z() + Math.sin(angle) * shape.rz() * .32);
            int y = i < 0 ? center[1] : (int) Math.round(shape.y());
            if (world.landscape().density(x, y, z, false) >= 0 || world.landscape().density(x, y, z, true) >= 0) continue;
            NoiseColumn old = ReferenceTerrainDensity.withAccurateTerrain(() -> baseline.getBaseColumn(x, z, level, baseState));
            NoiseColumn now = ReferenceTerrainDensity.withAccurateTerrain(() -> generator.getBaseColumn(x, z, level, world.state));
            for (int dy = -3; dy <= 3; dy++) if (old.getBlock(y + dy).isSolid() && !now.getBlock(y + dy).isSolid())
                return new BlockPos(x, y + dy, z);
        }
        return null;
    }

    /** Compare all 114,688 noise blocks in a chunk known to contain a successful new formation. */
    private static JsonObject compareOptimizedAndFull(GameTestHelper helper, ServerLevel level,
            NoiseBasedChunkGenerator generator, NoiseGeneratorSettings settings, World world, long seed, ProtoChunk optimized) {
        helper.assertTrue(ReferenceGenerationHeight.supports(settings), "Production landscape graph lost height optimization support");
        var fullSettings = withFinal(settings, DensityFunctions.add(settings.noiseRouter().finalDensity(), DensityFunctions.zero()));
        helper.assertTrue(!ReferenceGenerationHeight.supports(fullSettings), "Full-height comparison did not bypass optimization");
        var fullGenerator = new NoiseBasedChunkGenerator(generator.getBiomeSource(), Holder.direct(fullSettings));
        var fullState = RandomState.create(fullSettings, level.registryAccess().lookupOrThrow(Registries.NOISE), seed);
        try {
            var full = new ProtoChunk(optimized.getPos(), UpgradeData.EMPTY, level,
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
            long fullNanos = fill(level, fullGenerator, fullState, full);
            int compared = 0;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    p.set(optimized.getPos().getBlockX(x), y, optimized.getPos().getBlockZ(z));
                    helper.assertTrue(optimized.getBlockState(p).equals(full.getBlockState(p)),
                            "Height optimization changed an active landscape block at " + p);
                    compared++;
                }
            int boundedHeight = ReferenceGenerationHeight.height(settings.noiseSettings(), settings, world.terrain,
                    optimized.getPos(), Blender.empty(), level.structureManager(), true);
            JsonObject evidence = new JsonObject();
            evidence.addProperty("chunk_x", optimized.getPos().x); evidence.addProperty("chunk_z", optimized.getPos().z);
            evidence.addProperty("compared_blocks", compared); evidence.addProperty("optimized_matches_full", true);
            evidence.addProperty("bounded_height", boundedHeight); evidence.addProperty("full_height", settings.noiseSettings().height());
            // A high mountain or a terrain-adapting structure can correctly
            // select the conservative full-range path even at a dry formation.
            evidence.addProperty("used_reduced_height", boundedHeight < settings.noiseSettings().height());
            evidence.addProperty("full_fill_ms", fullNanos / 1_000_000.);
            return evidence;
        } finally { ((TerrainRandomState) (Object) fullState).cwiWorldgen$terrain().close(); }
    }

    private static NoiseGeneratorSettings baselineSettings(NoiseGeneratorSettings settings) {
        return withFinal(settings, settings.noiseRouter().finalDensity().mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction function) {
                return function instanceof ReferenceLandscapeDensity ? DensityFunctions.constant(64) : function;
            }
        }));
    }

    private static NoiseGeneratorSettings withFinal(NoiseGeneratorSettings settings, DensityFunction density) {
        NoiseRouter r = settings.noiseRouter();
        var router = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(),
                r.lavaNoise(), r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
                r.initialDensityWithoutJaggedness(), density, r.veinToggle(), r.veinRidged(), r.veinGap());
        return new NoiseGeneratorSettings(settings.noiseSettings(), settings.defaultBlock(), settings.defaultFluid(), router,
                settings.surfaceRule(), settings.spawnTarget(), settings.seaLevel(), settings.disableMobGeneration(),
                settings.isAquifersEnabled(), settings.oreVeinsEnabled(), settings.useLegacyRandomSource());
    }

    private static synchronized void recordEvidence(String category, JsonObject value) {
        EVIDENCE.add(category, value);
        Path path = Path.of("../terrain-preview/landscape-native.json");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(EVIDENCE) + "\n");
        } catch (IOException failure) { throw new IllegalStateException("Cannot save native landscape evidence", failure); }
    }

    /** Native noise columns prove the opening reaches existing outside air, beyond merely testing the descriptor. */
    private static BlockPos findOpening(ServerLevel level, NoiseBasedChunkGenerator generator,
            NoiseBasedChunkGenerator baseline, World world, RandomState baseState, Formation shape) {
        int centerX = (int) Math.round(shape.x()), centerZ = (int) Math.round(shape.z());
        int roofY = (int) Math.ceil(shape.y() + shape.ry()) + 2;
        if (world.terrain.sample(centerX, centerZ).height * 256 < roofY + 2) return null;
        NoiseColumn oldCenter = ReferenceTerrainDensity.withAccurateTerrain(() -> baseline.getBaseColumn(centerX, centerZ, level, baseState));
        NoiseColumn newCenter = ReferenceTerrainDensity.withAccurateTerrain(() -> generator.getBaseColumn(centerX, centerZ, level, world.state));
        if (!oldCenter.getBlock(roofY).isSolid() || !newCenter.getBlock(roofY).isSolid()) return null;
        Map<Long, Integer> heights = new HashMap<>();
        var policy = ReferenceLandscapeProfiles.select(world.terrain.generationVersion());
        int bound = (int) Math.ceil(shape.bound());
        for (int dx = -bound; dx <= bound; dx += 2) for (int dz = -bound; dz <= bound; dz += 2) {
            int x = centerX + dx, z = centerZ + dz;
            int approximate = (int) Math.floor(world.terrain.sample(x, z).height * 256);
            if (shape.density(x, approximate, z, policy) >= 0 && shape.density(x, approximate - 3, z, policy) >= 0) continue;
            int y = nativeHeight(level, baseline, baseState, heights, x, z) - 1;
            if (shape.density(x, y, z, policy) >= 0 || world.landscape().density(x, y, z, true) >= 0) continue;
            boolean outside = false;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                int nx = x + direction.getStepX(), nz = z + direction.getStepZ();
                if (nativeHeight(level, baseline, baseState, heights, nx, nz) <= y) { outside = true; break; }
            }
            if (!outside) continue;
            NoiseColumn old = ReferenceTerrainDensity.withAccurateTerrain(() -> baseline.getBaseColumn(x, z, level, baseState));
            NoiseColumn now = ReferenceTerrainDensity.withAccurateTerrain(() -> generator.getBaseColumn(x, z, level, world.state));
            if (old.getBlock(y).isSolid() && now.getBlock(y).isAir()) return new BlockPos(x, y, z);
        }
        return null;
    }

    private static int nativeHeight(ServerLevel level, NoiseBasedChunkGenerator baseline, RandomState state,
            Map<Long, Integer> heights, int x, int z) {
        return heights.computeIfAbsent(ChunkPos.asLong(x, z), ignored -> ReferenceTerrainDensity.withAccurateTerrain(
                () -> baseline.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, state)));
    }

    private static long fill(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState state, ProtoChunk chunk) {
        long started = System.nanoTime();
        var result = generator.fillFromNoise(Blender.empty(), state, level.structureManager(), chunk);
        level.getServer().managedBlock(() -> { level.getChunkSource().pollTask(); return result.isDone(); });
        result.join();
        return System.nanoTime() - started;
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void landscapeHydrologyGateReservesBanksAndNonfiniteColumns(GameTestHelper helper) {
        Cell cell = new Cell(); cell.terrain = TerrainType.HILLS; cell.height = .6F;
        helper.assertTrue(ReferenceLandscape.dry(cell), "Natural dry hillside was rejected");
        for (var terrain : List.of(TerrainType.RIVER, TerrainType.WETLAND, TerrainType.DEEP_OCEAN,
                TerrainType.SHALLOW_OCEAN, TerrainType.VOLCANO_PIPE, TerrainType.NONE)) {
            cell.terrain = terrain;
            helper.assertTrue(!ReferenceLandscape.dry(cell), "Landscape accepted protected terrain " + terrain);
        }
        cell.terrain = TerrainType.HILLS;
        for (RiverZone zone : RiverZone.values()) if (zone != RiverZone.None) {
            cell.riverZone = zone;
            helper.assertTrue(!ReferenceLandscape.dry(cell), "Landscape entered river/bank/valley zone " + zone);
        }
        cell.riverZone = RiverZone.None; cell.riverMask = .979F;
        helper.assertTrue(!ReferenceLandscape.dry(cell), "River mask transition was not protected");
        cell.riverMask = 1; cell.height = Float.NaN;
        helper.assertTrue(!ReferenceLandscape.dry(cell), "NaN terrain was accepted");
        helper.succeed();
    }

    private static int[] candidate(World world, Formation shape) {
        return candidate(world, shape, point -> world.landscape().density(point[0], point[1], point[2], false) < 0
                && world.landscape().density(point[0], point[1], point[2], true) < 0
                && world.terrain.sample(point[0], point[2]).height * 256 > point[1] + 1);
    }

    private static int[] candidate(World world, Formation shape, Predicate<int[]> accepted) {
        var policy = ReferenceLandscapeProfiles.select(world.terrain.generationVersion());
        int centerX = (int) Math.round(shape.x()), centerY = (int) Math.round(shape.y());
        int centerZ = (int) Math.round(shape.z()), bound = (int) Math.ceil(shape.bound());
        // Saved profiles have different openings and retained supports. Search the actual
        // selected field, keeping every point inside the same finite descriptor envelope.
        for (int dy : new int[] {0, -3, 3, -6, 6}) {
            for (int ring = 0; ring <= bound; ring += 2) {
                for (int dx = -ring; dx <= ring; dx += 2) for (int dz = -ring; dz <= ring; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int[] point = {centerX + dx, centerY + dy, centerZ + dz};
                    if (shape.density(point[0], point[1], point[2], policy) < 0 && accepted.test(point)) return point;
                }
            }
        }
        return null;
    }
    private static int surfaceCell() { return ReferenceLandscapeProfiles.SURFACE_CELL; }
    private static ServerLevel level(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "wasteland_test")));
        helper.assertTrue(level != null, "Missing production wasteland test dimension");
        return level;
    }
    private static World world(ServerLevel level, long seed) {
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var state = RandomState.create(generator.generatorSettings().value(), level.registryAccess().lookupOrThrow(Registries.NOISE), seed);
        return new World(state, ((TerrainRandomState) (Object) state).cwiWorldgen$terrain(),
                (BoundedCaveBiomeSource) generator.getBiomeSource());
    }
    private record World(RandomState state, ReferenceTerrain terrain, BoundedCaveBiomeSource caves) implements AutoCloseable {
        ReferenceLandscape landscape() { return terrain.landscape(); }
        @Override public void close() { terrain.close(); }
    }
    private NativeLandscapeTest() {}
}

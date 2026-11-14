package net.quiwh.cwiworldgen.biome;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.terrain.ReferenceTerrainDensity;

/** The same source is executable against retained .5 and new .6 distribution JARs. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeClimatePerformanceTest {
    private static final List<BlockPos> ORIGINS = List.of(new BlockPos(-17, 80, 19),
            new BlockPos(1635, -17, -3885));

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 60000)
    public static void directOwnedSamplerMatchesVanillaForSurfaceAndCaveTargets(GameTestHelper helper) {
        ServerLevel level = level(helper);
        BoundedCaveBiomeSource source = source(helper, level);
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int chunks = level.getChunkSource().getLoadedChunksCount();
        long baselineCalls = 0, candidateCalls = 0, samples = 0;
        int cases = 0;
        for (BlockPos origin : ORIGINS) {
            for (Predicate<Holder<Biome>> target : targets(source)) {
                CountedSampler control = new CountedSampler(sampler);
                var expected = baseline(source).findClosestBiome3d(origin, 64, 32, 64,
                        target, control.sampler, level);
                // Keep real delegates visible; counting wrappers would disable the owned-field optimization.
                var query = query(source, sampler, level, origin, target);
                while (!query.step(3, Long.MAX_VALUE)) { }
                helper.assertTrue(Objects.equals(expected, query.result()),
                        "Direct owned sampler differs from vanilla at " + origin
                                + ": expected=" + expected + ",actual=" + query.result());
                if (query.result() != null) {
                    BlockPos found = query.result().getFirst();
                    helper.assertTrue(query.result().getSecond().equals(source.getNoiseBiome(
                            QuartPos.fromBlock(found.getX()), QuartPos.fromBlock(found.getY()),
                            QuartPos.fromBlock(found.getZ()), sampler)), "Returned biome failed full-source confirmation");
                }
                baselineCalls += control.calls();
                candidateCalls += query.densityEvaluations();
                samples += query.samples();
                cases++;
            }
        }
        helper.assertTrue(candidateCalls < baselineCalls, "Direct sampler failed to reduce vanilla climate work");
        helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == chunks,
                "Direct sampler locate loaded remote chunks");
        String delegates = Stream.of(sampler.temperature(), sampler.humidity(), sampler.continentalness(),
                sampler.erosion(), sampler.depth(), sampler.weirdness())
                .map(field -> field.getClass().getSimpleName()).reduce((a, b) -> a + "/" + b).orElse("");
        System.out.println("CWI_CLIMATE_DIRECT=cases:" + cases + ",profile:" + source.generationVersion()
                + ",vanilla_calls:" + baselineCalls + ",query_calls:" + candidateCalls
                + ",samples:" + samples + ",delegates:" + delegates + ",chunks_unchanged:true");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 60000)
    public static void repeatedDirectSearchesReportControlledWarmTimings(GameTestHelper helper) {
        ServerLevel level = level(helper);
        BoundedCaveBiomeSource source = source(helper, level);
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int chunks = level.getChunkSource().getLoadedChunksCount();
        List<Predicate<Holder<Biome>>> targets = targets(source);
        // Warm terrain and JIT using identical complete batches, never production worlds.
        for (int warmup = 0; warmup < 4; warmup++) batch(source, sampler, level, targets, warmup);
        long[] rounds = new long[9];
        long calls = 0, samples = 0, queries = 0, checksum = 0;
        for (int round = 0; round < rounds.length; round++) {
            long started = System.nanoTime();
            Batch batch = batch(source, sampler, level, targets, round);
            rounds[round] = System.nanoTime() - started;
            calls += batch.calls(); samples += batch.samples(); queries += batch.queries(); checksum += batch.checksum();
        }
        long[] ordered = rounds.clone();
        Arrays.sort(ordered);
        helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == chunks,
                "Warm locate benchmark loaded remote chunks");
        System.out.println("CWI_CLIMATE_TIMING=round_ns:" + Arrays.toString(rounds).replace(" ", "")
                + ",median_ns:" + ordered[ordered.length / 2] + ",min_ns:" + ordered[0]
                + ",max_ns:" + ordered[ordered.length - 1] + ",rounds:" + rounds.length
                + ",warmup_batches:4,queries:" + queries + ",query_calls:" + calls
                + ",samples:" + samples + ",checksum:" + checksum + ",chunks_unchanged:true");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 60000)
    public static void accurateTerrainModeRetainsNativeLocateResults(GameTestHelper helper) {
        ServerLevel level = level(helper);
        BoundedCaveBiomeSource source = source(helper, level);
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int chunks = level.getChunkSource().getLoadedChunksCount();
        int cases = 0;
        // Reuse the same source and sampler while the terrain mode changes between queries.
        for (boolean accurate : new boolean[]{false, true, false, true, false}) {
            for (Predicate<Holder<Biome>> target : targets(source)) {
                java.util.function.Supplier<Boolean> check = () -> {
                    var expected = baseline(source).findClosestBiome3d(ORIGINS.getFirst(), 0, 32, 64,
                            target, sampler, level);
                    var query = CwiBiomeSearch.create(source, ORIGINS.getFirst(), 0, 32, 64, target,
                            sampler, level.getMinBuildHeight(), level.getMaxBuildHeight(), () -> false);
                    while (!query.step(1, Long.MAX_VALUE)) { }
                    return Objects.equals(expected, query.result());
                };
                helper.assertTrue(accurate ? ReferenceTerrainDensity.withAccurateTerrain(check) : check.get(),
                        "Accurate sampling mode changed locate parity: " + accurate);
                cases++;
            }
        }
        helper.assertTrue(!ReferenceTerrainDensity.isAccurateSampling(), "Accurate mode leaked out of its scope");
        helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == chunks,
                "Accurate mode locate loaded remote chunks");
        System.out.println("CWI_CLIMATE_MODE=cases:" + cases
                + ",ordinary_accurate_transitions:true,scope_restored:true,chunks_unchanged:true");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 60000)
    public static void accurateModeSwitchesInsideOneQueryRetainNativeSampleOrder(GameTestHelper helper) {
        ServerLevel level = level(helper);
        BoundedCaveBiomeSource source = source(helper, level);
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int chunks = level.getChunkSource().getLoadedChunksCount();
        int cases = 0;
        // The mode changes while one query retains its memoized horizontal column.
        for (BlockPos origin : ORIGINS) {
            int[] heights = Mth.outFromOrigin(origin.getY(), level.getMinBuildHeight() + 1,
                    level.getMaxBuildHeight(), 64).toArray();
            for (Predicate<Holder<Biome>> target : targets(source)) {
                Pair<BlockPos, Holder<Biome>> expected = null;
                for (int index = 0; index < heights.length; index++) {
                    final int y = heights[index];
                    java.util.function.Supplier<Holder<Biome>> sample = () -> source.getNoiseBiome(
                            QuartPos.fromBlock(origin.getX()), QuartPos.fromBlock(y),
                            QuartPos.fromBlock(origin.getZ()), sampler);
                    Holder<Biome> biome = index % 2 == 1
                            ? ReferenceTerrainDensity.withAccurateTerrain(sample) : sample.get();
                    if (target.test(biome)) {
                        expected = Pair.of(new BlockPos(origin.getX(), y, origin.getZ()), biome);
                        break;
                    }
                }
                var query = CwiBiomeSearch.create(source, origin, 0, 32, 64, target,
                        sampler, level.getMinBuildHeight(), level.getMaxBuildHeight(), () -> false);
                int index = 0;
                while (!query.done()) {
                    java.util.function.Supplier<Boolean> advance = () -> query.step(1, Long.MAX_VALUE);
                    if (index++ % 2 == 1) ReferenceTerrainDensity.withAccurateTerrain(advance);
                    else advance.get();
                    helper.assertTrue(index <= heights.length + 1, "Alternating-mode query failed to finish");
                }
                helper.assertTrue(Objects.equals(expected, query.result()),
                        "Changing accurate mode within one query changed native result at " + origin
                                + ": expected=" + expected + ",actual=" + query.result());
                cases++;
            }
        }
        helper.assertTrue(!ReferenceTerrainDensity.isAccurateSampling(), "Accurate mode leaked out of its scope");
        helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == chunks,
                "Accurate mode locate loaded remote chunks");
        System.out.println("CWI_CLIMATE_MODE_SWITCH=cases:" + cases
                + ",ordinary_accurate_transitions:true,within_query:true,scope_restored:true,chunks_unchanged:true");
        helper.succeed();
    }

    private static Batch batch(BoundedCaveBiomeSource source, Climate.Sampler sampler, ServerLevel level,
            List<Predicate<Holder<Biome>>> targets, int order) {
        long calls = 0, samples = 0, queries = 0, checksum = 0;
        for (int repeat = 0; repeat < 2; repeat++) {
            for (int oi = 0; oi < ORIGINS.size(); oi++) {
                BlockPos origin = ORIGINS.get((oi + order) % ORIGINS.size());
                for (int ti = 0; ti < targets.size(); ti++) {
                    var query = query(source, sampler, level, origin, targets.get((ti + order) % targets.size()));
                    while (!query.step(256, Long.MAX_VALUE)) { }
                    calls += query.densityEvaluations(); samples += query.samples(); queries++;
                    if (query.result() != null) checksum += query.result().getFirst().asLong();
                }
            }
        }
        return new Batch(calls, samples, queries, checksum);
    }

    private static CwiBiomeSearch.Query query(BoundedCaveBiomeSource source, Climate.Sampler sampler,
            ServerLevel level, BlockPos origin, Predicate<Holder<Biome>> target) {
        return CwiBiomeSearch.create(source, origin, 64, 32, 64, target, sampler,
                level.getMinBuildHeight(), level.getMaxBuildHeight(), () -> false);
    }

    private static List<Predicate<Holder<Biome>>> targets(BoundedCaveBiomeSource source) {
        List<Predicate<Holder<Biome>>> result = new ArrayList<>();
        source.possibleBiomes().stream().sorted(Comparator.comparing(holder ->
                holder.unwrapKey().orElseThrow().location().toString())).forEach(biome -> result.add(biome::equals));
        result.add(holder -> true);
        result.add(holder -> false);
        result.add(holder -> holder.unwrapKey().orElseThrow().location().getPath().contains("caves"));
        return result;
    }

    private static ServerLevel level(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        helper.assertTrue(level != null && level.getSeed() == 0, "Missing disposable profile-21 seed-0 dimension");
        return level;
    }

    private static BoundedCaveBiomeSource source(GameTestHelper helper, ServerLevel level) {
        var source = level.getChunkSource().getGenerator().getBiomeSource();
        helper.assertTrue(source instanceof BoundedCaveBiomeSource, "Missing CWI biome wrapper");
        var result = (BoundedCaveBiomeSource) source;
        helper.assertTrue(result.generationVersion() == 21, "Unexpected generation profile");
        return result;
    }

    private static BiomeSource baseline(BoundedCaveBiomeSource source) {
        return new BiomeSource() {
            @Override protected MapCodec<? extends BiomeSource> codec() { return MapCodec.unit(this); }
            @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return source.possibleBiomes().stream(); }
            @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
                return source.getNoiseBiome(x, y, z, sampler);
            }
        };
    }

    private record Batch(long calls, long samples, long queries, long checksum) {}

    private static final class CountedSampler {
        private final CountingField[] fields;
        private final Climate.Sampler sampler;
        private CountedSampler(Climate.Sampler source) {
            fields = new CountingField[]{new CountingField(source.temperature()), new CountingField(source.humidity()),
                    new CountingField(source.continentalness()), new CountingField(source.erosion()),
                    new CountingField(source.depth()), new CountingField(source.weirdness())};
            sampler = new Climate.Sampler(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], source.spawnTarget());
        }
        private long calls() { return Arrays.stream(fields).mapToLong(field -> field.calls).sum(); }
    }

    private static final class CountingField implements DensityFunction.SimpleFunction {
        private final DensityFunction delegate;
        private long calls;
        private CountingField(DensityFunction delegate) { this.delegate = delegate; }
        @Override public double compute(FunctionContext context) { calls++; return delegate.compute(context); }
        @Override public double minValue() { return delegate.minValue(); }
        @Override public double maxValue() { return delegate.maxValue(); }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return delegate.codec(); }
    }

    private NativeClimatePerformanceTest() {}
}

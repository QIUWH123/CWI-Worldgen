package net.quiwh.cwiworldgen.biome;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;

/** Real seeded density functions, actual CWI source and exact distribution; no remote chunks. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeBiomeSearchTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 60000)
    public static void optimizedLocateMatchesVanillaSamplesForEveryBiome(GameTestHelper helper) {
        ServerLevel level = level(helper);
        var source = source(helper, level);
        var sampler = level.getChunkSource().randomState().sampler();
        var baseline = baseline(source);
        int chunks = level.getChunkSource().getLoadedChunksCount();
        List<Predicate<Holder<Biome>>> targets = new ArrayList<>();
        for (var biome : source.possibleBiomes()) targets.add(biome::equals);
        targets.add(holder -> true);
        targets.add(holder -> false);
        targets.add(holder -> holder.unwrapKey().orElseThrow().location().getPath().contains("caves"));
        int cases = 0;
        long baselineEvaluations = 0, optimizedEvaluations = 0;
        for (BlockPos origin : List.of(new BlockPos(-17, 80, 19), new BlockPos(1635, -17, -3885))) {
            for (var target : targets) {
                var control = new CountedSampler(sampler);
                var candidate = new CountedSampler(sampler);
                var expected = baseline.findClosestBiome3d(origin, 64, 32, 64, target, control.sampler(), level);
                var actual = source.findClosestBiome3d(origin, 64, 32, 64, target, candidate.sampler(), level);
                baselineEvaluations += control.evaluations();
                optimizedEvaluations += candidate.evaluations();
                helper.assertTrue(Objects.equals(expected, actual),
                        "Optimized native result differs from vanilla samples at " + origin
                                + ": expected=" + expected + ",actual=" + actual);
                if (actual != null) {
                    var p = actual.getFirst();
                    helper.assertTrue(actual.getSecond().equals(source.getNoiseBiome(
                            QuartPos.fromBlock(p.getX()), QuartPos.fromBlock(p.getY()), QuartPos.fromBlock(p.getZ()), sampler)),
                            "Result does not match the full native biome source");
                }
                cases++;
            }
        }
        helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == chunks,
                "Biome locate loaded distant Minecraft chunks");
        helper.assertTrue(optimizedEvaluations < baselineEvaluations,
                "CWI biome search failed to reduce actual seeded climate evaluations: "
                        + baselineEvaluations + "->" + optimizedEvaluations);
        System.out.println("CWI_BIOME_SEARCH_PARITY=cases:" + cases + ",chunks_unchanged:true,profile:" + source.generationVersion()
                + ",climate_evaluations:" + baselineEvaluations + "->" + optimizedEvaluations);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 100)
    public static void incrementalQueryUsesNativeSourceAndHonorsCancellation(GameTestHelper helper) {
        ServerLevel level = level(helper);
        var source = source(helper, level);
        var sampler = level.getChunkSource().randomState().sampler();
        BlockPos origin = new BlockPos(-17, 80, 19);
        int chunks = level.getChunkSource().getLoadedChunksCount();
        AtomicBoolean cancellation = new AtomicBoolean();
        var query = CwiBiomeSearch.create(source, origin, 6400, 32, 64,
                holder -> holder.unwrapKey().orElseThrow().location().getPath().equals("echo_caves"),
                sampler, level.getMinBuildHeight(), level.getMaxBuildHeight(), cancellation::get);
        helper.assertTrue(!query.step(1, Long.MAX_VALUE) && query.samples() == 1,
                "One native sample ignored its incremental budget");
        cancellation.set(true);
        helper.assertTrue(query.step(64, Long.MAX_VALUE) && query.cancelled() && query.result() == null,
                "Native query retained a result after cancellation");
        var expected = source.getNoiseBiome(QuartPos.fromBlock(origin.getX()), QuartPos.fromBlock(origin.getY()),
                QuartPos.fromBlock(origin.getZ()), sampler);
        var found = CwiBiomeSearch.create(source, origin, 6400, 32, 64, expected::equals,
                sampler, level.getMinBuildHeight(), level.getMaxBuildHeight(), () -> false);
        helper.assertTrue(found.step(1, Long.MAX_VALUE) && found.result() != null
                        && found.result().getFirst().equals(origin) && found.result().getSecond().equals(expected),
                "One-sample native result differs from the complete source");
        helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == chunks,
                "Incremental biome locate loaded distant Minecraft chunks");
        System.out.println("CWI_BIOME_SEARCH_INCREMENTAL=budget:true,cancel:true,confirmed:true,chunks_unchanged:true");
        helper.succeed();
    }

    private static ServerLevel level(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        helper.assertTrue(level != null && level.getSeed() == 0, "Missing isolated seed-0 native dimension");
        return level;
    }

    private static BoundedCaveBiomeSource source(GameTestHelper helper, ServerLevel level) {
        var source = level.getChunkSource().getGenerator().getBiomeSource();
        helper.assertTrue(source instanceof BoundedCaveBiomeSource, "Native fixture lost the CWI biome wrapper");
        return (BoundedCaveBiomeSource) source;
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


    private static final class CountedSampler {
        private final CountingField[] fields;
        private final Climate.Sampler sampler;
        private CountedSampler(Climate.Sampler source) {
            fields = new CountingField[]{new CountingField(source.temperature()), new CountingField(source.humidity()),
                    new CountingField(source.continentalness()), new CountingField(source.erosion()),
                    new CountingField(source.depth()), new CountingField(source.weirdness())};
            sampler = new Climate.Sampler(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], source.spawnTarget());
        }
        private Climate.Sampler sampler() { return sampler; }
        private long evaluations() {
            long result = 0;
            for (var field : fields) result += field.evaluations;
            return result;
        }
    }

    private static final class CountingField implements DensityFunction.SimpleFunction {
        private final DensityFunction delegate;
        private long evaluations;
        private CountingField(DensityFunction delegate) { this.delegate = delegate; }
        @Override public double compute(FunctionContext context) { evaluations++; return delegate.compute(context); }
        @Override public double minValue() { return delegate.minValue(); }
        @Override public double maxValue() { return delegate.maxValue(); }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return delegate.codec(); }
    }

    private NativeBiomeSearchTest() {}
}

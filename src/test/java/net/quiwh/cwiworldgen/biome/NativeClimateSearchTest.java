package net.quiwh.cwiworldgen.biome;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;

/** Native MultiNoise selection and CWI search, with pure counted climate fields and no remote chunks. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeClimateSearchTest {
    private static final int[] BUDGETS = {1, 3, 256, Integer.MAX_VALUE};

    /** Removing shared-axis elision must fail this test even when old cave pruning remains enabled. */
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 200)
    public static void sharedClimateAxesAvoidExpensiveWorkWithoutSkippingCaveDepth(GameTestHelper helper) {
        ServerLevel level = level(helper);
        BiomesUsed biomes = biomes(level);
        int chunks = level.getChunkSource().getLoadedChunksCount();
        var rows = List.of(Pair.of(twoAxes(-20000, -2500, -20000, -2500), biomes.a()),
                Pair.of(twoAxes(2500, 20000, 2500, 20000), biomes.b()));
        var fields = constantFields(-1, .4, -1, -.6, .1, .75);
        var result = compare(helper, "absent shared-axis target", rows, fields, biomes,
                new BlockPos(-17, 80, 19), 96, 32, 64, biomes.b()::equals, false, 256);
        helper.assertTrue(result.expected() == null && result.actual() == null,
                "Impossible target climate unexpectedly exists in the search domain");
        long controlExpensive = result.control().calls(1, 3, 5);
        long candidateExpensive = result.candidate().calls(1, 3, 5);
        System.out.println("CWI_CLIMATE_WORK=baseline:" + result.control().counts()
                + ",optimized:" + result.candidate().counts() + ",samples:" + result.samples());
        helper.assertTrue(controlExpensive > 0 && candidateExpensive < controlExpensive,
                "Shared climate axes still evaluate expensive humidity/erosion/weirdness: "
                        + controlExpensive + "->" + candidateExpensive);
        helper.assertTrue(result.candidate().calls(4) > 0,
                "Surface optimization bypassed the underground cave depth gate");
        helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == chunks,
                "Synthetic climate locate loaded remote chunks");
        helper.succeed();
    }

    /** An envelope-only filter, changed offsets, or skipped RTree calls must fail these cases. */
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 200)
    public static void nearestClimateAndStatefulBoundaryChoicesMatchVanilla(GameTestHelper helper) {
        ServerLevel level = level(helper);
        BiomesUsed biomes = biomes(level);
        int cases = 0;
        List<List<Pair<Climate.ParameterPoint, Holder<Biome>>>> tables = List.of(
                List.of(Pair.of(axis(0, 0, 0, 0), biomes.a()), Pair.of(axis(0, 20000, 20000, 0), biomes.b())),
                List.of(Pair.of(axis(0, -10000, 5000, 0), biomes.a()), Pair.of(axis(0, -5000, 10000, 0), biomes.b())),
                List.of(Pair.of(axis(0, 0, 0, 10000), biomes.a()), Pair.of(axis(0, 5000, 5000, 0), biomes.b())),
                List.of(Pair.of(axis(0, -15000, -10000, 0), biomes.a()), Pair.of(axis(0, 10000, 15000, 0), biomes.a()),
                        Pair.of(axis(0, -5000, 5000, 0), biomes.b())),
                List.of(Pair.of(axis(0, 0, 0, 0), biomes.a()), Pair.of(axis(0, 0, 0, 0), biomes.b()),
                        Pair.of(axis(0, 15000, 15000, 0), biomes.c())),
                List.of(Pair.of(axis(0, 15000, 15000, 0), biomes.c()), Pair.of(axis(0, 0, 0, 0), biomes.b()),
                        Pair.of(axis(0, 0, 0, 0), biomes.a())));
        for (int table = 0; table < tables.size(); table++) {
            for (double temperature : new double[]{-.75, 0, .4, 1.25}) {
                for (var target : targets(biomes)) {
                    compare(helper, "nearest table=" + table + ",T=" + temperature,
                            tables.get(table), constantFields(temperature, -.2, .3, .4, .1, -.5), biomes,
                            new BlockPos(-17, 80, 19), 32, 32, 128, target, false, 256);
                    cases++;
                }
            }
        }
        // A target can be nearest even though the climate lies outside its own parameter interval.
        var outside = compare(helper, "outside own box remains nearest", tables.getFirst(),
                constantFields(.4, 0, 0, 0, .1, 0), biomes, new BlockPos(0, 80, 0),
                0, 32, 64, biomes.a()::equals, false, 1);
        helper.assertTrue(outside.actual() != null && outside.actual().getFirst().equals(new BlockPos(0, 80, 0)),
                "Climate-envelope rejection discarded the actual nearest biome");

        var tieRows = List.of(Pair.of(axis(0, -10000, 0, 0), biomes.a()),
                Pair.of(axis(0, 0, 10000, 0), biomes.b()));
        Fixture control = fixture(tieRows, biomes, false), candidate = fixture(tieRows, biomes, false);
        Climate.TargetPoint warmB = Climate.target(.5F, 0, 0, 0, .1F, 0);
        control.surface().getNoiseBiome(warmB);
        candidate.surface().getNoiseBiome(warmB);
        FieldSpec[] rejectedThenTie = constantFields(0, 0, 0, 0, .1, 0);
        rejectedThenTie[0] = bounded(context -> context.blockY() == 80 ? -.5 : 0);
        var boundary = compareExisting(helper, "non-target then tie after warm B", control, candidate,
                rejectedThenTie, new BlockPos(0, 80, 0), 0, 32, 64, biomes.b()::equals, level, 1);
        helper.assertTrue(boundary.expected() == null && boundary.actual() == null,
                "Skipping a non-target tree lookup changed a later boundary tie");
        // Keep each native tree alive across queries, but replace the sampler/query each time.
        for (double temperature : new double[]{0, -.5, 0, .5, 0, -.5, 0}) {
            compareExisting(helper, "same source across queries T=" + temperature, control, candidate,
                    constantFields(temperature, .2, -.2, .3, .1, -.3),
                    new BlockPos(0, 80, 0), 0, 32, 64, biomes.b()::equals, level, 3);
            cases++;
        }
        System.out.println("CWI_CLIMATE_NEAREST=cases:" + (cases + 2)
                + ",outside_box:true,offsets:true,duplicates:true,tie_history:true,cross_query:true");
        helper.succeed();
    }

    /** Reusing a field at Y=0, or eliding a genuinely varying table axis, must fail this matrix. */
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 200)
    public static void varyingAxesAndCustomSurfaceSourcesRetainHeightDependence(GameTestHelper helper) {
        ServerLevel level = level(helper);
        BiomesUsed biomes = biomes(level);
        int chunks = level.getChunkSource().getLoadedChunksCount();
        int cases = 0;
        for (int dimension = 0; dimension < 6; dimension++) {
            var rows = List.of(Pair.of(axis(dimension, -20000, -2500, 0), biomes.a()),
                    Pair.of(axis(dimension, -2499, 2499, 0), biomes.b()),
                    Pair.of(axis(dimension, 2500, 20000, 0), biomes.c()));
            FieldSpec[] fields = constantFields(0, 0, 0, 0, .1, 0);
            final int salt = dimension;
            fields[dimension] = bounded(context -> (Math.floorMod(context.blockY() / 4
                    + context.blockX() / 4 - context.blockZ() / 4 + salt, 17) - 8) / 8.0);
            for (BlockPos origin : origins()) {
                for (var target : targets(biomes)) {
                    compare(helper, "height-dependent dimension=" + dimension, rows, fields, biomes,
                            origin, 32, 32, 64, target, false, 3);
                    cases++;
                }
            }
        }
        var rows = List.of(Pair.of(vector(-10000, -10000, -10000, -10000, -10000, -10000), biomes.a()),
                Pair.of(vector(0, 0, 0, 0, 0, 0), biomes.b()),
                Pair.of(vector(10000, 10000, 10000, 10000, 10000, 10000), biomes.c()));
        FieldSpec[] allVary = new FieldSpec[6];
        for (int dimension = 0; dimension < 6; dimension++) {
            final int salt = dimension;
            allVary[dimension] = bounded(context -> (Math.floorMod(context.blockY() / 4
                    + context.blockX() / 8 + context.blockZ() / 8 + salt * 3, 17) - 8) / 8.0);
        }
        for (boolean customSource : new boolean[]{false, true}) {
            for (BlockPos origin : origins()) {
                for (var target : targets(biomes)) {
                    compare(helper, "all axes vary,custom=" + customSource, rows, allVary, biomes,
                            origin, 32, 32, 64, target, customSource, 256);
                    cases++;
                }
            }
        }
        helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == chunks,
                "Climate differential searches loaded distant Minecraft chunks");
        System.out.println("CWI_CLIMATE_HEIGHT=cases:" + cases
                + ",all_six_axes:true,custom_source:true,cave_mixed:true,chunks_unchanged:true");
        helper.succeed();
    }

    /** Float conversion, quantization, unsafe bounds, or overflowing score shortcuts must retain native results. */
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 200)
    public static void quantizationAndUnsafeClimateScoresUseNativeSemantics(GameTestHelper helper) {
        ServerLevel level = level(helper);
        BiomesUsed biomes = biomes(level);
        var rows = List.of(Pair.of(axis(0, -2, -1, 0), biomes.a()),
                Pair.of(axis(0, 0, 0, 0), biomes.b()), Pair.of(axis(0, 1, 2, 0), biomes.c()));
        int cases = 0;
        double[] boundaries = {-1.0001001, -1, -.00020001, -.0001, -.00009999,
                -Double.MIN_VALUE, 0, Double.MIN_VALUE, .00009999, .0001, .00020001,
                Math.nextDown((double).0001F), Math.nextUp((double).0001F), 1, 1.0001001};
        for (double value : boundaries) {
            for (var target : targets(biomes)) {
                compare(helper, "quantization T=" + value, rows,
                        constantFields(value, .2, -.3, .4, .1, -.5), biomes,
                        new BlockPos(-1, 80, -1), 0, 32, 64, target, false, 3);
                cases++;
            }
        }
        for (double value : new double[]{Double.NaN, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, -1.0E20, 1.0E20}) {
            for (int dimension : new int[]{0, 1, 4}) {
                FieldSpec[] fields = constantFields(.4, .2, -.3, .4, .1, -.5);
                fields[dimension] = new FieldSpec(value, value, context -> value);
                for (var target : targets(biomes)) {
                    compare(helper, "unsafe field=" + dimension + ",value=" + value, rows, fields, biomes,
                            new BlockPos(0, 16, 0), 0, 32, 64, target, false, 1);
                    cases++;
                }
            }
        }
        var unsafeTables = List.of(
                List.of(Pair.of(axis(0, -10000, 0, 4_000_000_000L), biomes.a()),
                        Pair.of(axis(0, 0, 10000, 0), biomes.b())),
                List.of(Pair.of(unsafeCommonAxis(Long.MIN_VALUE + 8, Long.MIN_VALUE + 16, -10000), biomes.a()),
                        Pair.of(unsafeCommonAxis(Long.MIN_VALUE + 8, Long.MIN_VALUE + 16, 10000), biomes.b())));
        for (int table = 0; table < unsafeTables.size(); table++) {
            for (var target : targets(biomes)) {
                compare(helper, "unsafe parameter table=" + table, unsafeTables.get(table),
                        constantFields(.4, .2, -.3, .4, .1, -.5), biomes,
                        new BlockPos(0, 80, 0), 0, 32, 64, target, false, 256);
                cases++;
            }
        }
        System.out.println("CWI_CLIMATE_NUMERIC=cases:" + cases
                + ",quantization:true,nonfinite:true,unsafe_scores:true");
        helper.succeed();
    }

    /** Resuming work in different batch sizes must neither reorder samples nor leak cancelled results. */
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 200)
    public static void climateQueriesRemainEquivalentAcrossBudgetsAndCancellation(GameTestHelper helper) {
        ServerLevel level = level(helper);
        BiomesUsed biomes = biomes(level);
        var rows = List.of(Pair.of(twoAxes(-20000, -2500, -20000, 20000), biomes.a()),
                Pair.of(twoAxes(-2499, 2499, -20000, 20000), biomes.b()),
                Pair.of(twoAxes(2500, 20000, -20000, 20000), biomes.c()));
        FieldSpec[] fields = constantFields(0, .3, -.4, .5, .1, -.6);
        fields[0] = bounded(context -> (Math.floorMod(context.blockX() + context.blockY() - context.blockZ(), 64) - 32) / 32.0);
        int cases = 0;
        for (var target : targets(biomes)) {
            long samples = -1;
            for (int budget : BUDGETS) {
                var result = compare(helper, "incremental budget=" + budget, rows, fields, biomes,
                        new BlockPos(-17, 80, 19), 64, 32, 64, target, false, budget);
                if (samples == -1) samples = result.samples();
                helper.assertTrue(samples == result.samples(), "Batch size changed the number/order of sampled positions");
                cases++;
            }
        }
        AtomicBoolean cancellation = new AtomicBoolean();
        Fixture fixture = fixture(rows, biomes, false);
        var counted = new CountedSampler(constantFields(-1, .3, -.4, .5, .1, -.6));
        var query = CwiBiomeSearch.create(fixture.source(), new BlockPos(0, 80, 0), 128, 32, 64,
                biomes.c()::equals, counted.sampler(), level.getMinBuildHeight(), level.getMaxBuildHeight(), cancellation::get);
        helper.assertTrue(!query.step(1, Long.MAX_VALUE) && query.samples() == 1,
                "Missing-climate query ignored its one-sample budget");
        long beforeCancel = counted.calls(0, 1, 2, 3, 4, 5);
        cancellation.set(true);
        helper.assertTrue(query.step(256, Long.MAX_VALUE) && query.cancelled() && query.result() == null,
                "Cancelled climate query delivered a stale biome result");
        helper.assertTrue(counted.calls(0, 1, 2, 3, 4, 5) == beforeCancel,
                "Cancelled query continued evaluating climate fields");
        System.out.println("CWI_CLIMATE_INCREMENTAL=cases:" + cases
                + ",budgets:1/3/256/full,cancel:true,no_stale_result:true");
        helper.succeed();
    }

    /** Shared costs must preserve internal-node pruning and cached ties in a genuinely branched native tree. */
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 200)
    public static void branchedClimateTreesAndConservativeBoundsMatchVanilla(GameTestHelper helper) {
        ServerLevel level = level(helper);
        BiomesUsed biomes = biomes(level);
        int chunks = level.getChunkSource().getLoadedChunksCount();
        var rows = new ArrayList<Pair<Climate.ParameterPoint, Holder<Biome>>>();
        for (int row = 0; row < 64; row++) {
            // The final two leaves have identical boxes/offsets but different biome holders.
            int cell = row == 63 ? 62 : row;
            long temperature = -17500 + cell / 8 * 5000L;
            long continentalness = -17500 + cell % 8 * 5000L;
            Climate.Parameter[] axes = {
                    new Climate.Parameter(temperature - cell % 3 * 300L, temperature + cell % 4 * 500L),
                    new Climate.Parameter(-1000, 1000),
                    new Climate.Parameter(continentalness - cell % 4 * 400L, continentalness + cell % 3 * 700L),
                    new Climate.Parameter(-1500, 500), new Climate.Parameter(-1000, 1000),
                    new Climate.Parameter(-500, 1500)};
            Holder<Biome> biome = row == 63 ? biomes.a()
                    : switch (row % 3) { case 0 -> biomes.a(); case 1 -> biomes.b(); default -> biomes.c(); };
            rows.add(Pair.of(parameters(axes, cell % 5 * 700L), biome));
        }
        int cases = 0;
        for (int boundsMode = 0; boundsMode < 3; boundsMode++) {
            Fixture control = fixture(rows, biomes, false), candidate = fixture(rows, biomes, false);
            Climate.TargetPoint warm = Climate.target(1.75F, 1.2F, 1.25F, -.9F, -.3F, 1.6F);
            control.surface().getNoiseBiome(warm);
            candidate.surface().getNoiseBiome(warm);
            Random random = new Random(0x4357495254524545L);
            // Each mode reuses both trees across all predicates and samplers, preserving last-leaf history.
            for (int sample = 0; sample < 32; sample++) {
                int row = sample < 2 ? 62 : random.nextInt(rows.size());
                var point = rows.get(row).getFirst();
                float boundaryT = (sample % 2 == 0 ? point.temperature().min() : point.temperature().max()) / 10000F;
                float boundaryC = (sample % 2 == 0 ? point.continentalness().max() : point.continentalness().min()) / 10000F;
                double temperature = switch (sample % 4) {
                    case 0 -> Math.nextDown(boundaryT);
                    case 1 -> boundaryT;
                    case 2 -> Math.nextUp(boundaryT);
                    default -> (random.nextInt(38001) - 19000) / 10000.0;
                };
                double continentalness = switch (sample % 4) {
                    case 0 -> Math.nextUp(boundaryC);
                    case 1 -> boundaryC;
                    case 2 -> Math.nextDown(boundaryC);
                    default -> (random.nextInt(38001) - 19000) / 10000.0;
                };
                // Every shared field is outside its common interval, but all actual scores stay finite/safe.
                FieldSpec[] fields = constantFields(temperature, 1.2, continentalness, -.9, -.3, 1.6);
                fields[0] = bounded(context -> temperature
                        + (Math.floorMod(context.blockX() / 4 + context.blockY() / 4, 3) - 1) * .0001);
                fields[2] = bounded(context -> continentalness
                        + (Math.floorMod(context.blockY() / 4 - context.blockZ() / 4, 3) - 1) * .0001);
                fields[4] = bounded(context -> context.blockY() < 64 ? .6 : -.3);
                if (boundsMode > 0) {
                    for (int dimension = 0; dimension < fields.length; dimension++) {
                        // Retained-axis bounds can be conservative; unsafe omitted-axis bounds forbid elision.
                        if (boundsMode == 2 || dimension == 0 || dimension == 2) {
                            fields[dimension] = new FieldSpec(-Double.MAX_VALUE, Double.MAX_VALUE,
                                    fields[dimension].value());
                        }
                    }
                }
                int targetIndex = 0;
                for (var target : targets(biomes)) {
                    compareExisting(helper, "branched tree mode=" + boundsMode + ",sample=" + sample,
                            control, candidate, fields, origins().get(sample % origins().size()),
                            sample % 4 == 0 ? 32 : 0, 32, sample % 2 == 0 ? 64 : 128, target, level,
                            BUDGETS[(sample + targetIndex++) % BUDGETS.length]);
                    cases++;
                }
            }
        }
        helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == chunks,
                "Branched climate differential searches loaded distant Minecraft chunks");
        System.out.println("CWI_CLIMATE_TREE=cases:" + cases
                + ",rows:64,shared_cost_outside:true,offsets:true,duplicate_ties:true,cross_query:true"
                + ",retained_conservative_bounds:true,native_fallback:true,budgets:1/3/256/full,chunks_unchanged:true");
        helper.succeed();
    }

    /** A source's exposed rows need not describe an overridden lookup or its already-built native index. */
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 200)
    public static void overriddenAndMutableParameterListsKeepNativeLookups(GameTestHelper helper) {
        ServerLevel level = level(helper);
        BiomesUsed biomes = biomes(level);
        int cases = 0;
        for (boolean mutable : new boolean[]{false, true}) {
            Fixture control = nonstandardParameterFixture(biomes, mutable);
            Fixture candidate = nonstandardParameterFixture(biomes, mutable);
            FieldSpec[] fields = constantFields(.4, .75, -.3, .4, .1, -.5);
            fields[1] = bounded(context -> context.blockY() == 80 ? .75 : -.75);
            for (var target : targets(biomes)) {
                compareExisting(helper, "nonstandard parameters mutable=" + mutable, control, candidate,
                        fields, new BlockPos(-17, 80, 19), 0, 32, 64, target, level, 3);
                cases++;
            }
        }
        System.out.println("CWI_CLIMATE_FALLBACK=cases:" + cases
                + ",parameter_override:true,mutated_rows_after_index:true,height_dependent_humidity:true");
        helper.succeed();
    }

    private static Differential compare(GameTestHelper helper, String label,
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> rows, FieldSpec[] fields, BiomesUsed biomes,
            BlockPos origin, int radius, int horizontalStep, int verticalStep,
            Predicate<Holder<Biome>> target, boolean custom, int budget) {
        return compareExisting(helper, label, fixture(rows, biomes, custom), fixture(rows, biomes, custom),
                fields, origin, radius, horizontalStep, verticalStep, target, level(helper), budget);
    }

    private static Differential compareExisting(GameTestHelper helper, String label,
            Fixture controlSource, Fixture candidateSource, FieldSpec[] fields, BlockPos origin,
            int radius, int horizontalStep, int verticalStep, Predicate<Holder<Biome>> target,
            ServerLevel level, int budget) {
        CountedSampler control = new CountedSampler(fields), candidate = new CountedSampler(fields);
        var expected = baseline(controlSource.source()).findClosestBiome3d(origin, radius, horizontalStep,
                verticalStep, target, control.sampler(), level);
        var query = CwiBiomeSearch.create(candidateSource.source(), origin, radius, horizontalStep,
                verticalStep, target, candidate.sampler(), level.getMinBuildHeight(), level.getMaxBuildHeight(), () -> false);
        int batches = 0;
        while (!query.step(budget, Long.MAX_VALUE)) {
            helper.assertTrue(++batches < 100000, "Climate query failed to finish: " + label);
        }
        helper.assertTrue(Objects.equals(expected, query.result()), "Native climate result differs: " + label
                + ",origin=" + origin + ",expected=" + expected + ",actual=" + query.result());
        helper.assertTrue(!query.cancelled(), "Uncancelled differential query reported cancellation: " + label);
        return new Differential(expected, query.result(), control, candidate, query.samples());
    }

    private static Fixture fixture(List<Pair<Climate.ParameterPoint, Holder<Biome>>> rows,
            BiomesUsed biomes, boolean custom) {
        var surface = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(List.copyOf(rows)));
        return fixture(surface, biomes, custom);
    }

    private static Fixture fixture(MultiNoiseBiomeSource surface, BiomesUsed biomes, boolean custom) {
        BiomeSource exposed = custom ? forwarding(surface) : surface;
        var source = new BoundedCaveBiomeSource(exposed, biomes.fallback(), 64, 448,
                List.of(new BoundedCaveBiomeSource.Region(biomes.cave(), -48, 32, 48, 128, 1)), 21);
        return new Fixture(source, surface);
    }

    private static Fixture nonstandardParameterFixture(BiomesUsed biomes, boolean mutable) {
        Climate.ParameterList<Holder<Biome>> parameters;
        if (mutable) {
            var rows = new ArrayList<>(List.of(Pair.of(axis(1, -20000, -2500, 0), biomes.a()),
                    Pair.of(axis(1, 2500, 20000, 0), biomes.b())));
            parameters = new Climate.ParameterList<>(rows);
            // The immutable tree still separates humidity, although values() now advertises a common axis.
            rows.set(0, Pair.of(axis(1, -20000, 20000, 0), biomes.a()));
            rows.set(1, Pair.of(axis(1, -20000, 20000, 0), biomes.b()));
        } else {
            var rows = List.of(Pair.of(axis(0, -20000, -2500, 0), biomes.a()),
                    Pair.of(axis(0, 2500, 20000, 0), biomes.b()));
            parameters = new Climate.ParameterList<>(rows) {
                @Override public Holder<Biome> findValue(Climate.TargetPoint point) {
                    return point.humidity() < 0 ? biomes.a() : biomes.b();
                }
            };
        }
        return fixture(MultiNoiseBiomeSource.createFromList(parameters), biomes, false);
    }

    private static BiomeSource baseline(BoundedCaveBiomeSource source) { return forwarding(source); }

    private static BiomeSource forwarding(BiomeSource source) {
        return new BiomeSource() {
            @Override protected MapCodec<? extends BiomeSource> codec() { return MapCodec.unit(this); }
            @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return source.possibleBiomes().stream(); }
            @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
                return source.getNoiseBiome(x, y, z, sampler);
            }
        };
    }

    private static ServerLevel level(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        helper.assertTrue(level != null && level.getSeed() == 0, "Missing isolated seed-0 native dimension");
        helper.assertTrue(level.getMinBuildHeight() == -64 && level.getMaxBuildHeight() == 384,
                "Synthetic search no longer exercises the complete native build-height domain");
        return level;
    }

    private static BiomesUsed biomes(ServerLevel level) {
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        return new BiomesUsed(registry.getHolderOrThrow(Biomes.PLAINS), registry.getHolderOrThrow(Biomes.DESERT),
                registry.getHolderOrThrow(Biomes.FOREST), registry.getHolderOrThrow(Biomes.LUSH_CAVES),
                registry.getHolderOrThrow(Biomes.DRIPSTONE_CAVES));
    }

    private static List<Predicate<Holder<Biome>>> targets(BiomesUsed biomes) {
        return List.of(biomes.a()::equals, biomes.b()::equals, biomes.c()::equals,
                biomes.cave()::equals, biomes.fallback()::equals,
                holder -> holder.equals(biomes.a()) || holder.equals(biomes.cave()), holder -> true, holder -> false);
    }

    private static List<BlockPos> origins() {
        return List.of(new BlockPos(-33, -17, 35), new BlockPos(-17, 80, 19), new BlockPos(32, 383, -36));
    }

    private static Climate.ParameterPoint axis(int dimension, long min, long max, long offset) {
        Climate.Parameter[] values = new Climate.Parameter[6];
        Arrays.fill(values, new Climate.Parameter(-20000, 20000));
        values[dimension] = new Climate.Parameter(min, max);
        return parameters(values, offset);
    }

    private static Climate.ParameterPoint twoAxes(long minT, long maxT, long minC, long maxC) {
        Climate.Parameter[] values = new Climate.Parameter[6];
        Arrays.fill(values, new Climate.Parameter(-20000, 20000));
        values[0] = new Climate.Parameter(minT, maxT);
        values[2] = new Climate.Parameter(minC, maxC);
        return parameters(values, 0);
    }

    private static Climate.ParameterPoint vector(long... coordinates) {
        Climate.Parameter[] values = new Climate.Parameter[6];
        for (int dimension = 0; dimension < 6; dimension++)
            values[dimension] = new Climate.Parameter(coordinates[dimension], coordinates[dimension]);
        return parameters(values, 0);
    }

    private static Climate.ParameterPoint unsafeCommonAxis(long min, long max, long temperature) {
        Climate.Parameter[] values = new Climate.Parameter[6];
        Arrays.fill(values, new Climate.Parameter(-20000, 20000));
        values[0] = new Climate.Parameter(temperature, temperature);
        values[1] = new Climate.Parameter(min, max);
        return parameters(values, 0);
    }

    private static Climate.ParameterPoint parameters(Climate.Parameter[] values, long offset) {
        return new Climate.ParameterPoint(values[0], values[1], values[2], values[3], values[4], values[5], offset);
    }

    private static FieldSpec[] constantFields(double... values) {
        FieldSpec[] fields = new FieldSpec[6];
        for (int dimension = 0; dimension < 6; dimension++) {
            final double value = values[dimension];
            fields[dimension] = bounded(context -> value);
        }
        return fields;
    }

    private static FieldSpec bounded(Value value) { return new FieldSpec(-2, 2, value); }

    @FunctionalInterface private interface Value { double at(DensityFunction.FunctionContext context); }
    private record FieldSpec(double min, double max, Value value) {}
    private record BiomesUsed(Holder<Biome> a, Holder<Biome> b, Holder<Biome> c,
                              Holder<Biome> cave, Holder<Biome> fallback) {}
    private record Fixture(BoundedCaveBiomeSource source, MultiNoiseBiomeSource surface) {}
    private record Differential(Pair<BlockPos, Holder<Biome>> expected, Pair<BlockPos, Holder<Biome>> actual,
                                CountedSampler control, CountedSampler candidate, long samples) {}

    private static final class CountedSampler {
        private final CountingField[] fields = new CountingField[6];
        private final Climate.Sampler sampler;
        private CountedSampler(FieldSpec[] specs) {
            for (int dimension = 0; dimension < fields.length; dimension++) fields[dimension] = new CountingField(specs[dimension]);
            sampler = new Climate.Sampler(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], List.of());
        }
        private Climate.Sampler sampler() { return sampler; }
        private long calls(int... dimensions) {
            long result = 0;
            for (int dimension : dimensions) result += fields[dimension].calls;
            return result;
        }
        private String counts() {
            return Arrays.toString(Arrays.stream(fields).mapToLong(field -> field.calls).toArray()).replace(" ", "");
        }
    }

    private static final class CountingField implements DensityFunction.SimpleFunction {
        private final FieldSpec spec;
        private long calls;
        private CountingField(FieldSpec spec) { this.spec = spec; }
        @Override public double compute(FunctionContext context) { calls++; return spec.value().at(context); }
        @Override public double minValue() { return spec.min(); }
        @Override public double maxValue() { return spec.max(); }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return DensityFunctions.constant(0).codec(); }
    }

    private NativeClimateSearchTest() {}
}

package net.quiwh.cwiworldgen.feature;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeIndustrialDepositTest {
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void existingLayeredVeinsScatterMatchingCompoundDeposits(GameTestHelper helper) {
        var level = helper.getLevel();
        var registry = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        var compoundTag = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("cwi", "ore_deposit"));
        BlockPos origin = new BlockPos(19000, 96, -19000);
        for (String mineral : List.of("halite", "hematite")) {
            ResourceLocation featureId = ResourceLocation.fromNamespaceAndPath("cwi", "striated_ores/" + mineral);
            var configured = registry.getOptional(featureId).orElseThrow(() -> new AssertionError("Missing compound layered vein " + featureId));
            check(BuiltInRegistries.FEATURE.getKey(configured.feature()).equals(ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "layered_deposit")),
                "Compound striated veins must use the concentrated deposit feature");
            var expected = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("kubejs", mineral + "_deposit"));
            check(expected != Blocks.AIR, "Missing registered compound deposit " + mineral);
            int deposits = 0;
            int hosts = 0;
            int mixedBoundaries = 0;
            Set<BlockState> hostMaterials = new HashSet<>();
            RandomSource seeds = RandomSource.create(700);
            int mineralBodies = 0;
            int bodiesWithDeposits = 0;
            for (int attempt = 0; attempt < 20; attempt++) {
                // The production size32 Create lens reaches at most15 blocks from its center.
                for (int x = -17; x <= 17; x++) for (int y = -17; y <= 17; y++) for (int z = -17; z <= 17; z++)
                    level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                if (!configured.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seeds.nextLong()), origin)) continue;
                mineralBodies++;
                int depositsBefore = deposits;
                for (int x = -16; x <= 16; x++) for (int y = -16; y <= 16; y++) for (int z = -16; z <= 16; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(compoundTag)) {
                        check(state.is(expected), "Wrong compound deposit inside " + mineral + " mineral vein: " + state);
                        deposits++;
                        for (Direction direction : Direction.values()) {
                            BlockState neighbor = level.getBlockState(pos.relative(direction));
                            if (!neighbor.is(Blocks.STONE) && !neighbor.is(compoundTag) && !neighbor.isAir()) {
                                mixedBoundaries++;
                                break;
                            }
                        }
                    } else if (!state.is(Blocks.STONE)) {
                        check(!state.isAir() && state.getFluidState().isEmpty(), "Layered mineral vein introduced non-mineral terrain");
                        hostMaterials.add(state);
                        hosts++;
                    }
                }
                if (deposits > depositsBefore) bodiesWithDeposits++;
                // A real host body may have no retained group; only positive samples
                // contribute compound counts and mixed mineral boundary checks.
            }
            check(mineralBodies > 0 && bodiesWithDeposits > 0,
                "No matching compound deposits across twenty native Create " + mineral + " vein samples");
            check(hosts > deposits && hostMaterials.size() >= 2, "Compound deposits must remain scattered inside mixed host minerals: " + mineral);
            check(mixedBoundaries > deposits / 2, "Compound deposits are not interspersed among host minerals: " + mineral);
            System.out.println("CWI_LAYERED_COMPOUND_DEPOSIT=" + mineral + "; " + deposits + " deposits; " + hosts
                + " host blocks; " + hostMaterials.size() + " mineral types; " + mixedBoundaries + " mixed boundaries; "
                + bodiesWithDeposits + "/" + mineralBodies + " bodies retain deposits");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void shallowMineralIndicatorsMatchTheirDepositAndRespectTheSurface(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = new BlockPos(18008, 80, -17992);
        BlockPos surface = new BlockPos(origin.getX(), 116, origin.getZ());
        var feature = new IndustrialDepositFeature(IndustrialDepositConfiguration.CODEC);
        var config = new IndustrialDepositConfiguration(BlockTags.BASE_STONE_OVERWORLD, 7, 7, 7, 0.14F,
            List.of(material(Blocks.GRANITE.defaultBlockState(), 3), material(Blocks.DIORITE.defaultBlockState(), 2)),
            List.of(material(Blocks.IRON_ORE.defaultBlockState(), 1)), true);
        long indicatorSeed = -1;
        for (long seed = 0; seed < 16; seed++) {
            prepareSurface(helper, origin, surface.getY(), Blocks.DIRT.defaultBlockState());
            feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
                RandomSource.create(seed), origin, config));
            if (level.getBlockState(surface).is(Blocks.GRANITE)) {
                indicatorSeed = seed;
                break;
            }
        }
        check(indicatorSeed >= 0, "No shallow mineral indicator appeared across16 seeded attempts");
        check(level.getBlockState(surface).is(Blocks.GRANITE), "Prospecting clue must match the deposit's first host mineral");
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
            if (Math.abs(x) > 1 || Math.abs(z) > 1)
                check(level.getBlockState(surface.offset(x, 0, z)).is(Blocks.DIRT), "Indicator escaped its local outcrop");
        }
        var first = surfaceSnapshot(helper, surface);
        prepareSurface(helper, origin, surface.getY(), Blocks.DIRT.defaultBlockState());
        feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
            RandomSource.create(indicatorSeed), origin, config));
        check(first.equals(surfaceSnapshot(helper, surface)), "Prospecting outcrop is not seed deterministic");

        prepareSurface(helper, origin, surface.getY(), Blocks.DIRT.defaultBlockState());
        feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
            RandomSource.create(indicatorSeed), origin.below(50), config));
        check(level.getBlockState(surface).is(Blocks.DIRT), "Deep deposit must not produce a surface marker");

        prepareSurface(helper, origin, surface.getY(), Blocks.STONE_BRICKS.defaultBlockState());
        feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
            RandomSource.create(indicatorSeed), origin, config));
        check(level.getBlockState(surface).is(Blocks.STONE_BRICKS), "Constructed surface was overwritten");
        check(level.getBlockState(surface.above()).isAir(), "Indicator appeared above constructed ground");

        prepareSurface(helper, origin, surface.getY(), Blocks.DIRT.defaultBlockState());
        level.setBlock(surface.above(), Blocks.WATER.defaultBlockState(), 2);
        feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
            RandomSource.create(indicatorSeed), origin, config));
        check(level.getBlockState(surface).is(Blocks.DIRT) && level.getBlockState(surface.above()).is(Blocks.WATER),
            "Submerged ground must not produce an indicator");

        prepareSurface(helper, origin, surface.getY(), Blocks.DIRT.defaultBlockState());
        level.setBlock(surface.above(), Blocks.DEAD_BUSH.defaultBlockState(), 2);
        feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
            RandomSource.create(indicatorSeed), origin, config));
        check(level.getBlockState(surface).is(Blocks.DIRT) && level.getBlockState(surface.above()).is(Blocks.DEAD_BUSH),
            "Existing vegetation must not be overwritten by an indicator");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void productionMineralBodiesContainActualCompoundDeposits(GameTestHelper helper) {
        var level = helper.getLevel();
        var registry = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        var compoundTag = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("cwi", "ore_deposit"));
        BlockPos origin = new BlockPos(17000, 96, -16984);
        int checked = 0;
        int totalCompoundBlocks = 0;
        for (var configured : registry) {
            if (!(configured.feature() instanceof IndustrialDepositFeature)) continue;
            var config = (IndustrialDepositConfiguration) configured.config();
            check(Math.abs(config.oreFraction() - 0.028F) < 0.00001F,
                "Production deposits must contain one fifth of the former 14% amount: " + registry.getKey(configured));
            for (var material : config.ores()) {
                ResourceLocation name = BuiltInRegistries.BLOCK.getKey(material.state().getBlock());
                check(name.getNamespace().equals("kubejs") && name.getPath().endsWith("_deposit"),
                    "Industrial bodies must contain actual compound deposit blocks: " + name);
                check(material.state().is(compoundTag), "Compound block lacks target pack deposit tag: " + name);
            }
            for (int x = -8; x <= 8; x++) for (int y = -17; y <= 17; y++) for (int z = -8; z <= 8; z++)
                level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
            check(configured.place(level, level.getChunkSource().getGenerator(), RandomSource.create(117 + checked), origin),
                "Production industrial config failed placement: " + registry.getKey(configured));
            int compounds = 0;
            int hosts = 0;
            for (int x = -7; x <= 7; x++) for (int y = -16; y <= 16; y++) for (int z = -7; z <= 7; z++) {
                BlockState state = level.getBlockState(origin.offset(x, y, z));
                if (state.is(Blocks.STONE)) continue;
                check(Math.abs(x) <= config.radiusX() && Math.abs(y) <= config.radiusY() && Math.abs(z) <= config.radiusZ(),
                    "Production size exceeded its configured maximum: " + registry.getKey(configured));
                if (state.is(compoundTag)) compounds++;
                else hosts++;
            }
            check(compounds == Math.round((compounds + hosts) * config.oreFraction()),
                "Production industrial size changed the configured local compound fraction: " + registry.getKey(configured));
            check(compounds > 0 && hosts > compounds, "Production body must mix compound deposits into a larger mineral mass: "
                + registry.getKey(configured) + " deposits=" + compounds + " hosts=" + hosts);
            totalCompoundBlocks += compounds;
            checked++;
        }
        check(checked > 0, "No production industrial deposit configurations were loaded");
        System.out.println("CWI_COMPOUND_DEPOSITS=" + checked + " industrial bodies; " + totalCompoundBlocks + " actual deposit blocks");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void reducedIndustrialDepositsUseDistinctMineralAggregateFamilies(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = new BlockPos(21000, 96, -20984);
        var feature = new IndustrialDepositFeature(IndustrialDepositConfiguration.CODEC);
        Set<Set<BlockPos>> familyShapes = new HashSet<>();
        for (String morphology : List.of("cubic", "massive", "bedded", "crystalline", "nodular")) {
            var config = aggregateConfig(morphology);
            Set<Set<BlockPos>> seededShapes = new HashSet<>();
            int obliqueCrystals = 0;
            for (long seed : List.of(42L, 117L, 8675309L)) {
                for (int x = -8; x <= 8; x++) for (int y = -13; y <= 13; y++) for (int z = -8; z <= 8; z++)
                    level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                check(feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
                    RandomSource.create(seed), origin, config)), "Reduced aggregate body failed placement");
                Set<BlockPos> ore = new HashSet<>();
                int minerals = 0;
                for (int x = -7; x <= 7; x++) for (int y = -12; y <= 12; y++) for (int z = -7; z <= 7; z++) {
                    BlockPos relative = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(origin.offset(relative));
                    if (state.is(Blocks.IRON_ORE)) ore.add(relative);
                    if (!state.is(Blocks.STONE)) minerals++;
                }
                check(ore.size() == Math.round(minerals * 0.028F),
                    "Aggregate morphology changed the reduced deposit budget: " + morphology);
                long neighboring = ore.stream().filter(pos -> {
                    for (Direction direction : Direction.values()) if (ore.contains(pos.relative(direction))) return true;
                    return false;
                }).count();
                check(neighboring >= ore.size() * 0.9, "Aggregate contains isolated deposit speckles: " + morphology);
                check(components(ore) >= 2 && components(ore) <= 5,
                    "Reduced deposits must concentrate into a few separate aggregates: " + morphology + "/" + seed);
                if (morphology.equals("crystalline")) {
                    for (Set<BlockPos> crystal : connectedGroups(ore))
                        if (Math.min(span(crystal, 0), Math.min(span(crystal, 1), span(crystal, 2))) >= 3
                                && maximumCoordinateCorrelation(crystal) > 0.35) obliqueCrystals++;
                }
                seededShapes.add(Set.copyOf(ore));
                if (seed == 42L) familyShapes.add(Set.copyOf(ore));
                if (morphology.equals("bedded")) {
                    for (Set<BlockPos> bed : connectedGroups(ore)) {
                        int horizontal = Math.max(span(bed, 0), span(bed, 2));
                        check(horizontal >= span(bed, 1) * 1.5,
                            "Bedded deposits must retain broad lenses with limited dip");
                    }
                }
                System.out.println("CWI_REDUCED_AGGREGATE=" + morphology + ";seed=" + seed + ";deposits=" + ore.size()
                    + ";minerals=" + minerals + ";groups=" + components(ore));
            }
            check(seededShapes.size() == 3, "Aggregate orientations and proportions did not vary with seed: " + morphology);
            if (morphology.equals("crystalline")) check(obliqueCrystals >= 2,
                "Crystalline aggregates remain restricted to the three block axes");
        }
        check(familyShapes.size() == 5, "Mineral morphology configuration still produces a universal crystal shape");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void weightedIndustrialSizesKeepSmallMediumAndLargeBodiesWithinTheChunk(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = new BlockPos(23000, 96, -22984);
        var feature = new IndustrialDepositFeature(IndustrialDepositConfiguration.CODEC);
        var json = IndustrialDepositConfiguration.CODEC.encodeStart(JsonOps.INSTANCE, aggregateConfig("crystalline")).getOrThrow().getAsJsonObject();
        json.add("size_settings", JsonParser.parseString("{\"enabled\":true,\"favored_biomes\":[]}"));
        var config = IndustrialDepositConfiguration.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        Set<String> sizes = new HashSet<>();
        RandomSource seeds = RandomSource.create(31337);
        for (int attempt = 0; attempt < 64; attempt++) {
            long seed = seeds.nextLong();
            for (int x = -8; x <= 8; x++) for (int y = -13; y <= 13; y++) for (int z = -8; z <= 8; z++)
                level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
            check(feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
                RandomSource.create(seed), origin, config)), "Weighted industrial body failed placement");
            int minerals = 0;
            int deposits = 0;
            Set<BlockPos> ore = new HashSet<>();
            for (int x = -8; x <= 8; x++) for (int y = -13; y <= 13; y++) for (int z = -8; z <= 8; z++) {
                BlockState state = level.getBlockState(origin.offset(x, y, z));
                if (state.is(Blocks.STONE)) continue;
                check(Math.abs(x) <= 7 && Math.abs(y) <= 12 && Math.abs(z) <= 7,
                    "Weighted industrial size escaped its configured chunk-contained maximum");
                minerals++;
                if (state.is(Blocks.IRON_ORE)) {
                    deposits++;
                    ore.add(new BlockPos(x, y, z));
                }
            }
            check(deposits == Math.round(minerals * 0.028F), "Weighted size changed local deposit concentration");
            long neighboring = ore.stream().filter(pos -> {
                for (Direction direction : Direction.values()) if (ore.contains(pos.relative(direction))) return true;
                return false;
            }).count();
            check(neighboring >= ore.size() * .9,
                "Weighted size broke aggregate growth into isolated voxel shards: " + neighboring + "/" + ore.size());
            sizes.add(minerals < 700 ? "small" : minerals < 1700 ? "medium" : "large");
        }
        check(sizes.equals(Set.of("small", "medium", "large")), "Weighted industrial settings did not select all three body sizes: " + sizes);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void tinyEligibleIndustrialRemnantsDoNotForceExtraDepositBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = new BlockPos(27000, 96, -26984);
        var feature = new IndustrialDepositFeature(IndustrialDepositConfiguration.CODEC);
        for (int x = -8; x <= 8; x++) for (int y = -13; y <= 13; y++) for (int z = -8; z <= 8; z++)
            level.setBlock(origin.offset(x, y, z), Blocks.BEDROCK.defaultBlockState(), 2);
        for (int x = -1; x <= 1; x++) level.setBlock(origin.offset(x, 0, 0), Blocks.STONE.defaultBlockState(), 2);
        check(feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
            RandomSource.create(42), origin, aggregateConfig("massive"))), "Eligible mineral remnant did not generate");
        for (int x = -1; x <= 1; x++) {
            BlockState state = level.getBlockState(origin.offset(x, 0, 0));
            check(state.is(Blocks.GRANITE) || state.is(Blocks.DIORITE),
                "Tiny eligible remnant forced an ore block above the rounded 2.8% budget");
        }
        check(level.getBlockState(origin.above()).is(Blocks.BEDROCK), "Tiny remnant altered protected surrounding terrain");
        helper.succeed();
    }

    private static IndustrialDepositConfiguration aggregateConfig(String morphology) {
        String json = """
            {"replaceable":"minecraft:base_stone_overworld","radius_x":7,"radius_y":12,"radius_z":7,
             "ore_fraction":0.028,"hosts":[{"state":{"Name":"minecraft:granite"},"weight":3},
             {"state":{"Name":"minecraft:diorite"},"weight":2}],
             "ores":[{"state":{"Name":"minecraft:iron_ore"},"weight":1}],"morphologies":["%s"]}
            """.formatted(morphology);
        return IndustrialDepositConfiguration.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static double maximumCoordinateCorrelation(Set<BlockPos> positions) {
        double[] means = new double[3];
        for (BlockPos pos : positions) for (int axis = 0; axis < 3; axis++) means[axis] += coordinate(pos, axis) / (double) positions.size();
        double maximum = 0;
        for (int a = 0; a < 3; a++) for (int b = a + 1; b < 3; b++) {
            double covariance = 0, varianceA = 0, varianceB = 0;
            for (BlockPos pos : positions) {
                double da = coordinate(pos, a) - means[a], db = coordinate(pos, b) - means[b];
                covariance += da * db;
                varianceA += da * da;
                varianceB += db * db;
            }
            if (varianceA > 0 && varianceB > 0) maximum = Math.max(maximum, Math.abs(covariance) / Math.sqrt(varianceA * varianceB));
        }
        return maximum;
    }

    private static int span(Set<BlockPos> positions, int axis) {
        return positions.stream().mapToInt(pos -> coordinate(pos, axis)).max().orElseThrow()
            - positions.stream().mapToInt(pos -> coordinate(pos, axis)).min().orElseThrow() + 1;
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void productionShallowVeinsRemainDiscoverableThroughMatchingSurfaceMinerals(GameTestHelper helper) {
        var level = helper.getLevel();
        var registry = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        var configured = registry.get(ResourceLocation.parse("cwi:industrial/halite"));
        var config = (IndustrialDepositConfiguration) configured.config();
        BlockPos origin = new BlockPos(25000, 32, -24984);
        BlockPos surface = new BlockPos(origin.getX(), 64, origin.getZ());
        boolean found = false;
        for (long seed = 0; seed < 16 && !found; seed++) {
            prepareSurface(helper, origin, 64, Blocks.DIRT.defaultBlockState());
            check(configured.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), origin),
                "Production shallow vein did not generate");
            found = level.getBlockState(surface).equals(config.hosts().getFirst().atDepth(64));
        }
        check(found, "Reduced production veins lost their matching surface mineral clues");
        int deposits = 0;
        for (int x = -7; x <= 7; x++) for (int y = -12; y <= 12; y++) for (int z = -7; z <= 7; z++)
            if (level.getBlockState(origin.offset(x, y, z)).is(config.ores().getFirst().state().getBlock())) deposits++;
        check(deposits > 0, "Surface mineral clue must have real matching deposits beneath it");
        System.out.println("CWI_PRODUCTION_PROSPECTING=halite surface_y=64 body_center_y=32 deposits=" + deposits);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void mineralLensContainsScatteredOrePocketsAndPreservesItsBounds(GameTestHelper helper) {
        var level = helper.getLevel();
        // A private, disposable solid volume away from other test fixtures.
        BlockPos origin = new BlockPos(16008, 96, -15992);
        var feature = new IndustrialDepositFeature(IndustrialDepositConfiguration.CODEC);
        var config = new IndustrialDepositConfiguration(BlockTags.BASE_STONE_OVERWORLD, 7, 10, 7, 0.14F,
            List.of(material(Blocks.GRANITE.defaultBlockState(), 3), material(Blocks.DIORITE.defaultBlockState(), 2),
                material(Blocks.ANDESITE.defaultBlockState(), 1)),
            List.of(new IndustrialDepositConfiguration.Material(Blocks.IRON_ORE.defaultBlockState(),
                    Optional.of(Blocks.DEEPSLATE_IRON_ORE.defaultBlockState()), 3),
                new IndustrialDepositConfiguration.Material(Blocks.COPPER_ORE.defaultBlockState(),
                    Optional.of(Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState()), 2)));

        prepare(helper, origin);
        var before = snapshot(helper, origin);
        var protectedEntity = level.getBlockEntity(origin.west());
        check(feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
            RandomSource.create(42), origin.offset(-7, 0, -7), config)), "Deposit did not generate");
        var first = snapshot(helper, origin);
        Set<BlockPos> ore = new HashSet<>();
        Set<BlockState> minerals = new HashSet<>();
        int changed = 0;
        for (var entry : first.entrySet()) {
            BlockPos relative = entry.getKey();
            BlockState state = entry.getValue();
            if (!state.equals(before.get(relative))) {
                changed++;
                check(Math.abs(relative.getX()) <= 7 && Math.abs(relative.getY()) <= 10 && Math.abs(relative.getZ()) <= 7,
                    "Deposit escaped configured radii at " + relative);
                check((origin.getX() + relative.getX()) >> 4 == origin.getX() >> 4
                    && (origin.getZ() + relative.getZ()) >> 4 == origin.getZ() >> 4, "Deposit crossed a chunk boundary");
                if (state.is(Blocks.IRON_ORE) || state.is(Blocks.COPPER_ORE)) ore.add(relative);
                else minerals.add(state);
            }
        }
        check(changed > 1200, "Expected an industrial mineral body, got " + changed + " blocks");
        check(minerals.size() == 3, "Expected all three host minerals, got " + minerals);
        check(Math.abs(ore.size() / (double) changed - 0.14) < 0.002,
            "Actual ore fraction differs from config: " + ore.size() + "/" + changed);
        long neighboring = ore.stream().filter(pos -> {
            for (Direction direction : Direction.values()) if (ore.contains(pos.relative(direction))) return true;
            return false;
        }).count();
        check(neighboring > ore.size() * 0.85, "Ores must form pockets, not independent salt-and-pepper blocks");
        int components = components(ore);
        check(components >= 2, "Expected multiple separated ore pockets, got " + components);
        for (BlockPos pos : List.of(BlockPos.ZERO.east(), BlockPos.ZERO.south(), BlockPos.ZERO.west(),
                BlockPos.ZERO.north(), BlockPos.ZERO.above())) {
            check(first.get(pos).equals(before.get(pos)), "Protected block was overwritten at " + pos);
        }
        check(level.getBlockEntity(origin.west()) == protectedEntity, "Protected block entity was replaced");

        prepare(helper, origin);
        feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
            RandomSource.create(42), origin.offset(-7, 0, -7), config));
        check(first.equals(snapshot(helper, origin)), "Same seeded deposit did not reproduce");
        prepare(helper, origin);
        feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
            RandomSource.create(43), origin.offset(-7, 0, -7), config));
        check(!first.equals(snapshot(helper, origin)), "Different seed produced an identical deposit");

        prepare(helper, origin);
        var noTerrain = new IndustrialDepositConfiguration(BlockTags.LOGS, 7, 10, 7, 0.14F, config.hosts(), config.ores());
        check(!feature.place(new FeaturePlaceContext<>(Optional.empty(), level, level.getChunkSource().getGenerator(),
            RandomSource.create(42), origin, noTerrain)), "Unmatched terrain tag must reject the deposit");
        check(before.equals(snapshot(helper, origin)), "Rejected deposit modified terrain");
        check(config.ores().getFirst().atDepth(-1).is(Blocks.DEEPSLATE_IRON_ORE), "Deep ore palette was ignored");
        System.out.println("CWI_INDUSTRIAL_DEPOSIT=" + changed + " blocks; " + ore.size()
            + " actual ore blocks; " + components + " separated pockets; neighboring ore=" + neighboring);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void industrialCodecRejectsUnsafeRadiiAndEmptyPalettes(GameTestHelper helper) {
        String config = """
            {"replaceable":"minecraft:base_stone_overworld","radius_x":7,"radius_y":10,"radius_z":7,
             "ore_fraction":0.14,"hosts":[{"state":{"Name":"minecraft:granite"},"weight":1}],
             "ores":[{"state":{"Name":"minecraft:iron_ore"},"weight":1}]}
            """;
        check(IndustrialDepositConfiguration.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(config)).isSuccess(),
            "Valid deposit codec input was rejected");
        check(!IndustrialDepositConfiguration.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(config))
            .getOrThrow().surfaceIndicators(), "Indicators must default to disabled");
        for (String unsafe : List.of(config.replace("\"radius_x\":7", "\"radius_x\":8"),
            config.replace("\"radius_y\":10", "\"radius_y\":100"),
            config.replace("\"ore_fraction\":0.14", "\"ore_fraction\":0.9"),
            config.replace("[{\"state\":{\"Name\":\"minecraft:granite\"},\"weight\":1}]", "[]"),
            config.replace("minecraft:granite", "minecraft:chest"),
            config.replace("\"ore_fraction\":0.14", "\"morphologies\":[\"unknown\"],\"ore_fraction\":0.14"),
            config.replace("\"ore_fraction\":0.14", "\"morphologies\":[],\"ore_fraction\":0.14"))) {
            check(!IndustrialDepositConfiguration.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(unsafe)).isSuccess(),
                "Unsafe deposit codec input was accepted: " + unsafe);
        }
        helper.succeed();
    }

    private static IndustrialDepositConfiguration.Material material(BlockState state, int weight) {
        return new IndustrialDepositConfiguration.Material(state, Optional.empty(), weight);
    }

    private static void prepareSurface(GameTestHelper helper, BlockPos origin, int surfaceY, BlockState surface) {
        var level = helper.getLevel();
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
            for (int y = origin.getY() - 60; y <= surfaceY; y++)
                level.setBlock(new BlockPos(origin.getX() + x, y, origin.getZ() + z),
                    y == surfaceY ? surface : Blocks.STONE.defaultBlockState(), 2);
            for (int y = surfaceY + 1; y <= level.getMaxBuildHeight() - 1; y++)
                level.setBlock(new BlockPos(origin.getX() + x, y, origin.getZ() + z), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static Map<BlockPos, BlockState> surfaceSnapshot(GameTestHelper helper, BlockPos surface) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 2; y++) {
            BlockPos relative = new BlockPos(x, y, z);
            result.put(relative, helper.getLevel().getBlockState(surface.offset(relative)));
        }
        return result;
    }

    private static void prepare(GameTestHelper helper, BlockPos origin) {
        for (int x = -8; x <= 8; x++) for (int y = -11; y <= 11; y++) for (int z = -8; z <= 8; z++)
            helper.getLevel().setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(origin.east(), Blocks.AIR.defaultBlockState(), 2);
        helper.getLevel().setBlock(origin.south(), Blocks.WATER.defaultBlockState(), 2);
        helper.getLevel().setBlock(origin.west(), Blocks.CHEST.defaultBlockState(), 2);
        helper.getLevel().setBlock(origin.north(), Blocks.BEDROCK.defaultBlockState(), 2);
        helper.getLevel().setBlock(origin.above(), Blocks.STONE_BRICKS.defaultBlockState(), 2);
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper helper, BlockPos origin) {
        Map<BlockPos, BlockState> result = new HashMap<>();
        for (int x = -8; x <= 8; x++) for (int y = -11; y <= 11; y++) for (int z = -8; z <= 8; z++) {
            BlockPos relative = new BlockPos(x, y, z);
            result.put(relative, helper.getLevel().getBlockState(origin.offset(relative)));
        }
        return result;
    }

    private static int components(Set<BlockPos> positions) {
        return connectedGroups(positions).size();
    }

    private static List<Set<BlockPos>> connectedGroups(Set<BlockPos> positions) {
        Set<BlockPos> remaining = new HashSet<>(positions);
        List<Set<BlockPos>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            var queue = new ArrayDeque<BlockPos>();
            Set<BlockPos> group = new HashSet<>();
            queue.add(remaining.iterator().next());
            remaining.remove(queue.peek());
            while (!queue.isEmpty()) {
                BlockPos pos = queue.remove();
                group.add(pos);
                for (Direction direction : Direction.values()) {
                    BlockPos adjacent = pos.relative(direction);
                    if (remaining.remove(adjacent)) queue.add(adjacent);
                }
            }
            result.add(group);
        }
        return result;
    }

    private static int coordinate(BlockPos pos, int axis) {
        return axis == 0 ? pos.getX() : axis == 1 ? pos.getY() : pos.getZ();
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}

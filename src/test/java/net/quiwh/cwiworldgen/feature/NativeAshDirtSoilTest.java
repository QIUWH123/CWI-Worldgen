package net.quiwh.cwiworldgen.feature;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises the distribution's loaded vanilla disk, including its real replacement predicate. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeAshDirtSoilTest {
    private static final int HALF = 6, LOW = -5, HIGH = 4;

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void loadedAshDiskPreservesRockAndProtectedBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ConfiguredFeature<?, ?> feature = configured(helper);
        BlockState depleted = registered(helper, "kubejs:depleted_dirt");
        BlockState ashen = registered(helper, "kubejs:ashen_depleted_dirt");
        BlockState ash = registered(helper, "kubejs:ash_dirt");
        BlockPos origin = helper.absolutePos(new BlockPos(8, 12, 8));
        List<BlockState> materials = List.of(Blocks.STONE.defaultBlockState(), depleted, ashen,
                Blocks.GRANITE.defaultBlockState(), Blocks.TUFF.defaultBlockState(),
                Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.CHEST.defaultBlockState(),
                Blocks.STONE_BRICKS.defaultBlockState());
        int protectedChecks = 0, soilChanges = 0;
        Set<Integer> radii = new HashSet<>();
        for (long seed = 0; seed < 24; seed++) {
            radii.add(radius(feature, seed));
            for (int x = -HALF; x <= HALF; x++) for (int z = -HALF; z <= HALF; z++)
                for (int y = LOW; y <= HIGH; y++) {
                    BlockState material = y > 0 ? Blocks.AIR.defaultBlockState()
                            : materials.get(Math.floorMod(x + 2 * z, materials.size()));
                    level.setBlock(origin.offset(x, y, z), material, 2);
                }
            feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), origin);
            for (int x = -HALF; x <= HALF; x++) for (int z = -HALF; z <= HALF; z++)
                for (int y = LOW; y <= HIGH; y++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState before = y > 0 ? Blocks.AIR.defaultBlockState()
                            : materials.get(Math.floorMod(x + 2 * z, materials.size()));
                    BlockState after = level.getBlockState(pos);
                    if (before == depleted || before == ashen) {
                        helper.assertTrue(after == before || after == ash,
                                "Ash disk introduced an unrelated soil material at " + pos);
                        if (after == ash) soilChanges++;
                    } else {
                        helper.assertTrue(after == before,
                                "Ash disk replaced protected rock/non-soil: " + before
                                        + " became " + after + " at " + pos + " seed=" + seed);
                        protectedChecks++;
                    }
                }
        }
        helper.assertTrue(soilChanges > 10, "Mixed rock/soil fixture never placed ash dirt on allowed soils");
        helper.assertTrue(radii.equals(Set.of(1, 2, 3, 4, 5)), "Seeded native checks missed a configured disk radius: " + radii);
        System.out.println("CWI_ASH_DIRT_PROTECTED=seeds:24,radii:" + radii
                + ",protected_checks:" + protectedChecks + ",allowed_soil_changes:" + soilChanges);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void bothDepletedSoilsRetainSeededDeterministicAshPatches(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ConfiguredFeature<?, ?> feature = configured(helper);
        BlockState ash = registered(helper, "kubejs:ash_dirt");
        BlockPos origin = helper.absolutePos(new BlockPos(8, 12, 8));
        int placements = 0, changedBlocks = 0;
        Set<Integer> radii = new HashSet<>();
        Set<Integer> footprintSizes = new HashSet<>();
        for (String soilId : List.of("kubejs:depleted_dirt", "kubejs:ashen_depleted_dirt")) {
            BlockState soil = registered(helper, soilId);
            for (long seed = 0; seed < 24; seed++) {
                radii.add(radius(feature, seed));
                List<BlockState> first = null;
                for (int repetition = 0; repetition < 2; repetition++) {
                    for (int x = -HALF; x <= HALF; x++) for (int z = -HALF; z <= HALF; z++)
                        for (int y = LOW; y <= HIGH; y++)
                            level.setBlock(origin.offset(x, y, z), y > 0 ? Blocks.AIR.defaultBlockState() : soil, 2);
                    boolean placed = feature.place(level, level.getChunkSource().getGenerator(),
                            RandomSource.create(seed), origin);
                    helper.assertTrue(placed, "Loaded ash disk rejected allowed soil " + soilId + " seed=" + seed);
                    var actual = new ArrayList<BlockState>();
                    int count = 0;
                    for (int x = -HALF; x <= HALF; x++) for (int z = -HALF; z <= HALF; z++)
                        for (int y = LOW; y <= HIGH; y++) {
                            BlockPos pos = origin.offset(x, y, z);
                            BlockState state = level.getBlockState(pos);
                            actual.add(state);
                            if (y > 0) helper.assertTrue(state.isAir(), "Ash disk replaced air at " + pos);
                            else helper.assertTrue(state == soil || state == ash,
                                    "Ash disk introduced an unrelated block on " + soilId + " at " + pos);
                            if (state == ash) {
                                count++;
                                helper.assertTrue(x * x + z * z <= 25 && y >= -4,
                                        "Ash disk escaped its unchanged configured extent at " + pos);
                            }
                        }
                    helper.assertTrue(count > 0, "Ash patch placed no blocks on " + soilId + " seed=" + seed);
                    if (repetition == 0) {
                        first = actual;
                        footprintSizes.add(count);
                        changedBlocks += count;
                    } else helper.assertTrue(actual.equals(first),
                            "Repeated native ash placement changed for identical seed/soil: " + seed + "/" + soilId);
                    placements++;
                }
            }
        }
        helper.assertTrue(radii.equals(Set.of(1, 2, 3, 4, 5)) && footprintSizes.size() == 5,
                "Deterministic checks did not cover all five native radii: " + radii + "/" + footprintSizes);
        System.out.println("CWI_ASH_DIRT_SOILS=materials:2,seeds_each:24,placements:" + placements
                + ",radii:" + radii + ",distinct_footprints:" + footprintSizes.size()
                + ",changed_blocks:" + changedBlocks + ",repeat_determinism:true");
        helper.succeed();
    }

    private static ConfiguredFeature<?, ?> configured(GameTestHelper helper) {
        ConfiguredFeature<?, ?> feature = helper.getLevel().registryAccess()
                .registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getHolderOrThrow(ResourceKey.create(Registries.CONFIGURED_FEATURE,
                        ResourceLocation.parse("cwi:stones/ash_dirt"))).value();
        helper.assertTrue(feature.feature() == Feature.DISK && feature.config() instanceof DiskConfiguration,
                "Expected the real loaded vanilla disk feature");
        return feature;
    }

    private static int radius(ConfiguredFeature<?, ?> feature, long seed) {
        return ((DiskConfiguration) feature.config()).radius().sample(RandomSource.create(seed));
    }

    private static BlockState registered(GameTestHelper helper, String id) {
        BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
        helper.assertTrue(!state.isAir(), "Missing fixture material " + id);
        return state;
    }
}

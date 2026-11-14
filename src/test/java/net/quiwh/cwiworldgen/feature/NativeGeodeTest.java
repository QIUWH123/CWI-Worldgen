package net.quiwh.cwiworldgen.feature;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.feature.geode.CrystalSpikeFeature;
import net.quiwh.cwiworldgen.feature.geode.EchoCrystalSpikeFeature;
import net.quiwh.cwiworldgen.feature.geode.config.GeodeCrystalSpikeConfig;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeGeodeTest {
    @GameTest(template = "empty")
    public static void spikeCodecRetainsStatesRadiusAndDirection(GameTestHelper helper) {
        var json = JsonParser.parseString("""
            {"crystal_state":{"Name":"minecraft:quartz_block"},
             "cluster_state":{"Name":"minecraft:amethyst_cluster","Properties":{"facing":"south","waterlogged":"true"}},
             "xz_radius":{"type":"minecraft:uniform","min_inclusive":1,"max_inclusive":3},
             "crystal_direction":"floor"}
            """);
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var config = GeodeCrystalSpikeConfig.CODEC.parse(ops, json).getOrThrow();
        helper.assertTrue(config.crystal_state.is(Blocks.QUARTZ_BLOCK), "crystal state lost its configured block");
        helper.assertTrue(config.cluster_state.getValue(net.minecraft.world.level.block.AmethystClusterBlock.FACING) == Direction.SOUTH,
                "cluster direction did not survive codec loading");
        helper.assertTrue(config.cluster_state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED),
                "cluster water did not survive codec loading");
        helper.assertTrue(config.crystal_direction == CaveSurface.FLOOR, "floor direction changed");
        helper.assertTrue(config.xzRadius.getMinValue() == 1 && config.xzRadius.getMaxValue() == 3, "radius distribution changed");
        helper.assertTrue(GeodeCrystalSpikeConfig.CODEC.parse(ops,
                GeodeCrystalSpikeConfig.CODEC.encodeStart(ops, config).getOrThrow()).getOrThrow().cluster_state == config.cluster_state,
                "state codec round trip changed cluster state");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void floorSpikesPreserveOriginalDiscreteSlope(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(5, 8, 5));
        BlockPos.betweenClosed(origin.offset(-3, -3, -3), origin.offset(5, 5, 5))
                .forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2));
        var ordinary = new CrystalSpikeFeature(GeodeCrystalSpikeConfig.CODEC);
        var echo = new EchoCrystalSpikeFeature(GeodeCrystalSpikeConfig.CODEC);
        var ordinaryPositions = new HashSet<BlockPos>();
        var echoPositions = new HashSet<BlockPos>();
        helper.assertTrue(ordinary.placeSpike(level, origin, 1, 4, 2, ordinaryPositions, Direction.DOWN, RandomSource.create(4)),
                "floor crystal geometry did not find empty placement positions");
        helper.assertTrue(echo.placeSpike(level, origin, 1, 4, 2, echoPositions, Direction.DOWN, RandomSource.create(4)),
                "floor echo geometry did not find empty placement positions");
        helper.assertTrue(ordinaryPositions.equals(echoPositions), "echo and ordinary spike shapes diverged");
        helper.assertTrue(ordinaryPositions.size() == 12, "original one-radius four-layer spike must contain twelve positions");
        helper.assertTrue(ordinaryPositions.contains(origin.offset(2, 3, 1)), "original slanted tip position changed");
        helper.assertTrue(ordinaryPositions.contains(origin.offset(1, 2, 0)), "native sine truncation at layer two changed");
        helper.assertTrue(!ordinaryPositions.contains(origin.offset(1, 2, 1)), "spike rounded an originally truncated offset");
        level.setBlock(origin.offset(2, 3, 1), Blocks.BEDROCK.defaultBlockState(), 2);
        ordinaryPositions.clear();
        ordinary.placeSpike(level, origin, 1, 4, 2, ordinaryPositions, Direction.DOWN, RandomSource.create(4));
        helper.assertTrue(ordinaryPositions.size() == 11 && !ordinaryPositions.contains(origin.offset(2, 3, 1)),
                "solid blocks must remove obstructed spike positions");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ceilingSpikesRejectMissingSurface(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(5, 5, 5));
        BlockPos.betweenClosed(origin.offset(-2, 0, -2), origin.offset(2, 12, 2))
                .forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2));
        var positions = new HashSet<BlockPos>();
        helper.assertTrue(!new CrystalSpikeFeature(GeodeCrystalSpikeConfig.CODEC)
                        .placeSpike(level, origin, 1, 4, 2, positions, Direction.UP, RandomSource.create(4)),
                "a ceiling spike must fail when its ten-block surface search stays empty");
        helper.assertTrue(positions.isEmpty(), "failed surface search must not leave spike positions");
        helper.succeed();
    }
}

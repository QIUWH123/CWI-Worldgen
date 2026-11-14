package net.quiwh.cwiworldgen.feature;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeSceneryRockSupportTest {
    @GameTest(template = "empty")
    public static void modernSceneryAcceptsSupportedLocalGravelAndCobbleButRejectsHollowGravelAtomically(GameTestHelper helper) {
        BlockState gravel = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:granite_gravel")).defaultBlockState();
        BlockState cobble = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:cobbled_granite")).defaultBlockState();
        helper.assertTrue(gravel.getBlock() instanceof FallingBlock && !cobble.isAir(), "Native rock variants are absent");
        var config = new SafeDeadwoodFeature.Config(Blocks.OAK_LOG.defaultBlockState(), SafeDeadwoodFeature.Kind.FALLEN, 4, 4, false);
        BlockPos origin = new BlockPos(-24, 65, 56); // Chunk(-2,3), local(8,8).
        for (int scenario = 0; scenario < 6; scenario++) {
            BlockState material = scenario >= 4 ? Blocks.GRAVEL.defaultBlockState() : scenario == 2 ? cobble : gravel;
            var chunk = fixture(helper, material, scenario != 3 && scenario != 5);
            int version = scenario == 0 || scenario == 5 ? 11 : 12;
            var before = snapshot(chunk);
            var random = RandomSource.create(42);
            boolean placed = SafeDeadwoodFeature.placeChunk(chunk, origin, random, config, position -> true, version);
            boolean expected = scenario == 1 || scenario == 2 || scenario >= 4;
            helper.assertTrue(placed == expected, "Scenery ignored supported local material/profile boundary in scenario " + scenario);
            if (expected) {
                int logs = 0;
                for (var entry : snapshot(chunk).entrySet()) if (entry.getValue().is(Blocks.OAK_LOG)) {
                    logs++;
                    helper.assertTrue(chunk.getBlockState(entry.getKey().below()).equals(material),
                            "Fallen wood lost direct local material support");
                }
                helper.assertTrue(logs == 4, "Supported material changed the configured fallen-log shape");
            } else helper.assertTrue(before.equals(snapshot(chunk)), "Rejected hollow/legacy support left partial scenery");
            var expectedRandom = RandomSource.create(42);
            expectedRandom.nextInt(1);
            net.minecraft.core.Direction.Plane.HORIZONTAL.getRandomDirection(expectedRandom);
            helper.assertTrue(random.nextLong() == expectedRandom.nextLong(), "Support resolution consumed feature randomness");
        }
        var supported = fixture(helper, gravel, true);
        for (int y = 65; y <= 67; y++) supported.setBlockState(new BlockPos(-22, y, 56), Blocks.OAK_LOG.defaultBlockState(), false);
        prime(supported);
        helper.assertTrue(SafeDeadwoodFeature.hasStandingTrunkNearby(supported, origin), "Supported trunk over local gravel did not shelter leaves");
        supported.setBlockState(new BlockPos(-22, 62, 56), Blocks.AIR.defaultBlockState(), false);
        helper.assertTrue(!SafeDeadwoodFeature.hasStandingTrunkNearby(supported, origin), "Hollow falling gravel supplied false trunk shelter");
        helper.succeed();
    }

    private static ProtoChunk fixture(GameTestHelper helper, BlockState top, boolean stable) {
        var chunk = new ProtoChunk(new ChunkPos(-2, 3), UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
        for (int x = -32; x < -16; x++) for (int z = 48; z < 64; z++) {
            chunk.setBlockState(new BlockPos(x, 64, z), top, false);
            chunk.setBlockState(new BlockPos(x, 63, z), top, false);
            if (stable) chunk.setBlockState(new BlockPos(x, 62, z), Blocks.GRANITE.defaultBlockState(), false);
        }
        prime(chunk);
        return chunk;
    }
    private static void prime(ProtoChunk chunk) {
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
    }
    private static Map<BlockPos, BlockState> snapshot(ProtoChunk chunk) {
        var result = new LinkedHashMap<BlockPos, BlockState>();
        for (int x = -32; x < -16; x++) for (int z = 48; z < 64; z++) for (int y = 62; y <= 67; y++) {
            var pos = new BlockPos(x, y, z); result.put(pos, chunk.getBlockState(pos));
        }
        return result;
    }
}

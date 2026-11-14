package net.quiwh.cwiworldgen.carver;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;

/** Native ellipsoid carving follows stored chunk biomes before claiming mask bits. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeBiomeCarverTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 1200)
    public static void targetedEllipsoidsPreserveOtherBiomesAndTheirMaskBits(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, "wasteland_test")));
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var sandy = biomes.getHolderOrThrow(ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("cwi", "sandy_caves")));
        var molten = biomes.getHolderOrThrow(ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("cwi", "molten_caves")));
        var carvers = level.registryAccess().registryOrThrow(Registries.CONFIGURED_CARVER);
        var cave = (TargetedCaveConfiguration) carvers.get(ResourceLocation.fromNamespaceAndPath(
                "cwi", "sandy_caves")).config();
        var canyon = (TargetedCanyonConfiguration) carvers.get(ResourceLocation.fromNamespaceAndPath(
                "cwi", "molten_caves_ravine")).config();
        var chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, level, biomes, null);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 20; y < 44; y++) {
            chunk.setBlockState(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), false);
        }
        var context = new CarvingContext(generator, level.registryAccess(), level, null,
                level.getChunkSource().randomState(), generator.generatorSettings().value().surfaceRule());
        chunk.fillBiomesFromNoise((qx, qy, qz, sampler) -> qx < 2 ? sandy : molten, context.randomState().sampler());
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        var mask = new CarvingMask(level.getHeight(), level.getMinBuildHeight());
        var aquifer = Aquifer.createDisabled((x, y, z) -> new Aquifer.FluidStatus(-64, Blocks.LAVA.defaultBlockState()));
        // Deliberately disagree with the stored left half: eligibility must use
        // the chunk's native four-block biome cells, not a fresh zoomed lookup.
        Function<BlockPos, Holder<Biome>> actualBiomes = pos -> molten;
        helper.assertTrue(ChunkBiomeCarvingCache.get(context, chunk).mayContain(cave.biome),
                "Chunk palette summary missed sandy cells");
        var absent = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, level, biomes, null);
        absent.fillBiomesFromNoise((qx, qy, qz, sampler) -> molten, context.randomState().sampler());
        absent.setPersistedStatus(ChunkStatus.BIOMES);
        helper.assertTrue(!ChunkBiomeCarvingCache.get(context, absent).mayContain(cave.biome),
                "Palette cache reused another chunk with identical coordinates");
        helper.assertTrue(!new BiomeRestrictedCaveCarver().carve(context, cave, absent, actualBiomes,
                RandomSource.create(987L), aquifer, absent.getPos(), mask), "Absent biome did not skip tunnel work");
        helper.assertTrue(ChunkBiomeCarvingCache.get(context, chunk).mayContain(cave.biome),
                "Palette cache failed to restore original chunk");
        new BiomeRestrictedCaveCarver().carveEllipsoid(context, cave, chunk, actualBiomes, aquifer,
                8., 32., 8., 6., 6., mask, (ctx, x, y, z, blockY) -> x * x + y * y + z * z >= 1.);
        helper.assertTrue(chunk.getBlockState(new BlockPos(6, 32, 8)).isAir(), "Matching sandy block was not carved");
        helper.assertTrue(chunk.getBlockState(new BlockPos(10, 32, 8)).is(Blocks.STONE), "Sandy shape leaked into molten biome");
        helper.assertTrue(!mask.get(10, 32, 8), "Rejected biome claimed a carving mask bit");
        new BiomeRestrictedCanyonCarver().carveEllipsoid(context, canyon, chunk, actualBiomes, aquifer,
                8., 32., 8., 6., 6., mask, (ctx, x, y, z, blockY) -> x * x + y * y + z * z >= 1.);
        helper.assertTrue(chunk.getBlockState(new BlockPos(10, 32, 8)).isAir(), "Later molten shape was blocked by another biome");
        helper.assertTrue(mask.get(10, 32, 8), "Matching molten block did not claim its mask");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void normalizedCandidateCoordinatesRoundTripAtNegativeAndPositiveEdges(GameTestHelper helper) {
        for (double center : new double[]{-10000.7, -16., -.1, 0., 16.3, 10000.7}) {
            for (double radius : new double[]{.7, 3., 8.3, 50.}) {
                for (int x = (int) center - 50; x <= (int) center + 50; x++) {
                    double delta = (x + .5 - center) / radius;
                    var recovered = BiomeCarvingGate.candidatePosition(center, center, radius, delta, delta, -37);
                    helper.assertTrue(recovered.equals(new BlockPos(x, -37, x)), "Candidate coordinate inversion changed voxel");
                }
            }
        }
        helper.succeed();
    }

    private NativeBiomeCarverTest() {}
}

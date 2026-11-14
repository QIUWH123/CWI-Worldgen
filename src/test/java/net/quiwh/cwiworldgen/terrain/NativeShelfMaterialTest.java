package net.quiwh.cwiworldgen.terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

/** Fixed native shelf fixtures; no discovery scan or mutable neighbor generation. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeShelfMaterialTest {
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void admittedShelfRetainsItsPhaseWithoutRetuningHistoricalGeometry(GameTestHelper helper) {
        try (var prior = new ReferenceTerrain(0, 18); var next = new ReferenceTerrain(0, 19)) {
            var oldCell = prior.sample(-501, -504);
            var cell = next.sample(-501, -504);
            helper.assertTrue(Float.floatToRawIntBits(oldCell.height) == Float.floatToRawIntBits(cell.height),
                    "Shelf material handoff changed native relief");
            helper.assertTrue(oldCell.cwiRockShelf == null && cell.cwiRockShelf != null,
                    "Shelf material phase escaped its generation profile or missed the native fixture");
            var shelf = cell.cwiRockShelf;
            helper.assertTrue(shelf.retainedAt(cell.height) && shelf.geometryCut() > 0
                            && !cell.cwiPlannedWater && !cell.cwiPondProtected,
                    "Native shelf phase escaped its admitted dry floor");
            helper.assertTrue(shelf.equals(next.sample(-501, -504).cwiRockShelf),
                    "Detached native sample dropped immutable shelf phase");
            cell.reset();
            helper.assertTrue(cell.cwiRockShelf == null, "Reset terrain cell retained a stale shelf phase");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void nativeSurfaceUsesShelfPhaseAndPreservesProtectedBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        helper.assertTrue(level != null, "Missing production-equivalent test dimension");
        var generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        var state = level.getChunkSource().randomState();
        var terrain = ((TerrainRandomState) (Object) state).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null && terrain.generationVersion() >= 19,
                "Shelf material native fixture requires the new saved generation profile");
        int[] tile = fixedTile(level.getSeed());
        int minX = tile[0] * 128, minZ = tile[1] * 128;
        var levels = terrain.levels();
        CliffBands.Heights heights = (x, z) -> levels.scale(terrain.sample(x, z).height);
        Map<ChunkPos, Integer> scores = new HashMap<>();
        for (int z = minZ + 8; z < minZ + 120; z++) for (int x = minX + 8; x < minX + 120; x++) {
            var shelf = terrain.sample(x, z).cwiRockShelf;
            if (shelf == null || shelf.outlineSupport() < .75 || shelf.winnerMargin() < .4) continue;
            var exposure = CliffBands.exposureAt(x, z, heights);
            if (!exposure.eligible()) continue;
            scores.merge(new ChunkPos(x >> 4, z >> 4), exposure.topY() - exposure.visibleMinY() + 1, Integer::sum);
        }
        var candidates = new ArrayList<>(scores.entrySet());
        candidates.sort(Map.Entry.<ChunkPos, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparingInt(entry -> entry.getKey().x).thenComparingInt(entry -> entry.getKey().z));
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        var independent = new CliffBands(terrain.seed());
        int built = 0, checked = 0, distinguished = 0, preserved = 0;
        for (var candidate : candidates) {
            if (built == 4) break;
            var chunk = new ProtoChunk(candidate.getKey(), UpgradeData.EMPTY, level, biomes, null);
            complete(level, generator.createBiomes(state, Blender.empty(), level.structureManager(), chunk));
            chunk.setPersistedStatus(ChunkStatus.BIOMES);
            complete(level, generator.fillFromNoise(Blender.empty(), state, level.structureManager(), chunk));
            chunk.setPersistedStatus(ChunkStatus.NOISE);
            var region = new IsolatedRegion(level, chunk);
            ReferenceRiverSurface.place(chunk, region.getBiomeManager(), terrain);
            List<BlockPos> targets = new ArrayList<>(), protectedPositions = new ArrayList<>();
            List<BlockState> expected = new ArrayList<>();
            List<Boolean> changes = new ArrayList<>();
            List<BlockState> sentinels = List.of(Blocks.DIAMOND_ORE.defaultBlockState(), Blocks.CHEST.defaultBlockState(),
                    Blocks.STONE_BRICKS.defaultBlockState(), Blocks.WATER.defaultBlockState());
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int gx = chunk.getPos().getBlockX(x), gz = chunk.getPos().getBlockZ(z);
                var shelf = terrain.sample(gx, gz).cwiRockShelf;
                if (shelf == null) continue;
                var exposure = CliffBands.exposureAt(gx, gz, heights);
                for (int y = Math.max(level.getMinBuildHeight(), exposure.visibleMinY());
                     y < Math.min(level.getMaxBuildHeight(), exposure.topY() - 2); y++) {
                    var pos = new BlockPos(gx, y, gz);
                    if (!chunk.getBlockState(pos).is(Blocks.STONE)) continue;
                    String biome = chunk.getNoiseBiome(gx >> 2, y >> 2, gz >> 2).unwrapKey()
                            .orElseThrow().location().toString();
                    BlockState material = CliffRockRule.material(ShelfCliffMaterials.materialAt(biome, y, exposure, shelf));
                    if (material == null) continue;
                    if (protectedPositions.size() < sentinels.size()) {
                        chunk.setBlockState(pos, sentinels.get(protectedPositions.size()), false);
                        protectedPositions.add(pos);
                    } else {
                        targets.add(pos); expected.add(material);
                        changes.add(material != CliffRockRule.material(independent.materialAt(biome, gx, y, gz, exposure)));
                    }
                }
            }
            generator.buildSurface(chunk, new WorldGenerationContext(generator, level), state,
                    level.structureManager(), region.getBiomeManager(), biomes, Blender.empty());
            built++;
            for (int i = 0; i < protectedPositions.size(); i++) {
                helper.assertTrue(chunk.getBlockState(protectedPositions.get(i)) == sentinels.get(i),
                        "Shelf material damaged a non-default block at " + protectedPositions.get(i));
                preserved++;
            }
            for (int i = 0; i < targets.size(); i++) {
                BlockState actual = chunk.getBlockState(targets.get(i));
                // The earlier soil rule owns real density ledges.
                if (!actual.is(Blocks.STONE) && !actual.is(Blocks.TUFF) && !actual.is(Blocks.ANDESITE)) continue;
                helper.assertTrue(actual == expected.get(i), "Native cliff material ignored shelf phase at " + targets.get(i));
                checked++;
                if (changes.get(i)) distinguished++;
            }
            if (checked >= 16 && distinguished >= 4 && preserved >= 4) break;
        }
        System.out.println("CWI_SHELF_MATERIAL_NATIVE=seed:" + level.getSeed() + ",chunks:" + built
                + ",checked:" + checked + ",phase_distinguished:" + distinguished + ",protected:" + preserved);
        helper.assertTrue(checked >= 16 && distinguished >= 4 && preserved >= 4,
                "Fixed bounded native shelf fixture did not establish actual stored-biome phase materials");
        helper.succeed();
    }

    private static int[] fixedTile(long seed) {
        if (seed == 0) return new int[]{-1, 7};
        if (seed == 42) return new int[]{18, 8};
        if (seed == 123456789) return new int[]{1, -8};
        if (seed == -987654321) return new int[]{-8, -23};
        throw new AssertionError("Native shelf fixture supports only the four declared survey seeds");
    }

    private static void complete(ServerLevel level, CompletableFuture<?> future) {
        level.getServer().managedBlock(() -> { level.getChunkSource().pollTask(); return future.isDone(); });
        future.join();
    }

    private static final class IsolatedRegion extends WorldGenRegion {
        private final ServerLevel level;
        private final ChunkAccess chunk;
        IsolatedRegion(ServerLevel level, ChunkAccess chunk) {
            super(level, null, null, chunk); this.level = level; this.chunk = chunk;
        }
        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            if (x == chunk.getPos().x && z == chunk.getPos().z) return chunk;
            if (required) throw new AssertionError("Shelf rule requested mutable neighbor " + x + "," + z);
            return null;
        }
        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) { return level.getUncachedNoiseBiome(x, y, z); }
    }
}

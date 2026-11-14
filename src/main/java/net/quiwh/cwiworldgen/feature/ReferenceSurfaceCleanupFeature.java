package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

/** Late unsupported-foliage and profile-gated aquatic-fragment follow-up. */
public final class ReferenceSurfaceCleanupFeature extends Feature<NoneFeatureConfiguration> {
    private static final Set<String> FOLIAGE = Set.of(
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
            "minecraft:dead_bush", "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
            "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip",
            "minecraft:white_tulip", "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower",
            "minecraft:lily_of_the_valley", "cwi_worldgen:dry_sprouts", "cwi_worldgen:mossy_sprouts");

    public ReferenceSurfaceCleanupFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!((Object) context.level().getLevel().getChunkSource().randomState() instanceof TerrainRandomState state)
                || state.cwiWorldgen$terrain() == null) return false;
        var terrain = state.cwiWorldgen$terrain();
        ChunkPos chunkPos = new ChunkPos(context.origin());
        var reader = terrain.prepareChunk(chunkPos.x, chunkPos.z);
        var levels = terrain.levels();
        var chunk = context.level().getChunk(chunkPos.x, chunkPos.z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean changed = terrain.generationVersion() >= 14 && WaterbedSedimentFeature.cleanup(context);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            var cell = reader.getCell(x, z);
            if (cell.terrain.isWetland() || cell.terrain.isRiver() || cell.terrain.isLake()
                    || cell.terrain.isDeepOcean() || cell.terrain.isShallowOcean()) continue;
            int groundY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (groundY > levels.scale(cell.height)) continue;
            // FEATURES updates final heightmaps; the WG map still describes pre-decoration ground.
            int highest = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            for (int y = Math.max(chunk.getMinBuildHeight(), groundY + 1);
                    y <= Math.min(chunk.getMaxBuildHeight() - 1, Math.min(highest, groundY + 8)); y++) {
                pos.set(chunkPos.getBlockX(x), y, chunkPos.getBlockZ(z));
                changed |= removeUnsupported(context.level(), pos);
            }
        }
        return changed;
    }

    static boolean removeUnsupported(WorldGenLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!FOLIAGE.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()) || state.canSurvive(level, pos)) return false;
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        return true;
    }
}

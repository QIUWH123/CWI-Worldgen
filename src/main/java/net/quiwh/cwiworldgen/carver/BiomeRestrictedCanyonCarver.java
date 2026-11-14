package net.quiwh.cwiworldgen.carver;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import org.apache.commons.lang3.mutable.MutableBoolean;

/** Vanilla canyon shape follows stored four-block biome cells.
 * Display BiomeManager Voronoi borders can differ by a few blocks; generation
 * uses the native chunk palette to avoid repeated expensive climate sampling.
 */
public final class BiomeRestrictedCanyonCarver extends CanyonWorldCarver {
    public BiomeRestrictedCanyonCarver() {
        super(TargetedCanyonConfiguration.CODEC.xmap(value -> (CanyonCarverConfiguration) value,
                value -> (TargetedCanyonConfiguration) value));
    }

    @Override
    public boolean carve(CarvingContext context, CanyonCarverConfiguration config,
            ChunkAccess chunk, Function<BlockPos, Holder<Biome>> biomes, RandomSource random,
            Aquifer aquifer, ChunkPos origin, CarvingMask mask) {
        if (!ChunkBiomeCarvingCache.get(context, chunk).mayContain(((TargetedCanyonConfiguration) config).biome)) return false;
        return super.carve(context, config, chunk, biomes, random, aquifer, origin, mask);
    }

    @Override
    protected boolean carveEllipsoid(CarvingContext context, CanyonCarverConfiguration config,
            ChunkAccess chunk, Function<BlockPos, Holder<Biome>> biomes, Aquifer aquifer,
            double x, double y, double z, double horizontalRadius, double verticalRadius,
            CarvingMask mask, CarveSkipChecker skip) {
        var targeted = (TargetedCanyonConfiguration) config;
        return super.carveEllipsoid(context, config, chunk, biomes, aquifer, x, y, z,
                horizontalRadius, verticalRadius, mask,
                BiomeCarvingGate.restrict(skip, targeted.biome,
                        pos -> chunk.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2),
                        x, z, horizontalRadius));
    }

    @Override
    protected boolean carveBlock(CarvingContext context, CanyonCarverConfiguration config,
            ChunkAccess chunk, Function<BlockPos, Holder<Biome>> biomes, CarvingMask mask,
            BlockPos.MutableBlockPos position, BlockPos.MutableBlockPos below,
            Aquifer aquifer, MutableBoolean reachedSurface) {
        if (!chunk.getNoiseBiome(position.getX() >> 2, position.getY() >> 2, position.getZ() >> 2)
                .is(((TargetedCanyonConfiguration) config).biome)) return false;
        return super.carveBlock(context, config, chunk, biomes, mask, position, below, aquifer, reachedSurface);
    }
}

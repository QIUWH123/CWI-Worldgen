package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

/**
 * Chunk-contained mineral lenses with mineral-specific aggregate pockets. Horizontal
 * recentering deliberately keeps the complete lens in its decoration chunk;
 * generation order cannot clip a neighbor's deposit or write outside the region.
 */
public final class IndustrialDepositFeature extends Feature<IndustrialDepositConfiguration> {
    private static final TagKey<Block> PROSPECTING_SURFACE = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "prospecting_surface"));
    public IndustrialDepositFeature(Codec<IndustrialDepositConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<IndustrialDepositConfiguration> context) {
        var config = context.config();
        var level = context.level();
        var origin = context.origin();
        int centerX = (origin.getX() & ~15) + 8;
        int centerZ = (origin.getZ() & ~15) + 8;
        float scale = config.sizeSettings().scale(context.random(), level.getBiome(origin));
        int radiusX = Math.max(2, Math.round(config.radiusX() * scale));
        int radiusY = Math.max(2, Math.round(config.radiusY() * scale));
        int radiusZ = Math.max(2, Math.round(config.radiusZ() * scale));
        long seed = context.random().nextLong();
        List<Aggregate> aggregates = aggregates(config, radiusX, radiusY, radiusZ, RandomSource.create(seed));
        List<Candidate> candidates = new ArrayList<>();

        for (int dx = -radiusX; dx <= radiusX; dx++) {
            for (int dy = -radiusY; dy <= radiusY; dy++) {
                for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                    double distance = square(dx / (double) radiusX)
                        + square(dy / (double) radiusY) + square(dz / (double) radiusZ);
                    double edge = 0.87 + 0.13 * unit(seed, Math.floorDiv(dx, 2), Math.floorDiv(dy, 2), Math.floorDiv(dz, 2));
                    if (distance > edge) continue;
                    BlockPos pos = new BlockPos(centerX + dx, origin.getY() + dy, centerZ + dz);
                    if (level.isOutsideBuildHeight(pos) || !level.ensureCanWrite(pos)) continue;
                    BlockState previous = level.getBlockState(pos);
                    if (!previous.is(config.replaceable()) || previous.isAir()
                            || !previous.getFluidState().isEmpty() || previous.hasBlockEntity()) continue;

                    int pocketIndex = 0;
                    double score = Double.POSITIVE_INFINITY;
                    for (int index = 0; index < aggregates.size(); index++) {
                        Aggregate aggregate = aggregates.get(index);
                        double pocketScore = aggregate.shape.score(dx, dy, dz);
                        if (pocketScore < score) {
                            score = pocketScore;
                            pocketIndex = index;
                        }
                    }
                    candidates.add(new Candidate(pos, dx, dy, dz, score, pocketIndex));
                }
            }
        }

        if (candidates.isEmpty()) return false;
        int oreBlocks = Math.round(candidates.size() * config.oreFraction());
        var orePositions = IndustrialAggregateGrowth.select(candidates.stream()
            .map(candidate -> new IndustrialAggregateGrowth.Voxel(candidate.coordinate(), candidate.score, candidate.pocket)).toList(), oreBlocks);
        int placed = 0;
        int top = level.getMinBuildHeight();
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            IndustrialDepositConfiguration.Material material;
            if (orePositions.contains(candidate.coordinate())) {
                material = aggregates.get(candidate.pocket).material;
            } else {
                int layer = Math.floorDiv(candidate.dy + (int) Math.round(1.7 * Math.sin(candidate.dx * 0.45)
                    + 1.3 * Math.sin(candidate.dz * 0.4)), 3);
                material = select(config.hosts(), unit(seed ^ 0x484F5354L, layer, 0, 0));
            }
            if (level.setBlock(candidate.pos, material.atDepth(candidate.pos.getY()), 2)) {
                placed++;
                top = Math.max(top, candidate.pos.getY());
            }
        }
        if (config.surfaceIndicators() && placed >= 64 && unit(seed ^ 0x50524F5350454354L, 0, 0, 0) < 0.5)
            placeSurfaceIndicator(level, config, centerX, centerZ, top, seed);
        return placed > 0;
    }

    /** A modest clue above a real shallow deposit, never an independent resource marker. */
    private static void placeSurfaceIndicator(WorldGenLevel level, IndustrialDepositConfiguration config,
            int centerX, int centerZ, int lensTop, long seed) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerX, centerZ) - 1;
        if (surfaceY < lensTop || surfaceY - lensTop > 40) return;
        BlockPos center = new BlockPos(centerX, surfaceY, centerZ);
        if (!validSurface(level, center)) return;
        BlockState mineral = config.hosts().getFirst().atDepth(surfaceY);
        level.setBlock(center, mineral, 2);
        int extras = (int) (unit(seed ^ 0x4F555443524F50L, 0, 0, 0) * 3);
        if (extras >= 1 && level.ensureCanWrite(center.above())) level.setBlock(center.above(), mineral, 2);
        if (extras == 2) {
            // A low adjacent block makes a small outcrop instead of a tall marker pillar.
            BlockPos neighbor = unit(seed, 17, 0, 31) < 0.5 ? center.east() : center.south();
            if (validSurface(level, neighbor)) level.setBlock(neighbor, mineral, 2);
        }
    }

    private static boolean validSurface(WorldGenLevel level, BlockPos ground) {
        if (level.isOutsideBuildHeight(ground.above()) || !level.ensureCanWrite(ground)
                || !level.ensureCanWrite(ground.above())) return false;
        BlockState state = level.getBlockState(ground);
        return state.is(PROSPECTING_SURFACE) && state.getFluidState().isEmpty() && !state.hasBlockEntity()
            && level.getBlockState(ground.above()).isAir();
    }

    private static List<Aggregate> aggregates(IndustrialDepositConfiguration config, int radiusX, int radiusY,
            int radiusZ, RandomSource random) {
        List<Aggregate> result = new ArrayList<>();
        // A few separated nuclei keep the reduced budget concentrated. Each nucleus
        // gets independent orientation, proportions, daughters and chipped edges.
        int count = config.oreFraction() <= .06F ? 2 + random.nextInt(2) : 2;
        for (int index = 0; index < count; index++) {
            int x = (int) Math.round((random.nextDouble() * 2 - 1) * radiusX * .2);
            int y = (int) Math.round(((index / (double) (count - 1)) * 2 - 1) * radiusY * .5);
            int z = (int) Math.round((random.nextDouble() * 2 - 1) * radiusZ * .2);
            if (count == 3 && index == 1) {
                x = (random.nextBoolean() ? 1 : -1) * Math.max(1, (int) Math.round(radiusX * .45));
                z = -x;
            }
            String family = config.morphologies().get(random.nextInt(config.morphologies().size()));
            result.add(new Aggregate(new IndustrialAggregateShape(family, x, y, z, random.nextLong()),
                select(config.ores(), random.nextDouble())));
        }
        return result;
    }

    private static IndustrialDepositConfiguration.Material select(List<IndustrialDepositConfiguration.Material> palette, double value) {
        int total = palette.stream().mapToInt(IndustrialDepositConfiguration.Material::weight).sum();
        int chosen = (int) (value * total);
        for (var material : palette) {
            chosen -= material.weight();
            if (chosen < 0) return material;
        }
        return palette.getLast();
    }

    private static double square(double value) { return value * value; }

    /** Stable coordinate mixing, independent of terrain eligibility and traversal. */
    private static double unit(long seed, int x, int y, int z) {
        long mixed = seed ^ (x * 0x632BE59BD9B4E019L) ^ (y * 0x9E3779B97F4A7C15L) ^ (z * 0x85157AF5D66D3E8BL);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return ((mixed ^ (mixed >>> 31)) >>> 11) * 0x1.0p-53;
    }

    private record Aggregate(IndustrialAggregateShape shape, IndustrialDepositConfiguration.Material material) {}
    private record Candidate(BlockPos pos, int dx, int dy, int dz, double score, int pocket) {
        IndustrialAggregateGrowth.Coordinate coordinate() {
            return new IndustrialAggregateGrowth.Coordinate(dx, dy, dz);
        }
    }
}

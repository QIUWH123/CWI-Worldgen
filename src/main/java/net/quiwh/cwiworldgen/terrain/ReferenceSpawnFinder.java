package net.quiwh.cwiworldgen.terrain;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Climate;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.SpawnType;

/** Source SpawnFinderFix search; vanilla still resolves a safe spawn height afterward. */
public final class ReferenceSpawnFinder {
    public static BlockPos find(ReferenceTerrain terrain, Climate.Sampler sampler) {
        BlockPos center = terrain.spawnSearchCenter();
        SpawnType type = terrain.preset().world().properties.spawnType;
        if (type == SpawnType.USER_SELECTED || type == SpawnType.WORLD_ORIGIN
                || sampler.spawnTarget().isEmpty()) return center;
        Result result = fitness(sampler.spawnTarget(), sampler, center.getX(), center.getZ());
        result = radialSearch(sampler, result, 2048F, 512F);
        return radialSearch(sampler, result, 512F, 32F).location();
    }

    private static Result radialSearch(Climate.Sampler sampler, Result result, float radius, float step) {
        float angle = 0F;
        float distance = step;
        BlockPos center = result.location();
        while (distance <= radius) {
            int x = center.getX() + (int) (Math.sin(angle) * distance);
            int z = center.getZ() + (int) (Math.cos(angle) * distance);
            Result candidate = fitness(sampler.spawnTarget(), sampler, x, z);
            if (candidate.fitness() < result.fitness()) result = candidate;
            angle += step / distance;
            if (angle > Math.PI * 2) {
                angle = 0F;
                distance += step;
            }
        }
        return result;
    }

    private static Result fitness(List<Climate.ParameterPoint> targets, Climate.Sampler sampler, int x, int z) {
        double scale = Mth.square(2500.0);
        long distance = (long) (Mth.square(10000F)
                * Math.pow((Mth.square((long) x) + Mth.square((long) z)) / scale, 2.0));
        Climate.TargetPoint sampled = sampler.sample(QuartPos.fromBlock(x), 0, QuartPos.fromBlock(z));
        Climate.TargetPoint surface = new Climate.TargetPoint(sampled.temperature(), sampled.humidity(),
                sampled.continentalness(), sampled.erosion(), 0L, sampled.weirdness());
        long climateFitness = Long.MAX_VALUE;
        for (Climate.ParameterPoint target : targets) climateFitness = Math.min(climateFitness, target.fitness(surface));
        return new Result(new BlockPos(x, 0, z), distance + climateFitness);
    }

    private record Result(BlockPos location, long fitness) {}

    private ReferenceSpawnFinder() {}
}

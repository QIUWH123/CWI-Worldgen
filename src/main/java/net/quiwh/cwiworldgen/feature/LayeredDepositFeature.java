/*
 * Mineral geometry adapted from Create's LayeredOreFeature and LayerPattern.
 * Copyright (c) The Create Team / The Creators of Create. MIT License;
 * full notice in assets/cwi_worldgen/CREATE_CODE_LICENSE.txt.
 */
package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.quiwh.cwiworldgen.feature.LayeredDepositConfiguration.Layer;

/** Original Create host geometry, with the former deposit density confined to small pockets. */
public final class LayeredDepositFeature extends Feature<LayeredDepositConfiguration> {
    public LayeredDepositFeature(Codec<LayeredDepositConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<LayeredDepositConfiguration> context) {
        var random = context.random();
        var origin = context.origin();
        var level = context.level();
        var config = context.config();
        int bodySize = config.sizeSettings().enabled()
            ? Math.max(2, Math.round(config.size() * config.sizeSettings().scale(random, level.getBiome(origin))))
            : config.size();
        var pattern = config.layerPatterns().get(random.nextInt(config.layerPatterns().size()));
        int size = bodySize + 1;
        float radius = bodySize * .5F;
        int bound = Mth.ceil(radius) - 1;
        if (origin.getY() >= level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, origin.getX(), origin.getZ())) return false;

        List<TemporaryLayer> temporary = new ArrayList<>();
        float totalSize = 0;
        Layer previous = null;
        while (totalSize < size) {
            Layer layer = rollNext(pattern, previous, random);
            float thickness = Mth.randomBetween(random, layer.minSize(), layer.maxSize());
            temporary.add(new TemporaryLayer(layer, thickness));
            totalSize += thickness;
            previous = layer;
        }
        List<ResolvedLayer> resolved = new ArrayList<>(temporary.size());
        float cumulative = -(totalSize - size) * random.nextFloat();
        for (var entry : temporary) {
            float start = resolved.isEmpty() ? Float.NEGATIVE_INFINITY : cumulative * (2F / size) - 1F;
            cumulative += entry.size;
            if (cumulative < 0) continue;
            resolved.add(new ResolvedLayer(entry.layer, Mth.randomBetween(random, .5F, 1F), start));
        }
        float gy = (float) Math.cbrt(Mth.randomBetween(random, -1F, 1F));
        float rescale = Mth.sqrt(1F - gy * gy);
        float theta = random.nextFloat() * Mth.TWO_PI;
        float gx = Mth.cos(theta) * rescale;
        float gz = Mth.sin(theta) * rescale;
        var displacement = new SimplexNoise(random);
        var radial = new SimplexNoise(random);
        // Derive an independent random field without advancing Create's host/material RNG.
        long seed = Double.doubleToLongBits(displacement.getValue(13.5, 19.25, 29.75)) ^ origin.asLong();
        Map<Layer, List<BlockPos>> byLayer = new IdentityHashMap<>();
        List<BlockPos> all = new ArrayList<>();
        var mutable = new BlockPos.MutableBlockPos();
        try (var sections = new BulkSectionAccess(level)) {
            // Keep traversal, arithmetic and random consumption aligned with Create.
            for (int dzBlock = -bound; dzBlock <= bound; dzBlock++) {
                float dz = dzBlock * (1F / radius);
                if (dz * dz > 1) continue;
                for (int dxBlock = -bound; dxBlock <= bound; dxBlock++) {
                    float dx = dxBlock * (1F / radius);
                    if (dz * dz + dx * dx > 1) continue;
                    for (int dyBlock = -bound; dyBlock <= bound; dyBlock++) {
                        float dy = dyBlock * (1F / radius);
                        float distance = dz * dz + dx * dx + dy * dy;
                        if (distance > 1 || level.isOutsideBuildHeight(origin.getY() + dyBlock)) continue;
                        int x = origin.getX() + dxBlock;
                        int y = origin.getY() + dyBlock;
                        int z = origin.getZ() + dzBlock;
                        float ramp = gx * dx + gy * dy + gz * dz;
                        ramp += displacement.getValue(x * .125F, y * .125F, z * .125F) * (1.75F / size);
                        int index = Collections.binarySearch(resolved, new ResolvedLayer(null, 0, ramp));
                        if (index < 0) index = -2 - index;
                        var entry = resolved.get(index);
                        if (distance > entry.threshold) continue;
                        float threshold = Mth.map((float) radial.getValue(x * .125F, y * .125F, z * .125F), -1F, 1F, .75F, 1F);
                        if (distance > entry.threshold * threshold) continue;
                        var choices = entry.layer.targets();
                        var targets = choices.size() == 1 ? choices.getFirst() : choices.get(random.nextInt(choices.size()));
                        mutable.set(x, y, z);
                        if (!level.ensureCanWrite(mutable)) continue;
                        var section = sections.getSection(mutable);
                        if (section == null) continue;
                        int localX = SectionPos.sectionRelative(x);
                        int localY = SectionPos.sectionRelative(y);
                        int localZ = SectionPos.sectionRelative(z);
                        var before = section.getBlockState(localX, localY, localZ);
                        for (var target : targets) {
                            if (!target.target.test(before, random)) continue;
                            float discard = config.discardChanceOnAirExposure();
                            boolean skipAir = discard <= 0 || (discard < 1 && random.nextFloat() >= discard);
                            if (!skipAir && isAdjacentToAir(sections::getBlockState, mutable)) continue;
                            if (target.state.isAir()) continue;
                            section.setBlockState(localX, localY, localZ, target.state, false);
                            BlockPos placed = mutable.immutable();
                            byLayer.computeIfAbsent(entry.layer, ignored -> new ArrayList<>()).add(placed);
                            all.add(placed);
                            break;
                        }
                    }
                }
            }
            if (all.isEmpty()) return false;
            var pockets = pockets(all, config.pocketFraction(), RandomSource.create(seed));
            for (var layer : pattern) {
                var positions = byLayer.get(layer);
                if (positions == null) continue;
                positions.sort(Comparator.comparingDouble(pos -> pocketScore(pos, pockets, seed)));
                int eligible = Math.round(positions.size() * config.pocketFraction());
                // The old palette contained N*6 host choices plus one deposit choice.
                double localDensity = 1D / (6 * layer.targets().size() + 1);
                for (int index = 0; index < eligible; index++) {
                    BlockPos pos = positions.get(index);
                    // Rank the complete former pocket mask before removing whole groups. Removed
                    // volume must never enlarge the eligible region of a surviving pocket.
                    var pocket = nearestPocket(pos, pockets);
                    if (unit(seed ^ 0x52455441494E4CL, pocket.center) >= config.groupRetention()) continue;
                    if (unit(seed ^ 0x4445504F534954L, pos) >= localDensity) continue;
                    var section = sections.getSection(pos);
                    section.setBlockState(SectionPos.sectionRelative(pos.getX()), SectionPos.sectionRelative(pos.getY()),
                        SectionPos.sectionRelative(pos.getZ()), config.deposit(), false);
                }
            }
        }
        return true;
    }

    private static Layer rollNext(List<Layer> pattern, Layer previous, RandomSource random) {
        int weight = 0;
        for (Layer layer : pattern) if (layer != previous) weight += layer.weight();
        int rolled = random.nextInt(weight);
        for (Layer layer : pattern) {
            if (layer == previous) continue;
            rolled -= layer.weight();
            if (rolled < 0) return layer;
        }
        throw new IllegalStateException("Layer pattern has no selectable layer");
    }

    private static List<Pocket> pockets(List<BlockPos> candidates, float fraction, RandomSource random) {
        int count = Math.max(2, Math.round(candidates.size() * fraction / 180F));
        List<Pocket> result = new ArrayList<>();
        for (int attempt = 0; attempt < count * 80 && result.size() < count; attempt++) {
            BlockPos center = candidates.get(random.nextInt(candidates.size()));
            if (result.stream().anyMatch(pocket -> pocket.center.distSqr(center) < 36)) continue;
            result.add(new Pocket(center, 2.8 + random.nextDouble() * .8, 2.2 + random.nextDouble() * .8,
                2.8 + random.nextDouble() * .8));
        }
        return result;
    }

    private static double pocketScore(BlockPos pos, List<Pocket> pockets, long seed) {
        double score = Double.POSITIVE_INFINITY;
        for (var pocket : pockets) {
            double dx = (pos.getX() - pocket.center.getX()) / pocket.rx;
            double dy = (pos.getY() - pocket.center.getY()) / pocket.ry;
            double dz = (pos.getZ() - pocket.center.getZ()) / pocket.rz;
            score = Math.min(score, dx * dx + dy * dy + dz * dz);
        }
        return score + .15 * unit(seed ^ 0x504F434B4554L, pos);
    }

    private static Pocket nearestPocket(BlockPos pos, List<Pocket> pockets) {
        Pocket nearest = null;
        double closest = Double.POSITIVE_INFINITY;
        for (var pocket : pockets) {
            double dx = (pos.getX() - pocket.center.getX()) / pocket.rx;
            double dy = (pos.getY() - pocket.center.getY()) / pocket.ry;
            double dz = (pos.getZ() - pocket.center.getZ()) / pocket.rz;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < closest) { closest = distance; nearest = pocket; }
        }
        return nearest;
    }

    private static double unit(long seed, BlockPos pos) {
        long mixed = seed ^ (pos.getX() * 0x632BE59BD9B4E019L) ^ (pos.getY() * 0x9E3779B97F4A7C15L)
            ^ (pos.getZ() * 0x85157AF5D66D3E8BL);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return ((mixed ^ (mixed >>> 31)) >>> 11) * 0x1.0p-53;
    }

    private record TemporaryLayer(Layer layer, float size) {}
    private record ResolvedLayer(Layer layer, float threshold, float start) implements Comparable<ResolvedLayer> {
        @Override public int compareTo(ResolvedLayer other) { return Float.compare(start, other.start); }
    }
    private record Pocket(BlockPos center, double rx, double ry, double rz) {}
}

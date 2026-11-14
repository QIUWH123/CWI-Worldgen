package net.quiwh.cwiworldgen.carver;

import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

/** Checks target voxels before vanilla claims their shared carving-mask entries. */
public final class BiomeCarvingGate {
    private BiomeCarvingGate() {}

    public static boolean matches(ResourceLocation target, Function<BlockPos, Holder<Biome>> biomes, BlockPos position) {
        return biomes.apply(position).is(target);
    }

    public static WorldCarver.CarveSkipChecker restrict(WorldCarver.CarveSkipChecker shape,
            ResourceLocation target, Function<BlockPos, Holder<Biome>> biomes,
            double centerX, double centerZ, double horizontalRadius) {
        return (context, dx, dy, dz, y) -> shape.shouldSkip(context, dx, dy, dz, y)
                || !matches(target, biomes, candidatePosition(centerX, centerZ, horizontalRadius, dx, dz, y));
    }

    public static BlockPos candidatePosition(double centerX, double centerZ, double radius, double dx, double dz, int y) {
        // Vanilla normalizes (block coordinate + 0.5 - center) / radius.
        // Inverting gives coordinate + 0.5, safely away from an integer boundary.
        return new BlockPos(Mth.floor(centerX + dx * radius), y, Mth.floor(centerZ + dz * radius));
    }
}

package net.quiwh.cwiworldgen.feature.geode;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.DripstoneUtils;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.material.Fluids;
import net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource;
import net.quiwh.cwiworldgen.feature.geode.config.GeodeCrystalSpikeConfig;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

/** One bounded transaction for modern cave spikes; legacy feature paths remain independent. */
final class CrystalSpikePlacement {
    private static final int MAX_RADIUS = 4;
    private static final int MAX_WRITES = 2048;

    private CrystalSpikePlacement() {}

    static int generationVersion(WorldGenLevel world) {
        return (Object) world.getLevel().getChunkSource().randomState() instanceof TerrainRandomState state
                && state.cwiWorldgen$terrain() != null ? state.cwiWorldgen$terrain().generationVersion() : 0;
    }

    static boolean place(FeaturePlaceContext<GeodeCrystalSpikeConfig> context,
            Predicate<BlockState> substrate, BlockState bloom) {
        var world = context.level();
        var origin = context.origin();
        ChunkPos owner = world instanceof WorldGenRegion region ? region.getCenter() : new ChunkPos(origin);
        var manager = world.getLevel().structureManager();
        if (world instanceof WorldGenRegion region) manager = manager.forWorldGenRegion(region);
        var boxes = manager.startsForStructure(owner, structure -> true).stream()
                .flatMap(start -> start.getPieces().stream()).map(piece -> piece.getBoundingBox()).toList();
        var sampler = world.getLevel().getChunkSource().randomState().sampler();
        var source = context.chunkGenerator().getBiomeSource() instanceof BoundedCaveBiomeSource bounded ? bounded : null;
        var cave = source == null ? null : source.caveRegionAtBlock(origin.getX(), origin.getY(), origin.getZ(), sampler);
        // A bounded cave generator must supply the same real district to all final writes.
        if (source != null && cave == null) return false;
        Map<Long, Boolean> caveQuarts = new LinkedHashMap<>();
        Predicate<BlockPos> eligible = position -> {
            if (!owner.equals(new ChunkPos(position)) || world.isOutsideBuildHeight(position)
                    || !world.hasChunk(owner.x, owner.z) || !world.ensureCanWrite(position)) return false;
            if (cave != null) {
                if (position.getY() < cave.minY() || position.getY() > cave.maxY()) return false;
                // The cave source quantizes these queries. Share each exact quart
                // result within the transaction instead of resampling density for
                // every support face and attachment around the same crystal.
                long quart = BlockPos.asLong(position.getX() >> 2, position.getY() >> 2, position.getZ() >> 2);
                Boolean inCave = caveQuarts.get(quart);
                if (inCave == null) {
                    inCave = source.caveRegionAtBlock(position.getX(), position.getY(), position.getZ(), sampler) == cave;
                    caveQuarts.put(quart, inCave);
                }
                if (!inCave) return false;
            }
            for (var box : boxes) if (box.isInside(position)) return false;
            return true;
        };
        int radius = context.config().xzRadius.sample(context.random()) + 1;
        int height = radius + 14 + Mth.nextInt(context.random(), 10, 14);
        return placeChecked(world, context.config(), context.random(), origin, radius, height, substrate, bloom, eligible);
    }

    /** Every read is bounded by the caller's immutable owner/district/protection predicate. */
    static boolean placeChecked(WorldGenLevel world, GeodeCrystalSpikeConfig config, RandomSource random,
            BlockPos origin, int radius, int height, Predicate<BlockState> substrate,
            BlockState bloom, Predicate<BlockPos> eligible) {
        if (radius < 1 || radius > MAX_RADIUS || height < 1 || !eligible.test(origin)
                || !validSubstrate(world, origin, substrate) || bloom.hasBlockEntity()
                || !config.cluster_state.hasProperty(AmethystClusterBlock.FACING)
                || !config.cluster_state.hasProperty(AmethystClusterBlock.WATERLOGGED)
                || config.crystal_state.isAir() || config.crystal_state.hasBlockEntity()
                || !config.crystal_state.getFluidState().isEmpty()) return false;
        Direction towardSurface = config.crystal_direction.getDirection();
        Direction growth = towardSurface.getOpposite();
        if (!eligible.test(origin.relative(growth))
                || !DripstoneUtils.isEmptyOrWaterOrLava(world.getBlockState(origin.relative(growth)))) return false;
        double azimuth = random.nextDouble() * Math.PI * 2.;
        double lean = .2 + random.nextDouble() * .25;
        Map<Long, BlockPos> roots = new LinkedHashMap<>();
        // Search every base column before planning any substrate edit. A later missing
        // ceiling must not leave the earlier column's blackstone/sculk bloom behind.
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            if (x * x + z * z > radius * radius) continue;
            BlockPos root = origin.offset(x, 0, z);
            if (!eligible.test(root)) return false;
            if (towardSurface == Direction.UP) {
                int searched = 0;
                while (DripstoneUtils.isEmptyOrWaterOrLava(world.getBlockState(root)) && searched < 10) {
                    root = root.above();
                    if (!eligible.test(root)) return false;
                    searched++;
                }
            }
            if (!validSubstrate(world, root, substrate)) return false;
            roots.put(key(x, z), root);
        }
        Set<BlockPos> crystals = new LinkedHashSet<>();
        int layers = Math.min(height, radius * 2 + 1);
        int previousX = 0;
        int previousZ = 0;
        for (int layer = 1; layer <= layers; layer++) {
            int localRadius = Math.max(0, radius - (layer - 1) / 2);
            double desiredX = Math.cos(azimuth) * lean * layer;
            double desiredZ = Math.sin(azimuth) * lean * layer;
            int shiftX = (int) Math.round(desiredX);
            int shiftZ = (int) Math.round(desiredZ);
            // A rounded diagonal jump can detach the final one-block tip. Follow
            // the continuous target with at most one horizontal voxel step/layer.
            if (Math.abs(shiftX - previousX) + Math.abs(shiftZ - previousZ) > 1) {
                if (Math.abs(desiredX - previousX) >= Math.abs(desiredZ - previousZ)) {
                    shiftX = previousX + Integer.signum(shiftX - previousX);
                    shiftZ = previousZ;
                } else {
                    shiftX = previousX;
                    shiftZ = previousZ + Integer.signum(shiftZ - previousZ);
                }
            }
            previousX = shiftX;
            previousZ = shiftZ;
            for (int x = -localRadius; x <= localRadius; x++) for (int z = -localRadius; z <= localRadius; z++) {
                if (x * x + z * z > localRadius * localRadius) continue;
                BlockPos target = roots.get(key(x, z)).relative(growth, layer).offset(shiftX, 0, shiftZ);
                if (!eligible.test(target) || !DripstoneUtils.isEmptyOrWaterOrLava(world.getBlockState(target))) return false;
                crystals.add(target);
            }
        }
        if (crystals.isEmpty() || !supported(world, crystals, substrate, eligible)) return false;
        Map<BlockPos, BlockState> writes = new LinkedHashMap<>();
        // Keep the existing small substrate bloom and its palette. Only actual natural
        // support faces are converted, with no ores, block entities or structures touched.
        int bloomRadius = radius / 4;
        for (BlockPos root : roots.values()) {
            for (int x = -bloomRadius; x <= bloomRadius; x++) for (int z = -bloomRadius; z <= bloomRadius; z++)
                for (int y = -2; y <= 2; y++) {
                    BlockPos target = root.offset(x, y, z);
                    if (!eligible.test(target)) return false;
                    if (!validSubstrate(world, target, substrate)) continue;
                    for (Direction direction : Direction.values()) {
                        BlockPos neighbor = target.relative(direction);
                        if (eligible.test(neighbor)
                                && DripstoneUtils.isEmptyOrWaterOrLava(world.getBlockState(neighbor))) {
                            writes.put(target, bloom);
                            break;
                        }
                    }
                }
        }
        for (BlockPos crystal : crystals) writes.put(crystal, config.crystal_state);
        for (BlockPos crystal : crystals) {
            if (random.nextInt(6) != 0) continue;
            for (Direction direction : Direction.values()) {
                if (!random.nextBoolean()) continue;
                BlockPos target = crystal.relative(direction);
                if (writes.containsKey(target) || !eligible.test(target)
                        || !DripstoneUtils.isEmptyOrWater(world.getBlockState(target))
                        || !config.crystal_state.isFaceSturdy(world, crystal, direction)) continue;
                writes.put(target, config.cluster_state.setValue(AmethystClusterBlock.FACING, direction)
                        .setValue(AmethystClusterBlock.WATERLOGGED,
                                world.getFluidState(target).getType() == Fluids.WATER));
            }
        }
        if (writes.size() > MAX_WRITES) return false;
        // Planning above performs no writes or fluid updates. All validation precedes
        // this commit; feature execution owns the chunk and cannot race a neighbor.
        for (var entry : writes.entrySet()) world.setBlock(entry.getKey(), entry.getValue(), 2);
        return true;
    }

    private static boolean validSubstrate(WorldGenLevel world, BlockPos position, Predicate<BlockState> substrate) {
        BlockState state = world.getBlockState(position);
        return !state.hasBlockEntity() && state.getFluidState().isEmpty() && substrate.test(state);
    }

    private static boolean supported(WorldGenLevel world, Set<BlockPos> crystals,
            Predicate<BlockState> substrate, Predicate<BlockPos> eligible) {
        Set<BlockPos> remaining = new LinkedHashSet<>(crystals);
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        boolean anchored = false;
        for (BlockPos crystal : crystals) for (Direction direction : Direction.values()) {
            BlockPos neighbor = crystal.relative(direction);
            if (!crystals.contains(neighbor) && eligible.test(neighbor) && validSubstrate(world, neighbor, substrate)
                    && world.getBlockState(neighbor).isFaceSturdy(world, neighbor, direction.getOpposite())) {
                anchored = true;
                break;
            }
        }
        if (!anchored) return false;
        BlockPos first = crystals.iterator().next();
        remaining.remove(first);
        pending.add(first);
        while (!pending.isEmpty()) {
            BlockPos current = pending.remove();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (remaining.remove(neighbor)) pending.add(neighbor);
            }
        }
        return remaining.isEmpty();
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}

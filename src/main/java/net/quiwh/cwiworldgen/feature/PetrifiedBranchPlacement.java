package net.quiwh.cwiworldgen.feature;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.DripstoneUtils;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.material.Fluids;
import net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource;
import net.quiwh.cwiworldgen.terrain.LocalRockMaterials;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

/** Connected cave branches within the original sampled length, palette and wood budget. */
final class PetrifiedBranchPlacement {
    private PetrifiedBranchPlacement() {}

    static int generationVersion(WorldGenLevel world) {
        return (Object) world.getLevel().getChunkSource().randomState() instanceof TerrainRandomState state
                && state.cwiWorldgen$terrain() != null ? state.cwiWorldgen$terrain().generationVersion() : 0;
    }

    static boolean place(FeaturePlaceContext<PalettedConfiguration<PetrifiedBranchConfig>> context) {
        var world = context.level();
        var origin = context.origin();
        ChunkPos owner = world instanceof WorldGenRegion region ? region.getCenter() : new ChunkPos(origin);
        // Most configured attempts are in solid rock or open air far from a roof.
        // Reject those before querying structures, cave districts or sampling a plan.
        if (!owned(world, owner, origin) || !owned(world, owner, origin.above())
                || !world.isEmptyBlock(origin) || !naturalCeiling(world, origin.above())) return false;
        var manager = world.getLevel().structureManager();
        if (world instanceof WorldGenRegion region) manager = manager.forWorldGenRegion(region);
        var boxes = manager.startsForStructure(owner, structure -> true).stream()
                .flatMap(start -> start.getPieces().stream()).map(piece -> piece.getBoundingBox()).toList();
        var source = context.chunkGenerator().getBiomeSource() instanceof BoundedCaveBiomeSource bounded ? bounded : null;
        var sampler = world.getLevel().getChunkSource().randomState().sampler();
        var cave = source == null ? null : source.caveRegionAtBlock(origin.getX(), origin.getY(), origin.getZ(), sampler);
        if (source != null && cave == null) return false;
        Map<Long, Boolean> caveQuarts = new HashMap<>();
        Predicate<BlockPos> eligible = position -> {
            if (!owned(world, owner, position) || !context.config().heightBounds().contains(position)) return false;
            if (cave != null) {
                if (position.getY() < cave.minY() || position.getY() > cave.maxY()) return false;
                long quart = BlockPos.asLong(position.getX() >> 2, position.getY() >> 2, position.getZ() >> 2);
                Boolean contained = caveQuarts.get(quart);
                if (contained == null) {
                    contained = source.caveRegionAtBlock(position.getX(), position.getY(), position.getZ(), sampler) == cave;
                    caveQuarts.put(quart, contained);
                }
                if (!contained) return false;
            }
            for (var box : boxes) if (box.isInside(position)) return false;
            return true;
        };
        return placeChecked(world, context.origin(), context.random(), context.config(), eligible);
    }

    static boolean placeChecked(WorldGenLevel world, BlockPos origin, RandomSource random,
            PalettedConfiguration<PetrifiedBranchConfig> config, Predicate<BlockPos> eligible) {
        var palette = config.palette();
        var settings = config.settings();
        if (settings.minLength() < 0 || settings.minLength() > settings.maxLength() || settings.maxLength() > 32
                || !eligible.test(origin) || !eligible.test(origin.above()) || !world.isEmptyBlock(origin)
                || !naturalCeiling(world, origin.above())) return false;
        BlockState log = palette.state("petrified_log");
        BlockState porous = palette.state("porous_petrified_log");
        BlockState rootBody = palette.state("petrified_roots_plant");
        BlockState rootHead = palette.state("petrified_roots");
        BlockState amber = palette.state("amber_cluster");
        for (BlockState state : new BlockState[]{log, porous, rootBody, rootHead, amber})
            if (state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty()) return false;
        if (!amber.hasProperty(AmethystClusterBlock.FACING) || !amber.hasProperty(AmethystClusterBlock.WATERLOGGED)
                || !log.isFaceSturdy(world, origin, Direction.DOWN) || !porous.isFaceSturdy(world, origin, Direction.DOWN)) return false;
        int length = UniformInt.of(settings.minLength(), settings.maxLength()).sample(random);
        // A whole branch envelope stays inside its owner rather than being cut off
        // at a border. Cave/structure bounds are checked on every reachable voxel.
        ChunkPos owner = new ChunkPos(origin);
        if (!new ChunkPos(origin.offset(-1, 0, -1)).equals(owner)
                || !new ChunkPos(origin.offset(1, 0, 1)).equals(owner)) return false;
        Map<BlockPos, BlockState> sampled = new LinkedHashMap<>();
        int porousBudget = 0;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = -1; y <= length; y++) {
            BlockPos position = origin.offset(x, -y, z);
            BlockState material = random.nextBoolean() && random.nextInt(10) == 0 ? porous : log;
            if (random.nextInt(3) == 0 && eligible.test(position)
                    && DripstoneUtils.isEmptyOrWaterOrLava(world.getBlockState(position))) {
                sampled.put(position, material);
                if (material == porous) porousBudget++;
            }
        }
        int budget = sampled.size();
        if (budget == 0) return false;
        // One BFS bounds every route to at most 9*(length+2) existing open voxels.
        // Reconnect sampled endpoints using their shortest paths; bridge voxels
        // consume the original budget, replacing isolated floating endpoints.
        Direction[] directions = Direction.values();
        for (int index = directions.length - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            Direction swap = directions[index];
            directions[index] = directions[other];
            directions[other] = swap;
        }
        Map<BlockPos, BlockPos> parents = new LinkedHashMap<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        parents.put(origin, origin);
        pending.add(origin);
        while (!pending.isEmpty()) {
            BlockPos current = pending.remove();
            for (Direction direction : directions) {
                BlockPos next = current.relative(direction);
                if (parents.containsKey(next) || Math.abs(next.getX() - origin.getX()) > 1
                        || Math.abs(next.getZ() - origin.getZ()) > 1 || next.getY() > origin.getY() + 1
                        || next.getY() < origin.getY() - length || !eligible.test(next)
                        || !DripstoneUtils.isEmptyOrWaterOrLava(world.getBlockState(next))) continue;
                parents.put(next, current);
                pending.add(next);
            }
        }
        if (parents.size() < budget) return false;
        Set<BlockPos> targets = new LinkedHashSet<>();
        targets.add(origin);
        var endpoints = new ArrayList<>(sampled.keySet());
        long shapeSeed = random.nextLong();
        endpoints.sort(Comparator.<BlockPos>comparingInt(position -> position.getY())
                .thenComparingLong(position -> mix(position.asLong() ^ shapeSeed)));
        for (BlockPos endpoint : endpoints) {
            if (!parents.containsKey(endpoint)) continue;
            var path = new ArrayList<BlockPos>();
            for (BlockPos position = endpoint; !targets.contains(position); position = parents.get(position)) path.add(position);
            if (targets.size() + path.size() > budget) continue;
            for (int index = path.size() - 1; index >= 0; index--) targets.add(path.get(index));
            if (targets.size() == budget) break;
        }
        // BFS order always adds a child after its already available parent.
        if (targets.size() < budget) for (BlockPos position : parents.keySet()) {
            targets.add(position);
            if (targets.size() == budget) break;
        }
        Map<BlockPos, BlockState> writes = new LinkedHashMap<>();
        for (BlockPos position : targets) {
            boolean retainedPorous = porousBudget > 0 && sampled.get(position) == porous;
            writes.put(position, retainedPorous ? porous : log);
            if (retainedPorous) porousBudget--;
        }
        if (porousBudget > 0) for (BlockPos position : targets) {
            if (writes.get(position) == porous) continue;
            writes.put(position, porous);
            if (--porousBudget == 0) break;
        }
        for (BlockPos wood : targets) {
            BlockPos below = wood.below();
            if (!rootSpace(world, below, writes, eligible)) continue;
            BlockPos lower = below.below();
            if (rootSpace(world, lower, writes, eligible)) {
                writes.put(below, rootBody);
                writes.put(lower, rootHead);
            } else writes.put(below, rootHead);
        }
        if (random.nextInt(5) == 0) for (Direction direction : Direction.values()) for (BlockPos wood : targets) {
            if (writes.get(wood) != porous || !random.nextBoolean()) continue;
            BlockPos target = wood.relative(direction);
            if (writes.containsKey(target) || !eligible.test(target)
                    || !DripstoneUtils.isEmptyOrWater(world.getBlockState(target))
                    || !porous.isFaceSturdy(world, wood, direction)) continue;
            writes.put(target, amber.setValue(AmethystClusterBlock.FACING, direction)
                    .setValue(AmethystClusterBlock.WATERLOGGED, world.getFluidState(target).getType() == Fluids.WATER));
        }
        // Commit only after the entire connected wood, root and amber plan exists.
        // All world reads above stayed inside the bounded owner/protection policy.
        for (var entry : writes.entrySet()) world.setBlock(entry.getKey(), entry.getValue(), 2);
        return true;
    }

    private static boolean rootSpace(WorldGenLevel world, BlockPos position, Map<BlockPos, BlockState> writes,
            Predicate<BlockPos> eligible) {
        return !writes.containsKey(position) && eligible.test(position) && world.getFluidState(position).isEmpty()
                && world.getBlockState(position).canBeReplaced() && !world.getBlockState(position).hasBlockEntity();
    }

    private static boolean owned(WorldGenLevel world, ChunkPos owner, BlockPos position) {
        return owner.equals(new ChunkPos(position)) && !world.isOutsideBuildHeight(position)
                && world.hasChunk(owner.x, owner.z) && world.ensureCanWrite(position);
    }

    private static boolean naturalCeiling(WorldGenLevel world, BlockPos position) {
        BlockState state = world.getBlockState(position);
        return !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && (state.is(BlockTags.BASE_STONE_OVERWORLD) || LocalRockMaterials.rawRock(state))
                && state.isFaceSturdy(world, position, Direction.DOWN);
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}

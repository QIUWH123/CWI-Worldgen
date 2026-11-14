package net.quiwh.cwiworldgen.feature;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

/** Runs the author's vanilla tree unchanged, then commits its complete supported trunk atomically. */
public final class OriginalBurntTreePlacement {
    private OriginalBurntTreePlacement() {}

    public static boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random,
            BlockPos origin, TreeConfiguration config) {
        var terrain = ((TerrainRandomState) (Object) level.getLevel().getChunkSource().randomState()).cwiWorldgen$terrain();
        ChunkPos owner = new ChunkPos(origin);
        ChunkAccess chunk = level.getChunk(owner.x, owner.z);
        var reader = terrain.prepareChunk(owner.x, owner.z);
        var manager = level.getLevel().structureManager();
        if (level instanceof WorldGenRegion region) manager = manager.forWorldGenRegion(region);
        var boxes = manager.startsForStructure(owner, structure -> true).stream()
                .flatMap(start -> start.getPieces().stream()).map(piece -> piece.getBoundingBox()).toList();
        return placeChecked(level, generator, chunk, random, origin, config, position -> {
            var cell = reader.getCell(position.getX(), position.getZ());
            if (cell.terrain == null || cell.terrain.isRiver() || cell.terrain.isLake() || cell.terrain.isWetland()
                    || cell.terrain.isSubmerged() || cell.terrain.isDeepOcean() || cell.terrain.isShallowOcean()) return false;
            var biome = level.getBiome(position);
            if (!biome.is(net.minecraft.resources.ResourceLocation.parse("cwi:forsaken_land"))
                    && !biome.is(net.minecraft.resources.ResourceLocation.parse("cwi:smoldering_barren"))) return false;
            for (var box : boxes) if (box.isInside(position) || box.isInside(position.below())) return false;
            return true;
        }, terrain.generationVersion());
    }

    static boolean placeChecked(WorldGenLevel level, ChunkGenerator generator, ChunkAccess chunk, RandomSource random,
            BlockPos origin, TreeConfiguration config, Predicate<BlockPos> eligible) {
        return placeChecked(level, generator, chunk, random, origin, config, eligible, 13);
    }

    static boolean placeChecked(WorldGenLevel level, ChunkGenerator generator, ChunkAccess chunk, RandomSource random,
            BlockPos origin, TreeConfiguration config, Predicate<BlockPos> eligible, int generationVersion) {
        BlockPos base = origin.atY(chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, origin.getX(), origin.getZ()) + 1);
        if (!chunk.getPos().equals(new ChunkPos(origin))
                || !SafeDeadwoodFeature.standingSupport(chunk, base.below(), generationVersion)) return false;
        var recording = new RecordingRegion(level, chunk, base);
        // Invoke the actual feature, trunk placer, foliage placer and RNG sequence; never approximate its fork.
        if (!Feature.TREE.place(config, recording, generator, random, base) || recording.overflow) return false;
        Map<BlockPos, BlockState> logs = new LinkedHashMap<>();
        for (var entry : recording.writes.entrySet()) {
            BlockPos position = entry.getKey(); BlockState state = entry.getValue();
            // Original air foliage is evaluated by vanilla, but it must not erase existing native scenery.
            // Dirt-provider writes below the root stay in the recording so the actual native ground is preserved.
            if (state.isAir() || position.getY() < base.getY()) continue;
            logs.put(position, state);
        }
        if (!logs.containsKey(base)) return false;
        int minX = logs.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = logs.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minZ = logs.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ = logs.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        if (maxX - minX >= 16 || maxZ - minZ >= 16) return false;
        var owner = chunk.getPos();
        int dx = minX < owner.getMinBlockX() ? owner.getMinBlockX() - minX
                : maxX > owner.getMaxBlockX() ? owner.getMaxBlockX() - maxX : 0;
        int dz = minZ < owner.getMinBlockZ() ? owner.getMinBlockZ() - minZ
                : maxZ > owner.getMaxBlockZ() ? owner.getMaxBlockZ() - maxZ : 0;
        if (dx != 0 || dz != 0) {
            // Preserve the complete original shape and its RNG. A border candidate moves only as far
            // inward as that exact recorded shape requires; there are no rerolls or alternative sites.
            var shiftedBase = base.offset(dx, 0, dz);
            int groundY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, shiftedBase.getX(), shiftedBase.getZ());
            shiftedBase = shiftedBase.atY(groundY + 1);
            if (!SafeDeadwoodFeature.standingSupport(chunk, shiftedBase.below(), generationVersion)) return false;
            int dy = shiftedBase.getY() - base.getY();
            Map<BlockPos, BlockState> shifted = new LinkedHashMap<>();
            for (var entry : logs.entrySet()) shifted.put(entry.getKey().offset(dx, dy, dz), entry.getValue());
            logs = shifted;
        }
        for (var entry : logs.entrySet()) {
            BlockPos position = entry.getKey(); BlockState state = entry.getValue();
            if (!chunk.getPos().equals(new ChunkPos(position)) || position.getY() >= chunk.getMaxBuildHeight()
                    || position.getY() <= chunk.getMinBuildHeight()
                    || !eligible.test(position) || !chunk.getBlockState(position).isAir()
                    || state.hasBlockEntity() || !state.getFluidState().isEmpty() || !state.canSurvive(level, position)) return false;
        }
        EnumSet<Heightmap.Types> maps = EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG);
        EnumSet<Heightmap.Types> missing = maps.clone(); missing.removeIf(chunk::hasPrimedHeightmap);
        if (!missing.isEmpty()) Heightmap.primeHeightmaps(chunk, missing);
        for (var entry : logs.entrySet()) {
            BlockPos position = entry.getKey(); BlockState state = entry.getValue();
            chunk.setBlockState(position, state, false);
            for (var map : maps) chunk.getOrCreateHeightmapUnprimed(map).update(position.getX() & 15, position.getY(), position.getZ() & 15, state);
        }
        return true;
    }

    /** Bounded overlay: vanilla reads its own writes, never mutates chunks or schedules ticks while planning. */
    private static final class RecordingRegion extends WorldGenRegion {
        private final ChunkAccess chunk;
        private final BlockPos base;
        private final Map<BlockPos, BlockState> writes = new LinkedHashMap<>();
        private boolean overflow;
        RecordingRegion(WorldGenLevel level, ChunkAccess chunk, BlockPos base) {
            super(level.getLevel(), null, ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), chunk);
            this.chunk = chunk; this.base = base;
        }
        private boolean bounded(BlockPos pos) {
            return Math.abs(pos.getX() - base.getX()) <= 8 && Math.abs(pos.getZ() - base.getZ()) <= 8
                    && pos.getY() >= base.getY() - 2 && pos.getY() <= base.getY() + 12;
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            if (!bounded(pos)) { overflow = true; return Blocks.BARRIER.defaultBlockState(); }
            BlockState written = writes.get(pos);
            if (written != null) return written;
            // Plan the full unobstructed vanilla shape. Every resulting log is validated against real blocks
            // before commit, so vanilla cannot silently omit a blocked branch or clip it at an owner border.
            if (pos.getY() >= base.getY()) return Blocks.AIR.defaultBlockState();
            return chunk.getPos().equals(new ChunkPos(pos)) ? chunk.getBlockState(pos) : Blocks.AIR.defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
            if (!bounded(pos) || writes.size() >= 2048) { overflow = true; return false; }
            writes.put(pos.immutable(), state); return true;
        }
        @Override public boolean ensureCanWrite(BlockPos pos) { return bounded(pos); }
        @Override public LevelTickAccess<Block> getBlockTicks() { return BlackholeTickAccess.emptyLevelList(); }
        @Override public LevelTickAccess<Fluid> getFluidTicks() { return BlackholeTickAccess.emptyLevelList(); }
        @Override public boolean hasChunk(int x, int z) { return chunk.getPos().equals(new ChunkPos(x, z)); }
        @Override public ChunkAccess getChunk(int x, int z) { return getChunk(x, z, ChunkStatus.EMPTY, true); }
        @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
            if (hasChunk(x, z)) return chunk;
            if (required) throw new IllegalStateException("Original tree recorder requested mutable neighboring chunk");
            return null;
        }
    }
}

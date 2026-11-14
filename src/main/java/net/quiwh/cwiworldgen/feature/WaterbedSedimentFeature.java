package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.LocalRockMaterials;
import net.quiwh.cwiworldgen.terrain.WaterbedMaterials;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;

/** Early submerged-bed materials: no dry-land, water, elevation, structure or mineral edits. */
public final class WaterbedSedimentFeature extends Feature<NoneFeatureConfiguration> {
    private static final Set<String> ROCKS = Set.of(
            "minecraft:stone", "minecraft:deepslate", "minecraft:granite", "minecraft:andesite", "minecraft:diorite",
            "minecraft:tuff", "minecraft:calcite", "minecraft:basalt", "create:limestone", "kubejs:claystone",
            "kubejs:schist", "kubejs:gneiss", "kubejs:rhyolite", "kubejs:shale", "kubejs:conglomerate",
            "kubejs:phyllite", "kubejs:quartzite", "kubejs:darkslate", "kubejs:quartzite_deepslate");
    private static final Set<String> SEDIMENTS = Set.of(
            "kubejs:depleted_dirt", "kubejs:ashen_depleted_dirt", "minecraft:dirt", "minecraft:coarse_dirt",
            "minecraft:rooted_dirt", "minecraft:gravel",
            "minecraft:sand", "minecraft:red_sand", "minecraft:clay", "minecraft:cobblestone");

    public WaterbedSedimentFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!((Object) context.level().getLevel().getChunkSource().randomState() instanceof TerrainRandomState state)) return false;
        var terrain = state.cwiWorldgen$terrain();
        if (terrain == null) return false;
        ChunkPos owner = new ChunkPos(context.origin());
        var chunk = context.level().getChunk(owner.x, owner.z);
        var reader = terrain.prepareChunk(owner.x, owner.z);
        var manager = context.level().getLevel().structureManager();
        if (context.level() instanceof WorldGenRegion region) manager = manager.forWorldGenRegion(region);
        var structures = manager.startsForStructure(owner, structure -> true).stream()
                .flatMap(start -> start.getPieces().stream()).map(piece -> piece.getBoundingBox()).toList();
        return placeChunk(chunk, context.level().getSeed(), (x, z) -> {
            var cell = reader.getCell(x, z);
            boolean ocean = cell.terrain != null && (cell.terrain.isDeepOcean() || cell.terrain.isShallowOcean());
            boolean wet = cell.cwiPlannedWater || ocean || cell.terrain != null
                    && (cell.terrain.isRiver() || cell.terrain.isLake());
            boolean coast = ocean || cell.terrain != null && (cell.terrain.isCoast() || cell.terrain == TerrainType.BEACH);
            if (!wet || cell.cwiIsland || cell.cwiDryChannel) return new TerrainColumn(false, ocean, 0);
            int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            int worldX = owner.getBlockX(x), worldZ = owner.getBlockZ(z);
            for (var box : structures) if (worldX >= box.minX() && worldX <= box.maxX()
                    && worldZ >= box.minZ() && worldZ <= box.maxZ() && floor + 1 >= box.minY()
                    && floor - 12 <= box.maxY()) return new TerrainColumn(false, ocean, 0);
            double energy = Float.isFinite(cell.gradient) ? Math.clamp(cell.gradient * 2.5, 0., 1.) : .3;
            if (cell.cwiGorge) energy = Math.max(energy, .75);
            if (cell.cwiShoal) energy *= .35;
            return new TerrainColumn(true, ocean || coast, energy);
        }, terrain.generationVersion());
    }

    static boolean placeChunk(ChunkAccess chunk, long worldSeed, ColumnLookup columns) {
        var owner = chunk.getPos();
        var pos = new BlockPos.MutableBlockPos();
        boolean changed = false;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            var column = columns.at(x, z);
            if (!column.eligible()) continue;
            int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
            if (floor < chunk.getMinBuildHeight() + 5 || surface <= floor || surface >= chunk.getMaxBuildHeight() - 1) continue;
            int worldX = owner.getBlockX(x), worldZ = owner.getBlockZ(z);
            // Reject roofs, covered cave water and discontinuous exposed columns.
            boolean exposed = chunk.getBlockState(pos.set(worldX, surface + 1, worldZ)).isAir();
            for (int y = floor + 1; exposed && y <= surface; y++)
                exposed = chunk.getBlockState(pos.set(worldX, y, worldZ)).is(Blocks.WATER);
            if (!exposed) continue;
            int material = WaterbedMaterials.material(worldSeed, worldX, worldZ, column.ocean(), column.energy());
            if (material == WaterbedMaterials.SOIL) continue;
            int depth = WaterbedMaterials.depth(worldSeed, worldX, worldZ, material);
            // Validate the whole shallow edit before changing it, including solid support below it.
            boolean supported = true;
            for (int d = 0; d <= depth; d++)
                supported &= natural(chunk.getBlockState(pos.set(worldX, floor - d, worldZ)));
            if (!supported) continue;
            BlockState replacement = switch (material) {
                case WaterbedMaterials.ROCK -> nativeRock(chunk, pos.set(worldX, floor, worldZ));
                case WaterbedMaterials.GRAVEL -> Blocks.GRAVEL.defaultBlockState();
                case WaterbedMaterials.SAND -> Blocks.SAND.defaultBlockState();
                case WaterbedMaterials.CLAY -> Blocks.CLAY.defaultBlockState();
                default -> BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("kubejs:depleted_dirt"))
                        .defaultBlockState();
            };
            if (replacement == null || replacement.isAir()) continue;
            if (replacement.is(Blocks.STONE) && WaterbedMaterials.rockWear(worldSeed, worldX, worldZ) > .66)
                replacement = Blocks.COBBLESTONE.defaultBlockState();
            for (int d = 0; d < depth; d++) {
                pos.set(worldX, floor - d, worldZ);
                if (!chunk.getBlockState(pos).equals(replacement)) {
                    chunk.setBlockState(pos, replacement, false);
                    changed = true;
                }
            }
        }
        return changed;
    }

    static boolean placeChunk(ChunkAccess chunk, long worldSeed, ColumnLookup columns, int generationVersion) {
        if (generationVersion < 12) return placeChunk(chunk, worldSeed, columns);
        var owner = chunk.getPos();
        var pos = new BlockPos.MutableBlockPos();
        boolean changed = false;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            var column = columns.at(x, z);
            if (!column.eligible()) continue;
            int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
            if (floor < chunk.getMinBuildHeight() + 5 || surface <= floor || surface >= chunk.getMaxBuildHeight() - 1) continue;
            int worldX = owner.getBlockX(x), worldZ = owner.getBlockZ(z);
            boolean exposed = chunk.getBlockState(pos.set(worldX, surface + 1, worldZ)).isAir();
            for (int y = floor + 1; exposed && y <= surface; y++)
                exposed = chunk.getBlockState(pos.set(worldX, y, worldZ)).is(Blocks.WATER);
            if (!exposed) continue;
            int material = WaterbedMaterials.material(worldSeed, worldX, worldZ, column.ocean(), column.energy(), generationVersion);
            if (material == WaterbedMaterials.SOIL) continue;
            int depth = WaterbedMaterials.depth(worldSeed, worldX, worldZ, material);
            boolean supported = true;
            for (int d = 0; d <= depth; d++) {
                BlockState old = chunk.getBlockState(pos.set(worldX, floor - d, worldZ));
                supported &= LocalRockMaterials.natural(old) && old.isFaceSturdy(chunk, pos, Direction.UP);
            }
            if (!supported) continue;
            var host = LocalRockMaterials.find(chunk, pos.set(worldX, floor, worldZ), 12);
            BlockState replacement = switch (material) {
                case WaterbedMaterials.ROCK -> host == null ? null
                        : WaterbedMaterials.rockWear(worldSeed, worldX, worldZ) > .66 ? host.cobble() : host.raw();
                case WaterbedMaterials.GRAVEL -> host == null ? Blocks.GRAVEL.defaultBlockState() : host.gravel();
                case WaterbedMaterials.SAND -> Blocks.SAND.defaultBlockState();
                case WaterbedMaterials.CLAY -> Blocks.CLAY.defaultBlockState();
                default -> null;
            };
            if (replacement == null || replacement.isAir()) continue;
            for (int d = 0; d < depth; d++) {
                pos.set(worldX, floor - d, worldZ);
                if (!chunk.getBlockState(pos).equals(replacement)) {
                    chunk.setBlockState(pos, replacement, false);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static BlockState nativeRock(ChunkAccess chunk, BlockPos top) {
        var pos = top.mutable();
        for (int d = 0; d <= 12 && top.getY() - d >= chunk.getMinBuildHeight(); d++) {
            var state = chunk.getBlockState(pos.setY(top.getY() - d));
            if (!natural(state)) return null;
            if (ROCKS.contains(id(state))) return state;
        }
        return null;
    }

    /** Final owner-local pass, after the canonical sediment features have finished. */
    static boolean cleanup(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!((Object) context.level().getLevel().getChunkSource().randomState() instanceof TerrainRandomState state)
                || state.cwiWorldgen$terrain() == null || state.cwiWorldgen$terrain().generationVersion() < 14) return false;
        var terrain = state.cwiWorldgen$terrain();
        var owner = new ChunkPos(context.origin());
        var chunk = context.level().getChunk(owner.x, owner.z);
        var reader = terrain.prepareChunk(owner.x, owner.z);
        var manager = context.level().getLevel().structureManager();
        if (context.level() instanceof WorldGenRegion region) manager = manager.forWorldGenRegion(region);
        var structures = manager.startsForStructure(owner, structure -> true).stream()
                .flatMap(start -> start.getPieces().stream()).map(piece -> piece.getBoundingBox()).toList();
        return cleanupChunk(chunk, (x, z) -> {
            var cell = reader.getCell(x, z);
            boolean wet = cell.cwiPlannedWater || cell.terrain != null && (cell.terrain.isRiver()
                    || cell.terrain.isLake() || cell.terrain.isDeepOcean() || cell.terrain.isShallowOcean());
            int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
            int worldX = owner.getBlockX(x), worldZ = owner.getBlockZ(z);
            for (var box : structures) if (worldX >= box.minX() && worldX <= box.maxX()
                    && worldZ >= box.minZ() && worldZ <= box.maxZ() && floor + 1 >= box.minY()
                    && floor - 5 <= box.maxY()) return new TerrainColumn(false, false, 0);
            return new TerrainColumn(wet && !cell.cwiIsland && !cell.cwiDryChannel, false, 0);
        });
    }

    /** Snapshot before edits: rejecting a fragment must not disconnect a neighboring lens. */
    static boolean cleanupChunk(ChunkAccess chunk, ColumnLookup columns) {
        FineColumn[] fines = new FineColumn[256];
        var owner = chunk.getPos();
        var pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
            int worldX = owner.getBlockX(x), worldZ = owner.getBlockZ(z);
            BlockState material = chunk.getBlockState(pos.set(worldX, floor, worldZ));
            if (!fine(material) || !columns.at(x, z).eligible()) continue;
            int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (floor < chunk.getMinBuildHeight() + 5 || surface <= floor || surface >= chunk.getMaxBuildHeight() - 1
                    || !chunk.getBlockState(pos.setY(surface + 1)).isAir()) continue;
            boolean exposed = true;
            for (int y = floor + 1; exposed && y <= surface; y++)
                exposed = chunk.getBlockState(pos.setY(y)).is(Blocks.WATER);
            if (!exposed) continue;
            int bottom = floor;
            while (floor - bottom < 4 && chunk.getBlockState(pos.setY(bottom - 1)).is(material.getBlock())) bottom--;
            BlockState support = chunk.getBlockState(pos.setY(bottom - 1));
            if (!LocalRockMaterials.natural(support) || !support.isFaceSturdy(chunk, pos, Direction.UP)) continue;
            var host = LocalRockMaterials.find(chunk, pos.setY(floor), 12);
            BlockState replacement = host == null ? Blocks.GRAVEL.defaultBlockState() : host.gravel();
            if (!LocalRockMaterials.natural(replacement) || !replacement.isFaceSturdy(chunk, pos, Direction.UP)) continue;
            fines[z * 16 + x] = new FineColumn(floor, bottom, material, replacement);
        }
        boolean changed = false;
        boolean[] seen = new boolean[256];
        int[] queue = new int[256];
        for (int start = 0; start < 256; start++) {
            if (seen[start] || fines[start] == null) continue;
            int read = 0, count = 1;
            queue[0] = start;
            seen[start] = true;
            while (read < count) {
                int current = queue[read++], x = current & 15, z = current >> 4;
                for (int direction = 0; direction < 4; direction++) {
                    int nx = x + (direction == 0 ? 1 : direction == 1 ? -1 : 0);
                    int nz = z + (direction == 2 ? 1 : direction == 3 ? -1 : 0);
                    if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16) continue;
                    int next = nz * 16 + nx;
                    if (!seen[next] && fines[next] != null && fines[current].connects(fines[next])) {
                        seen[next] = true;
                        queue[count++] = next;
                    }
                }
            }
            // A seam-crossing sliver may be trimmed; retained patches always have
            // enough local area without consulting mutable neighboring chunks.
            if (count >= 4) continue;
            for (int index = 0; index < count; index++) {
                int column = queue[index];
                FineColumn fine = fines[column];
                for (int y = fine.bottom; y <= fine.top; y++) {
                    chunk.setBlockState(pos.set(owner.getBlockX(column & 15), y, owner.getBlockZ(column >> 4)),
                            fine.replacement, false);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static boolean fine(BlockState material) { return material.is(Blocks.SAND) || material.is(Blocks.CLAY); }

    private record FineColumn(int top, int bottom, BlockState material, BlockState replacement) {
        boolean connects(FineColumn other) {
            return material.is(other.material.getBlock()) && Math.max(bottom, other.bottom) <= Math.min(top, other.top);
        }
    }

    private static boolean natural(BlockState state) {
        return !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && (ROCKS.contains(id(state)) || SEDIMENTS.contains(id(state)));
    }
    private static String id(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }
    @FunctionalInterface interface ColumnLookup { TerrainColumn at(int localX, int localZ); }
    record TerrainColumn(boolean eligible, boolean ocean, double energy) {}
}

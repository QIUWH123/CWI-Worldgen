package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.LocalRockMaterials;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.quiwh.cwiworldgen.terrain.SedimentPatchShape;

/** Irregular surface lenses using the original sediment feature IDs and disk codec. */
public final class IrregularSedimentFeature extends Feature<DiskConfiguration> {
    private static final Set<String> NATURAL_SUBSTRATES = Set.of(
            "minecraft:stone", "minecraft:tuff", "minecraft:andesite", "minecraft:granite", "minecraft:diorite",
            "minecraft:deepslate", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
            "minecraft:gravel", "minecraft:clay", "minecraft:sand", "minecraft:red_sand", "minecraft:sandstone",
            "minecraft:smooth_sandstone", "kubejs:depleted_dirt", "kubejs:ashen_depleted_dirt", "kubejs:claystone",
            "kubejs:conglomerate", "kubejs:quartzite", "kubejs:schist", "kubejs:gneiss", "kubejs:phyllite",
            "kubejs:shale", "kubejs:rhyolite", "kubejs:darkslate", "create:limestone");

    public IrregularSedimentFeature(Codec<DiskConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<DiskConfiguration> context) {
        var level = context.level();
        var config = context.config();
        var origin = context.origin();
        int version = (Object) level.getLevel().getChunkSource().randomState() instanceof TerrainRandomState state
                && state.cwiWorldgen$terrain() != null ? state.cwiWorldgen$terrain().generationVersion() : 0;
        int radius = config.radius().sample(context.random());
        long seed = context.random().nextLong() ^ level.getSeed();
        BlockState sample = version >= 14
                ? config.stateProvider().getState(level, RandomSource.create(seed), origin) : null;
        boolean groupedFine = sample != null && (sample.is(Blocks.SAND) || sample.is(Blocks.CLAY));
        if (groupedFine) radius = Math.min(8, radius + 1);
        var shape = new SedimentPatchShape(seed, radius, origin.getX(), origin.getZ());
        int shiftX = 0, shiftZ = 0;
        if (groupedFine) {
            ChunkPos owner = level instanceof WorldGenRegion region ? region.getCenter() : new ChunkPos(origin);
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (int dx = -shape.boundRadius(); dx <= shape.boundRadius(); dx++)
                for (int dz = -shape.boundRadius(); dz <= shape.boundRadius(); dz++)
                    if (shape.contains(origin.getX() + dx, origin.getZ() + dz)) {
                        minX = Math.min(minX, origin.getX() + dx); maxX = Math.max(maxX, origin.getX() + dx);
                        minZ = Math.min(minZ, origin.getZ() + dz); maxZ = Math.max(maxZ, origin.getZ() + dz);
                    }
            if (maxX - (long) minX > 15 || maxZ - (long) minZ > 15) return false;
            // Preserve the complete irregular outline at owner edges. Later neighbor
            // decoration cannot reintroduce fragments after this chunk's final pass.
            shiftX = Math.clamp(0, owner.getMinBlockX() - minX, owner.getMaxBlockX() - maxX);
            shiftZ = Math.clamp(0, owner.getMinBlockZ() - minZ, owner.getMaxBlockZ() - maxZ);
        }
        boolean acceptsGravel = version >= 12 && vanillaGravelTarget(level, config.target());
        boolean changed = false;
        for (int dx = -shape.boundRadius(); dx <= shape.boundRadius(); dx++) {
            for (int dz = -shape.boundRadius(); dz <= shape.boundRadius(); dz++) {
                int sampleX = origin.getX() + dx, sampleZ = origin.getZ() + dz;
                if (!shape.contains(sampleX, sampleZ)) continue;
                int x = sampleX + shiftX, z = sampleZ + shiftZ;
                // WorldGenRegion.hasChunk only consults its already supplied generation
                // dependencies. Check availability before either heightmap lookup.
                if (!level.hasChunk(x >> 4, z >> 4)
                        || !level.ensureCanWrite(new BlockPos(x, origin.getY(), z))) continue;
                int depth = shape.depth(sampleX, sampleZ, Math.min(5, config.halfHeight() + 1));
                changed |= version >= 12
                        ? placeColumn(level, config, context.random(), x, z, depth, acceptsGravel)
                        : placeColumn(level, config, context.random(), x, z, depth);
            }
        }
        return changed;
    }

    static boolean placeColumn(WorldGenLevel level, DiskConfiguration config, RandomSource random,
            int x, int z, int depth) {
        if (!level.hasChunk(x >> 4, z >> 4)) return false;
        int floorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        if (floorY < level.getMinBuildHeight() || surfaceY <= floorY
                || surfaceY >= level.getMaxBuildHeight()) return false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, floorY + 1, z);
        // Only open surface water qualifies. A roofed cave puddle or buried water
        // column cannot turn this into another underground disk/blob generator.
        // CWI rebuilds the water fluid tag. Test the actual vanilla water block
        // used by terrain, matching the early bed pass even during tag reloads.
        if (!level.getBlockState(pos).is(Blocks.WATER)
                || !level.getBlockState(pos.setY(surfaceY)).is(Blocks.WATER)) return false;
        int limit = Math.max(1, Math.min(5, Math.min(depth, config.halfHeight() + 1)));
        BlockState[] replacements = new BlockState[limit];
        int layers = 0;
        for (int layer = 0; layer < limit; layer++) {
            pos.setY(floorY - layer);
            if (level.isOutsideBuildHeight(pos) || !level.ensureCanWrite(pos)) break;
            BlockState old = level.getBlockState(pos);
            if (!natural(old) || !old.isFaceSturdy(level, pos, Direction.UP) || !config.target().test(level, pos)) break;
            BlockState next = config.stateProvider().getState(level, random, pos);
            if (!natural(next) || next.hasBlockEntity() || !next.getFluidState().isEmpty()) break;
            replacements[layers++] = next;
        }
        if (layers == 0) return false;
        // Check support before making any edit. Otherwise a thin dirt roof above
        // a cave could become falling sediment and drain the water overhead.
        pos.setY(floorY - layers);
        if (level.isOutsideBuildHeight(pos)) return false;
        BlockState support = level.getBlockState(pos);
        if (!natural(support) || !support.isFaceSturdy(level, pos, Direction.UP)) return false;
        boolean changed = false;
        for (int layer = 0; layer < layers; layer++) {
            pos.setY(floorY - layer);
            if (!level.getBlockState(pos).equals(replacements[layer]))
                changed |= level.setBlock(pos, replacements[layer], 2);
        }
        return changed;
    }

    static boolean placeColumn(WorldGenLevel level, DiskConfiguration config, RandomSource random,
            int x, int z, int depth, int generationVersion) {
        if (generationVersion < 12) return placeColumn(level, config, random, x, z, depth);
        return placeColumn(level, config, random, x, z, depth, vanillaGravelTarget(level, config.target()));
    }

    private static boolean placeColumn(WorldGenLevel level, DiskConfiguration config, RandomSource random,
            int x, int z, int depth, boolean acceptsGravel) {
        if (!level.hasChunk(x >> 4, z >> 4)) return false;
        int floorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        if (floorY < level.getMinBuildHeight() || surfaceY <= floorY || surfaceY >= level.getMaxBuildHeight()) return false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, floorY, z);
        for (int y = floorY + 1; y <= surfaceY; y++) if (!level.getBlockState(pos.setY(y)).is(Blocks.WATER)) return false;
        int limit = Math.max(1, Math.min(5, Math.min(depth, config.halfHeight() + 1)));
        var host = LocalRockMaterials.find(level, pos.setY(floorY), 12);
        BlockState[] replacements = new BlockState[limit];
        int layers = 0;
        for (int layer = 0; layer < limit; layer++) {
            pos.setY(floorY - layer);
            if (level.isOutsideBuildHeight(pos) || !level.ensureCanWrite(pos)) break;
            BlockState old = level.getBlockState(pos);
            if (!LocalRockMaterials.natural(old) || !old.isFaceSturdy(level, pos, Direction.UP)
                    || !(config.target().test(level, pos) || acceptsGravel && LocalRockMaterials.gravel(old))) break;
            BlockState next = LocalRockMaterials.matching(config.stateProvider().getState(level, random, pos), host);
            if (!LocalRockMaterials.natural(next)) break;
            replacements[layers++] = next;
        }
        if (layers == 0) return false;
        pos.setY(floorY - layers);
        if (level.isOutsideBuildHeight(pos)) return false;
        BlockState support = level.getBlockState(pos);
        if (!LocalRockMaterials.natural(support) || !support.isFaceSturdy(level, pos, Direction.UP)) return false;
        boolean changed = false;
        for (int layer = 0; layer < layers; layer++) {
            pos.setY(floorY - layer);
            if (!level.getBlockState(pos).equals(replacements[layer])) changed |= level.setBlock(pos, replacements[layer], 2);
        }
        return changed;
    }

    /** Only an explicit, unoffset vanilla-gravel target extends to the pack's gravel variants. */
    private static boolean vanillaGravelTarget(WorldGenLevel level, BlockPredicate target) {
        var encoded = BlockPredicate.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), target).result();
        if (encoded.isEmpty() || !encoded.get().isJsonObject()) return false;
        var json = encoded.get().getAsJsonObject();
        if (!json.has("type") || !json.get("type").getAsString().equals("minecraft:matching_blocks")) return false;
        if (json.has("offset")) {
            var offset = json.getAsJsonArray("offset");
            if (offset.size() != 3) return false;
            for (var coordinate : offset) if (coordinate.getAsInt() != 0) return false;
        }
        if (!json.has("blocks") || !json.get("blocks").isJsonArray()) return false;
        for (var block : json.getAsJsonArray("blocks"))
            if (block.isJsonPrimitive() && block.getAsString().equals("minecraft:gravel")) return true;
        return false;
    }

    private static boolean natural(BlockState state) {
        return !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && NATURAL_SUBSTRATES.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
}

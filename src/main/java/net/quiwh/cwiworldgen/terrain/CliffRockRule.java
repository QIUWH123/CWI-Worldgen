package net.quiwh.cwiworldgen.terrain;

import com.mojang.serialization.MapCodec;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.Tile;

/** Restrained rock bands on analytically exposed CWI cliffs, before ores and structures. */
public record CliffRockRule() implements SurfaceRules.RuleSource {
    public static final MapCodec<CliffRockRule> CODEC = MapCodec.unit(new CliffRockRule());

    @Override public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        var terrain = ((TerrainRandomState) (Object) context.randomState).cwiWorldgen$terrain();
        if (terrain == null) return (x, y, z) -> null;
        CliffBands sampler = new CliffBands(terrain.seed());
        var levels = terrain.levels();
        Map<Long, Tile.Chunk> readers = new HashMap<>();
        CliffBands.Heights heights = (x, z) -> levels.scale(readers.computeIfAbsent(ChunkPos.asLong(x >> 4, z >> 4),
                key -> terrain.prepareChunk(x >> 4, z >> 4)).getCell(x, z).height);
        return new SurfaceRules.SurfaceRule() {
            private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            private int lastX = Integer.MIN_VALUE;
            private int lastZ = Integer.MIN_VALUE;
            private CliffBands.Exposure exposure;
            private RockShelfRelief.Sample shelf;

            @Override public BlockState tryApply(int x, int y, int z) {
                if (y < 49 || context.chunk.getBlockState(pos.set(x, y, z)) != Blocks.STONE.defaultBlockState()) return null;
                var biome = context.chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2).unwrapKey().orElse(null);
                if (biome == null) return null;
                String name = biome.location().toString();
                if (!name.equals("cwi:forsaken_land") && !name.equals("cwi:smoldering_barren")) return null;
                if (lastX != x || lastZ != z) {
                    exposure = CliffBands.exposureAt(x, z, heights);
                    if (terrain.generationVersion() >= 19) {
                        shelf = readers.computeIfAbsent(ChunkPos.asLong(x >> 4, z >> 4),
                                key -> terrain.prepareChunk(x >> 4, z >> 4)).getCell(x, z).cwiRockShelf;
                    }
                    lastX = x;
                    lastZ = z;
                }
                if (shelf != null) return material(ShelfCliffMaterials.materialAt(name, y, exposure, shelf));
                return material(sampler.materialAt(name, x, y, z, exposure));
            }
        };
    }

    public static BlockState material(CliffBands.Material material) {
        return switch (material) {
            case NONE -> null;
            case STONE -> Blocks.STONE.defaultBlockState();
            case TUFF -> Blocks.TUFF.defaultBlockState();
            case ANDESITE -> Blocks.ANDESITE.defaultBlockState();
        };
    }

    @Override public KeyDispatchDataCodec<CliffRockRule> codec() { return KeyDispatchDataCodec.of(CODEC); }
}

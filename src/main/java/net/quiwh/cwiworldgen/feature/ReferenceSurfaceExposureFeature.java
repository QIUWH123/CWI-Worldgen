package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.ReferenceWeathering;
import net.quiwh.cwiworldgen.terrain.RiverBankProfiles;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.hydrology.watershed.WatershedSample;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.SurfaceSettings;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noises;

/** Coherent soil retreat applied before structures and minerals, exposing each column's own host rock. */
public final class ReferenceSurfaceExposureFeature extends Feature<NoneFeatureConfiguration> {
    private static final Noise HEIGHT_VARIANCE = Noises.perlin(0, 100, 1);
    private static final Noise DESERT_VARIANCE = Noises.perlin(435, 8, 1);
    private static final float HEIGHT_MODIFIER = 6F / 255F;
    private static final float SLOPE_MODIFIER = 3F / 255F;
    private static final float SEDIMENT_NOISE = 3F / 255F;
    private static final float SCREE_VALUE = 0.55F;
    private static final Set<String> ROCKS = Set.of(
            "minecraft:stone", "minecraft:deepslate", "minecraft:granite", "minecraft:andesite", "minecraft:diorite",
            "minecraft:tuff", "minecraft:calcite", "minecraft:basalt", "create:limestone", "kubejs:claystone",
            "kubejs:schist", "kubejs:gneiss", "kubejs:rhyolite", "kubejs:shale", "kubejs:conglomerate",
            "kubejs:phyllite", "kubejs:quartzite", "kubejs:darkslate", "kubejs:quartzite_deepslate");
    private static final Set<String> SOILS = Set.of(
            "kubejs:depleted_dirt", "kubejs:ashen_depleted_dirt", "minecraft:dirt", "minecraft:coarse_dirt",
            "minecraft:grass_block", "minecraft:mycelium", "minecraft:podzol", "minecraft:rooted_dirt",
            "minecraft:gravel", "minecraft:sand", "minecraft:red_sand", "minecraft:clay");
    private static final Set<String> DESERT_STATES = Set.of(
            "minecraft:sand", "minecraft:sandstone", "minecraft:smooth_sandstone", "minecraft:terracotta",
            "minecraft:brown_terracotta", "minecraft:orange_terracotta");

    public ReferenceSurfaceExposureFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!((Object) context.level().getLevel().getChunkSource().randomState() instanceof TerrainRandomState state)) return false;
        var terrain = state.cwiWorldgen$terrain();
        if (terrain == null) return false;
        ChunkPos chunkPos = new ChunkPos(context.origin());
        ChunkAccess chunk = context.level().getChunk(chunkPos.x, chunkPos.z);
        var reader = terrain.prepareChunk(chunkPos.x, chunkPos.z);
        var levels = terrain.levels();
        var manager = context.level().getLevel().structureManager();
        if (context.level() instanceof WorldGenRegion region) manager = manager.forWorldGenRegion(region);
        var structures = manager.startsForStructure(chunkPos, structure -> true).stream()
                .flatMap(start -> start.getPieces().stream()).map(piece -> piece.getBoundingBox()).toList();
        SurfaceSettings.Erosion erosion = terrain.preset().surface().erosion();
        Noise random = Noises.perlin(terrain.climateSeed(), 28, 2);
        BlockState fallback = context.chunkGenerator() instanceof NoiseBasedChunkGenerator generator
                ? generator.generatorSettings().value().defaultBlock() : Blocks.STONE.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean changed = false;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
                if (surfaceY < context.chunkGenerator().getSeaLevel() - 1 || surfaceY < chunk.getMinBuildHeight()) continue;
                int x = chunkPos.getBlockX(localX);
                int z = chunkPos.getBlockZ(localZ);
                pos.set(x, surfaceY, z);
                Cell cell = reader.getCell(localX, localZ);
                if (terrain.generationVersion() >= 2 && Float.isFinite(cell.gradient)) {
                    var water = terrain.watershedSample(x, z);
                    if (water.valid() && !water.basin() && !water.island()) {
                        boolean protectedColumn = false;
                        for (var box : structures) if (x >= box.minX() && x <= box.maxX()
                                && z >= box.minZ() && z <= box.maxZ() && surfaceY + 1 >= box.minY()
                                && surfaceY - 32 <= box.maxY()) { protectedColumn = true; break; }
                        if (!protectedColumn) {
                            var action = bankAction(terrain, water, cell, x, z, surfaceY, context.level().getSeed());
                            changed |= finishBankColumn(chunk, pos.set(x, surfaceY, z), surfaceY, action);
                            if (action != RiverBankProfiles.Action.NONE) continue;
                        }
                    }
                }
                if (surfaceY > levels.scale(cell.height) || excluded(cell)) continue;
                var biome = context.level().getBiome(pos);
                if (biome.is(Biomes.DESERT)) changed |= erodeDesert(chunk, pos, surfaceY, cell, levels);
                else if (!biome.is(Biomes.BADLANDS) && !biome.is(Biomes.WOODED_BADLANDS)) {
                    changed |= erodeColumn(chunk, pos, surfaceY, cell, erosion, random, fallback,
                            ReferenceWeathering.sample(context.level().getSeed(), x, z));
                }
            }
        }
        return changed;
    }

    static RiverBankProfiles.Action bankAction(ReferenceTerrain terrain, WatershedSample water, Cell cell,
            int x, int z, int surfaceY, long seed) {
        if (!water.valid() || water.basin() || water.island()) return RiverBankProfiles.Action.NONE;
        if (terrain.generationVersion() >= 12) {
            return RiverBankProfiles.choose(water.wet(), water.dry(), water.distance(), water.width(),
                    water.bankContext(), cell.gradient, water.dry() ? 1 : surfaceY-water.waterY(),
                    ReferenceWeathering.sample(seed,x,z));
        }
        return RiverBankProfiles.choose(water.wet(), water.dry(), water.distance(), water.width(),
                bend(terrain, water, x, z), cell.gradient, water.dry() ? 1 : surfaceY - water.waterY(),
                ReferenceWeathering.sample(seed, x, z));
    }

    private static double bend(ReferenceTerrain terrain, WatershedSample center, int x, int z) {
        int distance = (int) Math.clamp(center.width() * 2, 16, 36);
        var before = terrain.watershedSample((int) Math.round(x - center.tangentX() * distance),
                (int) Math.round(z - center.tangentZ() * distance));
        var after = terrain.watershedSample((int) Math.round(x + center.tangentX() * distance),
                (int) Math.round(z + center.tangentZ() * distance));
        if (!before.valid() || !after.valid() || before.reachId() != center.reachId()
                || after.reachId() != center.reachId()) return 0;
        return before.tangentX() * after.tangentZ() - before.tangentZ() * after.tangentX();
    }

    /** Atomic natural-cover edits, confined to the owner and exposed above actual water. */
    static boolean finishBankColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int surfaceY,
            RiverBankProfiles.Action action) {
        if (action == RiverBankProfiles.Action.NONE || !chunk.getPos().equals(new ChunkPos(pos))
                || surfaceY < chunk.getMinBuildHeight() + 1 || surfaceY + 1 >= chunk.getMaxBuildHeight()
                || !chunk.getBlockState(pos.setY(surfaceY + 1)).isAir()
                || !isSoil(chunk.getBlockState(pos.setY(surfaceY)))) return false;
        if (action == RiverBankProfiles.Action.EXPOSE_HOST) return exposeRock(chunk, pos, surfaceY);
        if (!isSoil(chunk.getBlockState(pos.setY(surfaceY - 1)))) return false;
        BlockState depleted = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:depleted_dirt")).defaultBlockState();
        if (depleted.isAir()) return false;
        return fill(chunk, pos, surfaceY, 2, depleted, false);
    }

    private static boolean excluded(Cell cell) {
        return !Float.isFinite(cell.height) || !Float.isFinite(cell.gradient)
                || cell.terrain == null || cell.terrain.isRiver() || cell.terrain.isLake() || cell.terrain.isWetland()
                || cell.terrain.isDeepOcean() || cell.terrain.isShallowOcean() || cell.terrain == TerrainType.VOLCANO_PIPE;
    }

    static boolean erodeColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int surfaceY, Cell cell,
            SurfaceSettings.Erosion config, Noise random, BlockState fallback) {
        return erodeColumn(chunk, pos, surfaceY, cell, config, random, fallback, .5);
    }

    static boolean erodeColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int surfaceY, Cell cell,
            SurfaceSettings.Erosion config, Noise random, BlockState fallback, double weathering) {
        if (excluded(cell) || !chunk.getPos().equals(new ChunkPos(pos))
                || surfaceY < chunk.getMinBuildHeight() || surfaceY + 1 >= chunk.getMaxBuildHeight()
                || !chunk.getBlockState(pos.setY(surfaceY + 1)).isAir()) return false;
        pos.setY(surfaceY);
        BlockState top = chunk.getBlockState(pos);
        if (!isSoil(top)) return false;
        int x = pos.getX();
        int z = pos.getZ();
        double retreat = Math.clamp(weathering, 0., 1.);
        float height = cell.height + random.compute(x, z, 0) * HEIGHT_MODIFIER + (float) ((retreat - .5) * .04);
        float steepness = cell.gradient + random.compute(x, z, 1) * SLOPE_MODIFIER + (float) ((retreat - .5) * .12);
        if (steepness > config.rockSteepness || height > sampleHeight(x, z, config.rockVariance, config.rockMin)) {
            return exposeRock(chunk, pos, surfaceY);
        }
        boolean changed = false;
        if (steepness > config.dirtSteepness && height > sampleHeight(x, z, config.dirtVariance, config.dirtMin)) {
            changed |= fill(chunk, pos, surfaceY, 2 + Math.min(3, (int) (retreat * 4)), intermediate(top), false);
        }
        // Source sedimentModifier is unused; both active terms use sedimentNoise.
        float sediment = cell.sediment * SEDIMENT_NOISE;
        float sedimentNoise = random.compute(x, z, 2) * SEDIMENT_NOISE;
        if (steepness >= config.screeSteepness && sediment + sedimentNoise > SCREE_VALUE) {
            changed |= fill(chunk, pos, surfaceY, 2, Blocks.GRAVEL.defaultBlockState(), true);
        }
        return changed;
    }

    private static float sampleHeight(int x, int z, int variance, int minimum) {
        return HEIGHT_VARIANCE.compute(x, z, 0) * (variance / 255F) + minimum / 255F;
    }

    static boolean erodeDesert(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int surfaceY, Cell cell, Levels levels) {
        if (excluded(cell) || !chunk.getPos().equals(new ChunkPos(pos)) || surfaceY < chunk.getMinBuildHeight()
                || surfaceY + 1 >= chunk.getMaxBuildHeight() || !chunk.getBlockState(pos.setY(surfaceY + 1)).isAir()
                || cell.gradient < 0.15F || cell.height < levels.ground(10)) return false;
        float value = cell.height + DESERT_VARIANCE.compute(pos.getX(), pos.getZ(), 0) * levels.scale(16);
        float threshold = levels.ground(40);
        if (cell.gradient <= 0.3F && value <= threshold) return false;
        BlockState material = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        if (value > threshold) {
            if (cell.gradient > 0.975F) material = Blocks.TERRACOTTA.defaultBlockState();
            else if (cell.gradient > 0.85F) material = Blocks.BROWN_TERRACOTTA.defaultBlockState();
            else if (cell.gradient > 0.75F) material = Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            else if (cell.gradient > 0.65F) material = Blocks.TERRACOTTA.defaultBlockState();
        }
        boolean changed = false;
        for (int dy = 0; dy < 4 && surfaceY - dy >= chunk.getMinBuildHeight(); dy++) {
            BlockState old = chunk.getBlockState(pos.setY(surfaceY - dy));
            if (!DESERT_STATES.contains(id(old))) break;
            if (old != material) {
                chunk.setBlockState(pos, material, false);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean exposeRock(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int surfaceY) {
        int depth = Math.min(32, surfaceY - chunk.getMinBuildHeight() + 1);
        // Validate the entire soil cover before making the first edit. A missing
        // host or protected layer never falls back to a different biome's stone.
        for (int dy = 1; dy < depth; dy++) {
            BlockState state = chunk.getBlockState(pos.setY(surfaceY - dy));
            if (isRock(state)) {
                return fill(chunk, pos, surfaceY, dy, state, false);
            }
            if (!isSoil(state)) return false;
        }
        return false;
    }

    private static boolean fill(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int surfaceY, int depth,
            BlockState material, boolean includeRock) {
        boolean changed = false;
        for (int dy = 0; dy < depth && surfaceY - dy >= chunk.getMinBuildHeight(); dy++) {
            BlockState old = chunk.getBlockState(pos.setY(surfaceY - dy));
            if (!isSoil(old) && !(includeRock && isRock(old))) break;
            if (old != material) {
                chunk.setBlockState(pos, material, false);
                changed = true;
            }
        }
        return changed;
    }

    private static BlockState intermediate(BlockState state) {
        if (id(state).equals("kubejs:ashen_depleted_dirt")) {
            return BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kubejs:depleted_dirt")).defaultBlockState();
        }
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM)) return Blocks.COARSE_DIRT.defaultBlockState();
        if (state.is(Blocks.SAND)) return Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        if (state.is(Blocks.RED_SAND)) return Blocks.SMOOTH_RED_SANDSTONE.defaultBlockState();
        return state;
    }

    private static String id(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }
    private static boolean isSoil(BlockState state) {
        return !state.hasBlockEntity() && state.getFluidState().isEmpty() && SOILS.contains(id(state));
    }
    private static boolean isRock(BlockState state) {
        return !state.hasBlockEntity() && state.getFluidState().isEmpty() && ROCKS.contains(id(state));
    }
}

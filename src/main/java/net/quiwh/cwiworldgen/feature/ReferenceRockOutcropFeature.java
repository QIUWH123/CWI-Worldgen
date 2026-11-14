package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.LocalRockMaterials;
import net.quiwh.cwiworldgen.terrain.SurfaceHabitat;
import net.quiwh.cwiworldgen.terrain.ReferenceWeathering;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;

/** Sparse native rock relief applied before structures, ores and prospecting indicators. */
public final class ReferenceRockOutcropFeature extends Feature<NoneFeatureConfiguration> {
    private static final ResourceKey<Biome> FORSAKEN_LAND = ResourceKey.create(Registries.BIOME,
            ResourceLocation.parse("cwi:forsaken_land"));
    private static final ResourceKey<Biome> SMOLDERING_BARREN = ResourceKey.create(Registries.BIOME,
            ResourceLocation.parse("cwi:smoldering_barren"));
    private static final Set<String> ROCKS = Set.of(
            "minecraft:stone", "minecraft:deepslate", "minecraft:granite", "minecraft:andesite", "minecraft:diorite",
            "minecraft:tuff", "minecraft:calcite", "minecraft:basalt", "create:limestone", "kubejs:claystone",
            "kubejs:schist", "kubejs:gneiss", "kubejs:rhyolite", "kubejs:shale", "kubejs:conglomerate",
            "kubejs:phyllite", "kubejs:quartzite", "kubejs:darkslate", "kubejs:quartzite_deepslate");
    private static final Set<String> SOILS = Set.of(
            "kubejs:depleted_dirt", "kubejs:ashen_depleted_dirt", "minecraft:dirt", "minecraft:coarse_dirt",
            "minecraft:grass_block", "minecraft:mycelium", "minecraft:podzol", "minecraft:rooted_dirt",
            "minecraft:gravel", "minecraft:sand", "minecraft:red_sand", "minecraft:clay");
    private static final int RADIUS = 5;
    private static final int DIAMETER = RADIUS * 2 + 1;
    private static final int ROCK_SEARCH_DEPTH = 12;
    private static final ResourceLocation DARK_ASH_STONE = ResourceLocation.parse("kubejs:dark_ash_stone");
    private static final ResourceLocation ASH_STONE = ResourceLocation.parse("kubejs:ash_stone");
    private static final long ASH_SALT = 0x435749415348524BL;

    public ReferenceRockOutcropFeature(Codec<NoneFeatureConfiguration> codec) { super(codec); }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!((Object) context.level().getLevel().getChunkSource().randomState() instanceof TerrainRandomState state)) return false;
        var terrain = state.cwiWorldgen$terrain();
        if (terrain == null) return false;
        ChunkPos chunkPos = new ChunkPos(context.origin());
        ChunkAccess chunk = context.level().getChunk(chunkPos.x, chunkPos.z);
        var reader = terrain.prepareChunk(chunkPos.x, chunkPos.z);
        var levels = terrain.levels();
        return placeChunk(chunk, context.level().getSeed(), context.chunkGenerator().getSeaLevel(), (x, z) -> {
            Cell cell = reader.getCell(x, z);
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
            var biome = context.level().getBiome(new BlockPos(chunkPos.getBlockX(x), y, chunkPos.getBlockZ(z)));
            BiomeStyle style = biome.is(SMOLDERING_BARREN) ? BiomeStyle.SMOLDERING
                    : biome.is(FORSAKEN_LAND) ? BiomeStyle.FORSAKEN : BiomeStyle.OTHER;
            return new TerrainColumn(cell, levels.scale(cell.height), style, terrain.generationVersion() >= 12
                    ? terrain.surfaceHabitat(chunkPos.getBlockX(x), chunkPos.getBlockZ(z), cell) : null);
        }, terrain.generationVersion());
    }

    static boolean placeChunk(ChunkAccess chunk, long worldSeed, int seaLevel, ColumnLookup terrain) {
        return placeChunk(chunk, worldSeed, seaLevel, terrain, 9);
    }

    static boolean placeChunk(ChunkAccess chunk, long worldSeed, int seaLevel, ColumnLookup terrain,
            int generationVersion) {
        boolean outcrop = placeOutcrop(chunk, worldSeed, seaLevel, terrain, generationVersion);
        boolean ash = generationVersion >= 10 ? placeAshBoulder(chunk, worldSeed, seaLevel, terrain, generationVersion)
                : placeAshChunk(chunk, worldSeed, seaLevel, terrain);
        return outcrop || ash;
    }

    private static boolean placeOutcrop(ChunkAccess chunk, long worldSeed, int seaLevel, ColumnLookup terrain, int generationVersion) {
        ChunkPos chunkPos = chunk.getPos();
        long seed = worldSeed ^ chunkPos.x * 0x632BE59BD9B4E019L ^ chunkPos.z * 0x9E3779B97F4A7C15L
                ^ 0x435749524F434B53L;
        RandomSource random = RandomSource.create(seed);
        // Saved profiles retain their original centered sampling and random path.
        int anchorX = 6 + random.nextInt(4);
        int anchorZ = 6 + random.nextInt(4);
        int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, anchorX, anchorZ);
        TerrainColumn anchor = terrain.at(anchorX, anchorZ);
        if (generationVersion < 16 && !eligible(anchor, surfaceY, chunk, seaLevel)) return false;
        BlockPos anchorPos = new BlockPos(chunkPos.getBlockX(anchorX), surfaceY, chunkPos.getBlockZ(anchorZ));
        BlockState anchorRock = nativeRock(chunk, anchorPos, generationVersion);
        if (generationVersion < 16 && anchorRock == null) return false;
        BlockState darkAsh = anchor.biome == BiomeStyle.SMOLDERING ? registeredMaterial(DARK_ASH_STONE) : null;
        if (generationVersion < 16 && anchor.biome == BiomeStyle.SMOLDERING && darkAsh == null) return false;
        int west = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, anchorX - 4, anchorZ);
        int east = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, anchorX + 4, anchorZ);
        int north = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, anchorX, anchorZ - 4);
        int south = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, anchorX, anchorZ + 4);
        int highest = Math.max(Math.max(west, east), Math.max(north, south));
        int lowest = Math.min(Math.min(west, east), Math.min(north, south));
        boolean slope = anchor.cell.gradient >= .16F || highest - lowest >= 3;
        boolean cliffBase = highest - surfaceY >= 4 && surfaceY - lowest <= 1;
        boolean tuff = anchorRock != null && anchorRock.is(Blocks.TUFF);
        double chance = cliffBase ? (tuff ? .34 : .16) : slope ? (tuff ? .25 : .11) : (tuff ? .022 : .008);
        double weathering = ReferenceWeathering.sample(worldSeed, anchorPos.getX(), anchorPos.getZ());
        chance *= .65 + weathering * .7;
        if (generationVersion >= 12 && anchorRock != null && anchor.biome != BiomeStyle.SMOLDERING && anchor.habitat != null)
            chance *= outcropChanceMultiplier(anchor.habitat.withRockResistance(LocalRockMaterials.resistance(anchorRock)));
        double chanceDraw = random.nextDouble();
        if (generationVersion < 16 && chanceDraw >= chance) return false;
        // No possible final context exceeds this bound (.34 * 1.35 * 1.15).
        // Skip most shape construction without adding another rarity roll.
        if (generationVersion >= 16 && chanceDraw >= .53) return false;
        double angle = east == west && south == north ? random.nextDouble() * Math.PI * 2
                : Math.atan2(south - north, east - west) + Math.PI / 2 + (random.nextDouble() - .5) * .7;
        Shape shape = new Shape(random, tuff, angle, weathering);
        List<Offset> footprint = shape.footprint();
        if (footprint.size() < 8 || footprint.stream().filter(offset -> offset.relief > 0).count() * 2 < footprint.size()) return false;
        if (generationVersion >= 16) {
            int minX = 0, maxX = 0, minZ = 0, maxZ = 0;
            for (Offset offset : footprint) {
                minX = Math.min(minX, offset.x);
                maxX = Math.max(maxX, offset.x);
                minZ = Math.min(minZ, offset.z);
                maxZ = Math.max(maxZ, offset.z);
            }
            anchorX = ownedAnchor(random, minX, maxX);
            anchorZ = ownedAnchor(random, minZ, maxZ);
            surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, anchorX, anchorZ);
            anchor = terrain.at(anchorX, anchorZ);
            if (!eligible(anchor, surfaceY, chunk, seaLevel)) return false;
            anchorPos = new BlockPos(chunkPos.getBlockX(anchorX), surfaceY, chunkPos.getBlockZ(anchorZ));
            anchorRock = nativeRock(chunk, anchorPos, generationVersion);
            if (anchorRock == null) return false;
            darkAsh = anchor.biome == BiomeStyle.SMOLDERING ? registeredMaterial(DARK_ASH_STONE) : null;
            if (anchor.biome == BiomeStyle.SMOLDERING && darkAsh == null) return false;
            // Geometry is translated intact. Actual final terrain controls its
            // acceptance, palette and single chance comparison, including edges.
            west = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, Math.max(0, anchorX - 4), anchorZ);
            east = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, Math.min(15, anchorX + 4), anchorZ);
            north = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, anchorX, Math.max(0, anchorZ - 4));
            south = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, anchorX, Math.min(15, anchorZ + 4));
            highest = Math.max(Math.max(west, east), Math.max(north, south));
            lowest = Math.min(Math.min(west, east), Math.min(north, south));
            slope = anchor.cell.gradient >= .16F || highest - lowest >= 3;
            cliffBase = highest - surfaceY >= 4 && surfaceY - lowest <= 1;
            boolean localTuff = anchorRock.is(Blocks.TUFF);
            chance = cliffBase ? (localTuff ? .34 : .16) : slope ? (localTuff ? .25 : .11) : (localTuff ? .022 : .008);
            chance *= .65 + ReferenceWeathering.sample(worldSeed, anchorPos.getX(), anchorPos.getZ()) * .7;
            if (anchor.biome != BiomeStyle.SMOLDERING && anchor.habitat != null)
                chance *= outcropChanceMultiplier(anchor.habitat.withRockResistance(LocalRockMaterials.resistance(anchorRock)));
            if (chanceDraw >= chance) return false;
        }
        List<ColumnPlacement> columns = new ArrayList<>(footprint.size());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Offset offset : footprint) {
            int localX = anchorX + offset.x;
            int localZ = anchorZ + offset.z;
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
            TerrainColumn column = terrain.at(localX, localZ);
            if (column.biome != anchor.biome || !eligible(column, top, chunk, seaLevel)) return false;
            int x = chunkPos.getBlockX(localX);
            int z = chunkPos.getBlockZ(localZ);
            BlockState material = nativeRock(chunk, pos.set(x, top, z), generationVersion);
            int buried = anchor.biome == BiomeStyle.SMOLDERING ? 1 : offset.relief > 1 ? 3 : 2;
            int bottom = top - buried;
            int cap = top + offset.relief;
            if (material == null || bottom <= chunk.getMinBuildHeight() || cap >= chunk.getMaxBuildHeight()) return false;
            for (int y = bottom - 1; y <= top; y++) {
                BlockState old = chunk.getBlockState(pos.set(x, y, z));
                if (!naturalDry(old, generationVersion) || !old.isFaceSturdy(chunk, pos, Direction.UP)) return false;
            }
            for (int y = top + 1; y <= Math.max(top + 1, cap); y++) {
                if (!chunk.getBlockState(pos.set(x, y, z)).isAir()) return false;
            }
            columns.add(new ColumnPlacement(localX, localZ, bottom, cap, darkAsh == null ? material : darkAsh));
        }
        // A connected outline can still split into separate stacks on an abrupt ledge.
        // Preserve historical native formations while requiring one joined rock mass in new profiles.
        boolean requireConnected = generationVersion >= 16 || anchor.biome == BiomeStyle.SMOLDERING;
        return (!requireConnected || connected(columns)) && placeColumns(chunk, columns);
    }

    static double outcropChanceMultiplier(SurfaceHabitat habitat) {
        double exposure = habitat.rockResistance() * .5 + habitat.slope() * .3
                + (1. - habitat.groveRichness()) * .15 + (1. - habitat.waterProximity()) * .05;
        return .85 + Math.clamp(exposure, 0., 1.) * .30;
    }

    /** Uniformly translates a complete bounded footprint, including owner-edge columns. */
    static int ownedAnchor(RandomSource random, int minOffset, int maxOffset) {
        int positions = 16 - (maxOffset - minOffset);
        if (positions < 1) throw new IllegalArgumentException("Outcrop footprint exceeds its owning chunk");
        return -minOffset + random.nextInt(positions);
    }

    private static boolean placeAshChunk(ChunkAccess chunk, long worldSeed, int seaLevel, ColumnLookup terrain) {
        ChunkPos chunkPos = chunk.getPos();
        RandomSource random = RandomSource.create(worldSeed ^ chunkPos.x * 0x632BE59BD9B4E019L
                ^ chunkPos.z * 0x9E3779B97F4A7C15L ^ ASH_SALT);
        if (random.nextInt(20) != 0) return false;
        int anchorX = 4 + random.nextInt(8);
        int anchorZ = 4 + random.nextInt(8);
        int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, anchorX, anchorZ);
        TerrainColumn anchor = terrain.at(anchorX, anchorZ);
        if (anchor.biome != BiomeStyle.FORSAKEN || !eligible(anchor, surfaceY, chunk, seaLevel)) return false;
        BlockState ash = registeredMaterial(ASH_STONE);
        if (ash == null) return false;

        // Choose The Entire Shape Before Inspecting Blocks So An Obstruction Rejects It Atomically.
        int targetVolume = 12 + random.nextInt(9);
        int columnCount = targetVolume / 3;
        List<Offset> footprint = new ArrayList<>(columnCount);
        footprint.add(new Offset(0, 0, 0));
        while (footprint.size() < columnCount) {
            List<Offset> frontier = new ArrayList<>();
            for (Offset offset : footprint) for (Direction direction : Direction.Plane.HORIZONTAL) {
                int x = offset.x + direction.getStepX();
                int z = offset.z + direction.getStepZ();
                if (x * x + z * z > 4 || footprint.stream().anyMatch(member -> member.x == x && member.z == z)) continue;
                frontier.add(new Offset(x, z, 0));
            }
            footprint.add(frontier.get(random.nextInt(frontier.size())));
        }
        for (int remaining = targetVolume - columnCount * 2; remaining > 0; remaining--) {
            List<Integer> available = new ArrayList<>();
            for (int index = 0; index < footprint.size(); index++) if (footprint.get(index).relief < 2) available.add(index);
            int index = available.get(random.nextInt(available.size()));
            Offset offset = footprint.get(index);
            footprint.set(index, new Offset(offset.x, offset.z, offset.relief + 1));
        }

        List<ColumnPlacement> columns = new ArrayList<>(columnCount);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Offset offset : footprint) {
            int localX = anchorX + offset.x;
            int localZ = anchorZ + offset.z;
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
            TerrainColumn column = terrain.at(localX, localZ);
            if (column.biome != BiomeStyle.FORSAKEN || !eligible(column, top, chunk, seaLevel)) return false;
            int x = chunkPos.getBlockX(localX);
            int z = chunkPos.getBlockZ(localZ);
            int bottom = top - 1;
            int cap = top + offset.relief;
            if (nativeRock(chunk, pos.set(x, top, z)) == null || bottom <= chunk.getMinBuildHeight()
                    || cap >= chunk.getMaxBuildHeight()) return false;
            for (int y = bottom - 1; y <= top; y++) {
                BlockState old = chunk.getBlockState(pos.set(x, y, z));
                if (!naturalDry(old) || !old.isFaceSturdy(chunk, pos, Direction.UP)) return false;
            }
            for (int y = top + 1; y <= Math.max(top + 1, cap); y++) {
                if (!chunk.getBlockState(pos.set(x, y, z)).isAir()) return false;
            }
            columns.add(new ColumnPlacement(localX, localZ, bottom, cap, ash));
        }
        return connected(columns) && placeColumns(chunk, columns);
    }

    private static boolean placeAshBoulder(ChunkAccess chunk, long worldSeed, int seaLevel, ColumnLookup terrain, int generationVersion) {
        ChunkPos chunkPos = chunk.getPos();
        RandomSource random = RandomSource.create(worldSeed ^ chunkPos.x * 0x632BE59BD9B4E019L
                ^ chunkPos.z * 0x9E3779B97F4A7C15L ^ ASH_SALT);
        if (random.nextInt(20) != 0) return false;
        int anchorX = 4 + random.nextInt(8), anchorZ = 4 + random.nextInt(8);
        int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, anchorX, anchorZ);
        TerrainColumn anchor = terrain.at(anchorX, anchorZ);
        if (generationVersion < 16 && (anchor.biome != BiomeStyle.FORSAKEN
                || !eligible(anchor, surfaceY, chunk, seaLevel))) return false;
        BlockState ash = registeredMaterial(ASH_STONE);
        if (ash == null) return false;
        var shape = AshBoulderShape.create(random.nextLong());
        if (generationVersion >= 16) {
            int minX = 0, maxX = 0, minZ = 0, maxZ = 0;
            for (var offset : shape) {
                minX = Math.min(minX, offset.x());
                maxX = Math.max(maxX, offset.x());
                minZ = Math.min(minZ, offset.z());
                maxZ = Math.max(maxZ, offset.z());
            }
            anchorX = ownedAnchor(random, minX, maxX);
            anchorZ = ownedAnchor(random, minZ, maxZ);
            surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, anchorX, anchorZ);
            anchor = terrain.at(anchorX, anchorZ);
            if (anchor.biome != BiomeStyle.FORSAKEN || !eligible(anchor, surfaceY, chunk, seaLevel)) return false;
        }
        List<ColumnPlacement> columns = new ArrayList<>(shape.size());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (var offset : shape) {
            int localX = anchorX + offset.x(), localZ = anchorZ + offset.z();
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, localX, localZ);
            TerrainColumn column = terrain.at(localX, localZ);
            if (column.biome != BiomeStyle.FORSAKEN || !eligible(column, top, chunk, seaLevel)) return false;
            int x = chunkPos.getBlockX(localX), z = chunkPos.getBlockZ(localZ);
            int bottom = top - offset.buried(), cap = top + offset.relief();
            if (nativeRock(chunk, pos.set(x, top, z), generationVersion) == null || bottom <= chunk.getMinBuildHeight()
                    || cap >= chunk.getMaxBuildHeight()) return false;
            for (int y = bottom - 1; y <= top; y++) {
                BlockState old = chunk.getBlockState(pos.set(x, y, z));
                if (!naturalDry(old, generationVersion) || !old.isFaceSturdy(chunk, pos, Direction.UP)) return false;
            }
            for (int y = top + 1; y <= cap; y++) if (!chunk.getBlockState(pos.set(x, y, z)).isAir()) return false;
            columns.add(new ColumnPlacement(localX, localZ, bottom, cap, ash));
        }
        return connected(columns) && placeColumns(chunk, columns);
    }

    private static BlockState registeredMaterial(ResourceLocation id) {
        BlockState state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        return state.isAir() ? null : state;
    }

    /** Solid Vertical Columns Join Only When Adjacent Intervals Share At Least One Y Coordinate. */
    private static boolean connected(List<ColumnPlacement> columns) {
        if (columns.isEmpty()) return false;
        boolean[] visited = new boolean[columns.size()];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(0);
        visited[0] = true;
        int count = 0;
        while (!pending.isEmpty()) {
            ColumnPlacement current = columns.get(pending.remove());
            count++;
            for (int index = 0; index < columns.size(); index++) {
                ColumnPlacement other = columns.get(index);
                if (!visited[index] && Math.abs(current.x - other.x) + Math.abs(current.z - other.z) == 1
                        && Math.max(current.bottom, other.bottom) <= Math.min(current.cap, other.cap)) {
                    visited[index] = true;
                    pending.add(index);
                }
            }
        }
        return count == columns.size();
    }

    private static boolean placeColumns(ChunkAccess chunk, List<ColumnPlacement> columns) {
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // ProtoChunk Stops Updating These Worldgen Maps Automatically At Features Status.
        EnumSet<Heightmap.Types> missing = EnumSet.noneOf(Heightmap.Types.class);
        for (Heightmap.Types type : List.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG)) {
            if (!chunk.hasPrimedHeightmap(type)) missing.add(type);
        }
        if (!missing.isEmpty()) Heightmap.primeHeightmaps(chunk, missing);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        boolean changed = false;
        for (ColumnPlacement column : columns) {
            for (int y = column.bottom; y <= column.cap; y++) {
                pos.set(chunkPos.getBlockX(column.x), y, chunkPos.getBlockZ(column.z));
                if (chunk.getBlockState(pos) != column.material) {
                    chunk.setBlockState(pos, column.material, false);
                    worldSurface.update(column.x, y, column.z, column.material);
                    oceanFloor.update(column.x, y, column.z, column.material);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static boolean eligible(TerrainColumn column, int surface, ChunkAccess chunk, int seaLevel) {
        Cell cell = column.cell;
        return column.biome != BiomeStyle.OTHER && cell.terrain != null && surface >= seaLevel
                && surface > chunk.getMinBuildHeight() + 3 && surface <= column.terrainHeight
                && Float.isFinite(cell.height) && Float.isFinite(cell.gradient)
                && !cell.terrain.isRiver() && !cell.terrain.isLake() && !cell.terrain.isWetland()
                && !cell.terrain.isDeepOcean() && !cell.terrain.isShallowOcean() && !cell.terrain.isSubmerged()
                && cell.terrain != TerrainType.VOLCANO_PIPE;
    }

    private static BlockState nativeRock(ChunkAccess chunk, BlockPos surface, int generationVersion) {
        if (generationVersion < 12) return nativeRock(chunk, surface);
        var host = LocalRockMaterials.find(chunk, surface, ROCK_SEARCH_DEPTH);
        return host == null ? null : host.raw();
    }

    private static boolean naturalDry(BlockState state, int generationVersion) {
        return generationVersion >= 12 ? LocalRockMaterials.natural(state) : naturalDry(state);
    }

    private static BlockState nativeRock(ChunkAccess chunk, BlockPos surface) {
        BlockPos.MutableBlockPos pos = surface.mutable();
        for (int depth = 0; depth <= ROCK_SEARCH_DEPTH && surface.getY() - depth >= chunk.getMinBuildHeight(); depth++) {
            BlockState state = chunk.getBlockState(pos.setY(surface.getY() - depth));
            if (!naturalDry(state)) return null;
            if (ROCKS.contains(id(state))) return state;
        }
        return null;
    }

    private static boolean naturalDry(BlockState state) {
        return !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && (ROCKS.contains(id(state)) || SOILS.contains(id(state)));
    }

    private static String id(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }

    @FunctionalInterface
    interface ColumnLookup { TerrainColumn at(int localX, int localZ); }

    enum BiomeStyle { OTHER, NATIVE, FORSAKEN, SMOLDERING }

    record TerrainColumn(Cell cell, int terrainHeight, BiomeStyle biome, SurfaceHabitat habitat) {
        TerrainColumn(Cell cell, int terrainHeight, BiomeStyle biome) {
            this(cell, terrainHeight, biome, null);
        }
        TerrainColumn(Cell cell, int terrainHeight, boolean targetBiome) {
            this(cell, terrainHeight, targetBiome ? BiomeStyle.NATIVE : BiomeStyle.OTHER);
        }
    }

    private record ColumnPlacement(int x, int z, int bottom, int cap, BlockState material) {}

    private record Offset(int x, int z, int relief) {}

    private static final class Shape {
        private final int[] relief = new int[DIAMETER * DIAMETER];

        Shape(RandomSource random, boolean tuff, double angle, double weathering) {
            double wear = .92 + weathering * .16;
            double mainX = (tuff ? 3.5 + random.nextDouble() * .4 : 1.9 + random.nextDouble() * .4) * wear;
            double mainZ = (tuff ? 2.3 + random.nextDouble() * .4 : 1.2 + random.nextDouble() * .3) * wear;
            double secondX = (tuff ? 2.05 : 1.25) + (random.nextDouble() - .5) * .4;
            double secondZ = -.35 + random.nextDouble() * .5;
            double secondRadiusX = (tuff ? 2.2 + random.nextDouble() * .3 : 1.4 + random.nextDouble() * .25) * wear;
            double secondRadiusZ = (tuff ? 1.65 + random.nextDouble() * .3 : .95 + random.nextDouble() * .2) * wear;
            double phaseA = random.nextDouble() * Math.PI * 2;
            double phaseB = random.nextDouble() * Math.PI * 2;
            double height = (tuff ? 4.8 + random.nextDouble() * .7 : 2.4 + random.nextDouble() * .4) * wear;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                double u = dx * cos + dz * sin;
                double v = -dx * sin + dz * cos;
                double main = ellipse(u + .6, v, mainX, mainZ);
                double second = ellipse(u - secondX, v - secondZ, secondRadiusX, secondRadiusZ);
                double third = ellipse(u + 1.1, v - (tuff ? 1.45 : .9), (tuff ? 1.9 : 1.15) * wear,
                        (tuff ? 1.5 : .9) * wear);
                double field = Math.min(main, Math.min(second, third))
                        + Math.sin(u * 1.7 + phaseA) * Math.sin(v * 1.4 + phaseB) * .09;
                relief[index(dx, dz)] = field >= 1 ? -1
                        : Math.max(0, Math.min(tuff ? 5 : 3, (int) Math.floor((1 - field) * height
                                + Math.sin(u + phaseA) * .3 + .1)));
            }
        }

        List<Offset> footprint() {
            List<Offset> result = new ArrayList<>();
            boolean[] visited = new boolean[relief.length];
            ArrayDeque<Offset> pending = new ArrayDeque<>();
            pending.add(new Offset(0, 0, relief[index(0, 0)]));
            visited[index(0, 0)] = true;
            while (!pending.isEmpty()) {
                Offset next = pending.remove();
                if (next.relief < 0) continue;
                result.add(next);
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    int x = next.x + direction.getStepX();
                    int z = next.z + direction.getStepZ();
                    if (Math.abs(x) > RADIUS || Math.abs(z) > RADIUS || visited[index(x, z)]) continue;
                    visited[index(x, z)] = true;
                    pending.add(new Offset(x, z, relief[index(x, z)]));
                }
            }
            return result;
        }

        private static double ellipse(double x, double z, double radiusX, double radiusZ) {
            return x * x / (radiusX * radiusX) + z * z / (radiusZ * radiusZ);
        }

        private static int index(int x, int z) { return (x + RADIUS) * DIAMETER + z + RADIUS; }
    }
}

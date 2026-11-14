package net.quiwh.cwiworldgen.terrain.reference;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import com.mojang.serialization.Lifecycle;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.PresetClimateNoise;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.PresetTerrainTypeNoise;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.Preset;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.Tile;
import net.quiwh.cwiworldgen.terrain.reference.noise.domain.Domains;
import net.quiwh.cwiworldgen.terrain.reference.noise.function.CurveFunctions;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noises;
import net.quiwh.cwiworldgen.terrain.reference.registries.CFHRegistries;

/**
 * A seed-local instance of the supplied terrain engine. Ordinary sampling returns
 * detached copies; density-only raw cells are read-only, and chunk readers pin
 * completed tiles that are never recycled.
 */
public final class ReferenceTerrain implements AutoCloseable {
    public static final int TILE_SHIFT = 7;
    private static boolean codecsReady;
    private final GeneratorContext context;
    private final int generationVersion;
    private final int seed;
    private final long landscapeSeed;
    private final ThreadLocal<RawDensitySample> rawDensitySample = ThreadLocal.withInitial(RawDensitySample::new);
    private final net.quiwh.cwiworldgen.terrain.ReferenceWaterProtection waterProtection=
            new net.quiwh.cwiworldgen.terrain.ReferenceWaterProtection(this);
    private final java.util.LinkedHashMap<Long,net.quiwh.cwiworldgen.terrain.SurfaceHabitat> surfaceHabitats=
            new java.util.LinkedHashMap<>(128,.75F,true);
    private net.quiwh.cwiworldgen.terrain.ReferenceLandscape landscape;
    private final net.quiwh.cwiworldgen.hydrology.ReferenceHydrology hydrology;

    public ReferenceTerrain(long seed) { this(seed, 1); }

    public ReferenceTerrain(long seed, int generationVersion) {
        net.quiwh.cwiworldgen.terrain.TerrainGenerationProfile.requireSupported(generationVersion);
        this.generationVersion = generationVersion;
        this.seed = net.quiwh.cwiworldgen.terrain.WorldSeedPolicy.engineSeed(seed, generationVersion);
        this.landscapeSeed = seed;
        initializeCodecs();
        Preset preset = CwiTerrainPreset.make(generationVersion);
        MappedRegistry<Noise> registry = new MappedRegistry<>(CFHRegistries.NOISE, Lifecycle.stable());
        Registry.register(registry, PresetTerrainTypeNoise.GROUND,
                Noises.constant(preset.world().properties.seaLevel /
                        (float) preset.world().properties.terrainScaler()));
        Registry.register(registry, PresetClimateNoise.BIOME_EDGE_SHAPE,
                preset.climate().biomeEdgeShape.build(0));
        HolderGetter<Noise> lookup = new HolderGetter<>() {
            @Override public Optional<Holder.Reference<Noise>> get(ResourceKey<Noise> key) {
                return registry.getHolder(key);
            }
            @Override public Optional<HolderSet.Named<Noise>> get(TagKey<Noise> key) {
                return registry.getTag(key);
            }
        };
        // Preserve historical casts; new profiles diffuse all world-seed bits into the engine interface.
        context = GeneratorContext.makeCached(preset, lookup, this.seed, 3, 6, false, generationVersion);
        hydrology = new net.quiwh.cwiworldgen.hydrology.ReferenceHydrology(context.generator.getHeightmap(), this.seed);
    }

    private static synchronized void initializeCodecs() {
        if (codecsReady) return;
        Noises.bootstrap();
        Domains.bootstrap();
        CurveFunctions.bootstrap();
        codecsReady = true;
    }

    public Cell sample(int blockX, int blockZ) {
        Cell result = new Cell();
        sampleAccurateInto(result, blockX, blockZ);
        return result;
    }

    public void sampleBaseInto(Cell destination, int blockX, int blockZ) {
        destination.reset();
        context.generator.getHeightmap().applyBaseTerrain(destination, blockX, blockZ);
    }

    public void sampleAccurateInto(Cell destination, int blockX, int blockZ) {
        destination.copyFrom(getTile(blockX >> TILE_SHIFT, blockZ >> TILE_SHIFT).lookup(blockX, blockZ));
    }

    /** Match the original CellSampler: use completed tiles, otherwise raw heightmap. */
    public Cell sampleCachedOrRaw(int blockX, int blockZ) {
        Cell result = new Cell();
        context.lookup.applyCell(result, blockX, blockZ, false, true);
        return result;
    }

    /** Deterministic sparse query: intentionally independent of cache warmth. */
    public Cell sampleRaw(int blockX, int blockZ) {
        Cell result = new Cell();
        context.generator.getHeightmap().apply(result, blockX, blockZ, true);
        if (result.terrain == TerrainType.COAST && result.height > context.levels.water
                && result.height <= context.levels.water(5)) result.terrain = TerrainType.BEACH;
        return result;
    }

    /** Read-only density sample shared across fields at the last raw coordinate on this thread. */
    public Cell sampleRawForDensity(int blockX, int blockZ) {
        RawDensitySample cached = rawDensitySample.get();
        if (cached.cell == null || cached.x != blockX || cached.z != blockZ) {
            // Publish a completed detached cell: nested queries cannot overwrite an outer sample.
            Cell result = sampleRaw(blockX, blockZ);
            cached.cell = result;
            cached.x = blockX;
            cached.z = blockZ;
        }
        return cached.cell;
    }

    private static final class RawDensitySample {
        int x, z;
        Cell cell;
    }

    /** Returned tiles and their cells are read-only to callers. */
    public Tile getTile(int tileX, int tileZ) {
        return context.cache.provide(tileX, tileZ);
    }

    /** Pins the owning complete tile; use this reader only inside its own chunk. */
    public Tile.Chunk prepareChunk(int chunkX, int chunkZ) {
        return getTile(chunkX >> 3, chunkZ >> 3).getChunkReader(chunkX, chunkZ);
    }

    public Levels levels() {
        return new Levels(context.levels.worldHeight, context.levels.waterLevel);
    }

    public Preset preset() { return context.preset.copy(); }

    /** Uses the source preset and its own continent graph, without global world settings. */
    public BlockPos spawnSearchCenter() {
        var properties = context.preset.world().properties;
        return properties.spawnType.getSearchCenter(context, properties);
    }

    public net.quiwh.cwiworldgen.hydrology.HydrologySnapshot hydrologySnapshot(int blockX, int blockZ) {
        var watershed = context.generator.getHeightmap().watershed();
        return watershed == null ? hydrology.snapshot(blockX, blockZ) : watershed.snapshot(blockX, blockZ);
    }

    public net.quiwh.cwiworldgen.hydrology.HydrologyQuery queryHydrology(int blockX, int blockZ) {
        var watershed = context.generator.getHeightmap().watershed();
        return watershed == null ? hydrology.query(blockX, blockZ) : watershed.query(blockX, blockZ);
    }

    /** Immutable profile12 water envelope and native floor/rim protection, without filtered tile access. */
    public net.quiwh.cwiworldgen.hydrology.watershed.EnclosedPondPlanner.Column pondColumn(int x,int z) {
        var watershed=context.generator.getHeightmap().watershed();
        return watershed==null?net.quiwh.cwiworldgen.hydrology.watershed.EnclosedPondPlanner.Column.NONE:watershed.pondColumn(x,z);
    }
    /** Full bounded structure footprint against immutable pond masks, without filtered tile access. */
    public boolean pondRectangleIntersectsWet(int minX,int minZ,int maxX,int maxZ) {
        var watershed=context.generator.getHeightmap().watershed();
        return watershed!=null&&watershed.pondRectangleIntersectsWet(minX,minZ,maxX,maxZ);
    }

    /** Uses one completed native cell; it never probes mutable neighboring chunks. */
    public net.quiwh.cwiworldgen.terrain.SurfaceHabitat surfaceHabitat(int x,int z) {
        long key=((long)x<<32)^(z&0xffffffffL);
        synchronized(surfaceHabitats){var cached=surfaceHabitats.get(key);if(cached!=null)return cached;}
        return surfaceHabitat(x,z,sample(x,z));
    }
    public net.quiwh.cwiworldgen.terrain.SurfaceHabitat surfaceHabitat(int x,int z,Cell accurateCell) {
        long key=((long)x<<32)^(z&0xffffffffL);
        synchronized(surfaceHabitats){
            var cached=surfaceHabitats.get(key);if(cached!=null)return cached;
            var habitat=new net.quiwh.cwiworldgen.terrain.SurfaceHabitat(accurateCell.gradient,
                    accurateCell.height*context.levels.worldHeight,accurateCell.cwiPlannedWater?1:1-accurateCell.riverMask,
                    net.quiwh.cwiworldgen.placement.SceneryClusters.groveRichness(landscapeSeed,x,z),.5);
            surfaceHabitats.put(key,habitat);
            if(surfaceHabitats.size()>1024)surfaceHabitats.remove(surfaceHabitats.keySet().iterator().next());
            return habitat;
        }
    }
    public net.quiwh.cwiworldgen.terrain.ReferenceWaterProtection waterProtection(){return waterProtection;}
    public int cachedSurfaceHabitats(){synchronized(surfaceHabitats){return surfaceHabitats.size();}}

    public int seed() { return seed; }
    public int generationVersion() { return generationVersion; }
    public net.quiwh.cwiworldgen.hydrology.watershed.WatershedSample watershedSample(int x, int z) {
        var watershed = context.generator.getHeightmap().watershed();
        return watershed == null ? net.quiwh.cwiworldgen.hydrology.watershed.WatershedSample.absent(sampleRaw(x,z).height * 256.) : watershed.sample(x,z);
    }
    public net.quiwh.cwiworldgen.hydrology.watershed.WatershedHydrology.Diagnostics watershedDiagnostics(int x, int z) {
        var w = context.generator.getHeightmap().watershed();
        if (w == null) throw new IllegalStateException("Legacy generation does not use a watershed plan");
        return w.diagnostics(x,z);
    }
    public java.util.List<net.quiwh.cwiworldgen.terrain.RegionalLandforms.Basin> watershedBasins(int x, int z) {
        var w = context.generator.getHeightmap().watershed();
        return w == null ? java.util.List.of() : w.basins(x,z);
    }
    /** Detached accepted geometry for discovery; no mutable provider or Minecraft level escapes. */
    public net.quiwh.cwiworldgen.hydrology.watershed.WatershedHydrology.SearchSnapshot landformWaterSnapshot(int x, int z) {
        var watershed = context.generator.getHeightmap().watershed();
        return watershed == null ? null : watershed.searchSnapshot(x, z);
    }
    public net.quiwh.cwiworldgen.hydrology.watershed.EnclosedPondPlanner.Mask landformPondMask(int cellX, int cellZ) {
        var watershed = context.generator.getHeightmap().watershed();
        return watershed == null ? null : watershed.pondSearchMask(cellX, cellZ);
    }
    public int cachedWatershedPlans() { var w = context.generator.getHeightmap().watershed(); return w == null ? 0 : w.cachedPlans(); }
    public long builtWatershedPlans() { var w = context.generator.getHeightmap().watershed(); return w == null ? 0 : w.builtPlans(); }
    public void clearWatershedPlans() { var w = context.generator.getHeightmap().watershed(); if (w != null) w.clearPlans(); }


    public int climateSeed() { return context.generator.getHeightmap().climate().randomSeed(); }

    public void configureLandscape(net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource caves) {
        if (landscape == null) landscape = new net.quiwh.cwiworldgen.terrain.ReferenceLandscape(this, landscapeSeed, caves);
    }

    public net.quiwh.cwiworldgen.terrain.ReferenceLandscape landscape() { return landscape; }

    @Override public void close() {
        rawDensitySample.remove();
        waterProtection.clear();
        synchronized(surfaceHabitats){surfaceHabitats.clear();}
        context.cache.clear();
        var watershed = context.generator.getHeightmap().watershed();
        if (watershed != null) watershed.close();
    }
}

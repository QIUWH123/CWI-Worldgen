package net.quiwh.cwiworldgen.terrain.reference;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderGetter;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.Preset;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Heightmap;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.WorldLookup;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.TileCache;
import net.quiwh.cwiworldgen.terrain.reference.densityfunction.tile.generation.TileGenerator;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.util.Seed;

public class GeneratorContext {
    public final int generationVersion;
    public final int baseGenerationVersion;
    public Seed seed;
    public Levels levels;
    public Preset preset;
    public HolderGetter<Noise> noiseLookup;
    public TileGenerator generator;
    @Nullable
    public TileCache cache;
    public WorldLookup lookup;
    
    public GeneratorContext(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int tileBorder, int batchCount, @Nullable TileCache cache) {
        this(preset, noiseLookup, seed, tileSize, tileBorder, batchCount, cache, 1);
    }

    public GeneratorContext(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int tileBorder, int batchCount, @Nullable TileCache cache, int generationVersion) {
        net.quiwh.cwiworldgen.terrain.TerrainGenerationProfile.requireSupported(generationVersion);
        this.generationVersion = generationVersion;
        this.baseGenerationVersion = net.quiwh.cwiworldgen.terrain.BaseTerrainProfile.version(generationVersion);
        this.preset = preset;
        this.noiseLookup = noiseLookup;
        this.seed = new Seed(seed);
        this.levels = new Levels(preset.world().properties.terrainScaler(), preset.world().properties.seaLevel);
        this.generator = new TileGenerator(Heightmap.make(this), new WorldFilters(this), tileSize, tileBorder, batchCount);
        this.cache = cache;
        this.lookup = new WorldLookup(this);
    }

    public static GeneratorContext makeCached(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int batchCount, boolean queue) {
    	GeneratorContext ctx = makeUncached(preset, noiseLookup, seed, tileSize, Math.min(2, Math.max(1, preset.filters().erosion.dropletLifetime / 16)), batchCount);
    	ctx.cache = new TileCache(tileSize, queue, ctx.generator);
    	ctx.lookup = new WorldLookup(ctx);
    	return ctx;
    }
    
    public static GeneratorContext makeCached(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int batchCount, boolean queue, int generationVersion) {
        GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, seed, tileSize,
                Math.min(2, Math.max(1, preset.filters().erosion.dropletLifetime / 16)), batchCount, null, generationVersion);
        ctx.cache = new TileCache(tileSize, queue, ctx.generator);
        ctx.lookup = new WorldLookup(ctx);
        return ctx;
    }

    public static GeneratorContext makeUncached(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int tileBorder, int batchCount) {
    	return new GeneratorContext(preset, noiseLookup, seed, tileSize, tileBorder, batchCount, null);
    }
}

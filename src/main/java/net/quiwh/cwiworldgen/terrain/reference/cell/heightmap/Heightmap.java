package net.quiwh.cwiworldgen.terrain.reference.cell.heightmap;

import net.minecraft.core.HolderGetter;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.PresetNoiseData;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.PresetTerrainTypeNoise;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.Preset;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.TerrainSettings;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.WorldSettings;
import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.WorldSettings.ControlPoints;
import net.quiwh.cwiworldgen.terrain.reference.GeneratorContext;
import net.quiwh.cwiworldgen.terrain.reference.biome.Erosion;
import net.quiwh.cwiworldgen.terrain.reference.biome.Weirdness;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.CellPopulator;
import net.quiwh.cwiworldgen.terrain.reference.cell.climate.Climate;
import net.quiwh.cwiworldgen.terrain.reference.cell.continent.Continent;
import net.quiwh.cwiworldgen.terrain.reference.cell.continent.ContinentLerper2;
import net.quiwh.cwiworldgen.terrain.reference.cell.continent.ContinentLerper3;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.ContinentalHydrology;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.Rivermap;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.Blender;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.IslandBlender;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.Populators;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.populator.ArchipelagoPopulator;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.populator.VolcanoPopulator;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.provider.TerrainProvider;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.region.RegionLerper;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.region.RegionModule;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.region.RegionSelector;
import net.quiwh.cwiworldgen.terrain.reference.noise.function.DistanceFunction;
import net.quiwh.cwiworldgen.terrain.reference.noise.function.EdgeFunction;
import net.quiwh.cwiworldgen.terrain.reference.noise.function.Interpolation;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noises;
import net.quiwh.cwiworldgen.terrain.reference.util.Seed;

public record Heightmap(CellPopulator terrain, CellPopulator region, Continent continent, Climate climate, Levels levels, ControlPoints controlPoints, float terrainFrequency, Noise beachNoise, net.quiwh.cwiworldgen.hydrology.watershed.WatershedHydrology watershed) {
	
	public void apply(Cell cell, float x, float z, boolean applyClimate) {
		this.applyTerrain(cell, x, z);
		if (cell.terrain == TerrainType.ISLAND_BEACH) {
			cell.terrain = TerrainType.BEACH;
		}
		this.applyRivers(cell, x, z, this.watershed == null ? this.continent.getRivermap(cell) : null);
		this.applyClimate(cell, x, z, applyClimate);
	}
	
    public void applyTerrain(Cell cell, float x, float z) {
        cell.clearCwiWatershed();
        if (this.watershed != null) {
            cell.riverMask = 1F;
            cell.riverWaterLevel = 0F;
            cell.riverZone = net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.RiverCarverSettings.RiverZone.None;
        }
        this.applyBaseTerrain(cell, x, z);
        if (this.watershed != null) this.watershed.applyLandforms(cell, Math.round(x), Math.round(z));
    }

    /** Native uncarved terrain only: safe for drainage planning and independent fixtures. */
    public void applyBaseTerrain(Cell cell, float x, float z) {
        cell.terrain = TerrainType.FLATS;
        cell.beachNoise = this.beachNoise.compute(x, z, 0);
        this.continent.apply(cell, x, z);
        this.region.apply(cell, x, z);
        this.terrain.apply(cell, x * this.terrainFrequency, z * this.terrainFrequency);
	}
	
	public void applyRivers(Cell cell, float x, float z, Rivermap rivermap) {
        if (this.watershed == null) rivermap.apply(cell, x, z);
        else this.watershed.apply(cell, Math.round(x), Math.round(z));
        VolcanoPopulator.modifyVolcanoType(cell, this.levels);
	}
	
	public void applyClimate(Cell cell, float x, float z, boolean applyClimate) {
		float riverValleyThreshold = 0.675F;
        if(cell.riverMask < riverValleyThreshold && !isIslandTerrain(cell)) {
        	cell.erosion = 0.445F;
        	cell.weirdness = 0.34F;
        }
        
        if(cell.terrain.isRiver()) {
            cell.erosion = -0.05F;
            cell.weirdness = -0.03F;
        }
        
        if(cell.terrain.isLake() && cell.height < this.levels.water) {
            cell.erosion = Erosion.LEVEL_4.mid();
            cell.weirdness = -0.03F;
        }
        if(cell.terrain.isWetland()) {
        	cell.erosion = Erosion.LEVEL_6.mid();
        	cell.weirdness = Weirdness.VALLEY.mid();
        }
        
        this.climate.apply(cell, x, z, applyClimate);

        if(cell.riverMask >= riverValleyThreshold && cell.macroBiomeId > 0.5F) { 
        	cell.weirdness = -cell.weirdness;
        }
	}
	
	public static Heightmap make(GeneratorContext ctx) {
    	HolderGetter<Noise> noiseLookup = ctx.noiseLookup;
    	
        Preset preset = ctx.preset;
        WorldSettings world = ctx.preset.world();
        ControlPoints controlPoints = world.controlPoints;

        TerrainSettings terrainSettings = preset.terrain();
        TerrainSettings.General general = terrainSettings.general;
        float globalVerticalScale = general.globalVerticalScale;
        
        Seed regionWarp = ctx.seed.offset(8934);
        int regionWarpScale = 400;
        int regionWarpStrength = 200;
        
        RegionConfig regionConfig = new RegionConfig(
        	ctx.seed.root() + 789124, 
        	general.terrainRegionSize, 
        	Noises.simplex(regionWarp.next(), regionWarpScale, 1),
        	Noises.simplex(regionWarp.next(), regionWarpScale, 1), 
        	regionWarpStrength
        );
        Levels levels = ctx.levels;
        float terrainFrequency = 1.0F / terrainSettings.general.globalHorizontalScale;
        CellPopulator region = new RegionModule(regionConfig);

        Seed mountainSeed = ctx.seed.offset(general.terrainSeedOffset);
        int mountainShapeScale = general.legacyMountainScaling ? 1000 : Math.round(1000 * terrainSettings.mountains.horizontalScale * 2.25F);
        if (ctx.baseGenerationVersion >= 11) mountainShapeScale = Math.round(mountainShapeScale * .70F);
        Noise mountainShape = Noises.worleyEdge(mountainSeed.next(), mountainShapeScale, EdgeFunction.DISTANCE_2_ADD, DistanceFunction.EUCLIDEAN);
        mountainShape = Noises.warpPerlin(mountainShape, mountainSeed.next(),
                ctx.baseGenerationVersion >= 11 ? 240 : 333, 2, ctx.baseGenerationVersion >= 11 ? 175F : 250F);
        mountainShape = Noises.curve(mountainShape, Interpolation.CURVE3);
        mountainShape = Noises.clamp(mountainShape, 0.0F, 0.9F);
        mountainShape = Noises.map(mountainShape, 0.0F, 1.0F);

        if (ctx.baseGenerationVersion >= 11) {
            // Worley edges form an unbroken lattice independently of terrain regions.
            // Interrupt only this overriding chain layer, retaining full-strength peaks
            // between irregular lowland gaps. Separate salts preserve native seed order.
            Noise rangeBreaks = Noises.simplex(ctx.seed.root() ^ 0x5A71C93D, 520, 2);
            rangeBreaks = Noises.warpPerlin(rangeBreaks, ctx.seed.root() ^ 0x27B4E16F, 180, 2, 95F);
            rangeBreaks = Noises.curve(Noises.map(Noises.clamp(rangeBreaks, .26F, .50F), 0F, 1F), Interpolation.CURVE4);
            mountainShape = Noises.mul(mountainShape, rangeBreaks);
        }

        mountainShape = net.quiwh.cwiworldgen.terrain.MountainRangeProfile.chainMask(
                mountainShape, ctx.seed.root(), ctx.generationVersion);

        Noise ground = PresetNoiseData.getNoise(noiseLookup, PresetTerrainTypeNoise.GROUND);
        
        RegionSelector terrainRegions = new RegionSelector(TerrainProvider.generateTerrain(ctx.seed, terrainSettings, regionConfig, levels, noiseLookup, ctx.baseGenerationVersion, controlPoints.shallowOcean, controlPoints.inland));
        CellPopulator terrainRegionBorders = Populators.makeBorder(ctx.seed, ground, terrainSettings.plains, terrainSettings.steppe, globalVerticalScale);
        CellPopulator terrainBlend = new RegionLerper(terrainRegionBorders, terrainRegions, ctx.baseGenerationVersion);
        terrainBlend = net.quiwh.cwiworldgen.terrain.MountainRangeProfile.regional(
                terrainBlend, terrainRegions, regionConfig, ctx.generationVersion);
        CellPopulator mountains = Populators.makeMountainChain(mountainSeed, ground, terrainSettings.mountains, terrainSettings.general.legacyMountainScaling ? 1.0F : terrainSettings.mountains.horizontalScale * 2.25F, terrainSettings.general.legacyMountainScaling ? globalVerticalScale : globalVerticalScale * net.quiwh.cwiworldgen.terrain.MountainRangeProfile.chainVerticalScale(
                ctx.generationVersion, terrainSettings.mountains.verticalScale), general.fancyMountains, general.legacyMountainScaling, ctx.baseGenerationVersion);
        Continent continent = world.continent.continentType.create(ctx.seed, ctx);
        Climate climate = Climate.make(continent, ctx);
        CellPopulator land = new Blender(mountainShape, terrainBlend, mountains, 0.3F, 0.8F, 0.575F, ctx.baseGenerationVersion);
        
        CellPopulator deepOcean = Populators.makeDeepOcean(ctx.seed.next(), levels.water);
        CellPopulator shallowOcean = Populators.makeShallowOcean(ctx.levels);
        CellPopulator coast = Populators.makeCoast(ctx.levels);

        CellPopulator oceans = new ContinentLerper3(deepOcean, shallowOcean, coast, controlPoints.deepOcean, controlPoints.shallowOcean, controlPoints.coast);

        CellPopulator terrain = new ContinentLerper2(oceans, (cell, x, z) -> {
            land.apply(cell, x, z);
            cell.globalContinentScale = world.continent.continentScale;
            if (ctx.baseGenerationVersion >= 7) {
                cell.height += net.quiwh.cwiworldgen.terrain.reference.ContinuousInlandUplift.sample(
                        ctx.seed.root(), x, z, cell.waterTable, cell.continentSizeModifier, levels.worldHeight);
            } else {
                cell.height += (ContinentalHydrology.getComplexWaterHeight(
                        cell.waterTable,
                        cell.globalContinentScale,
                        cell.continentSizeModifier)
                        * net.quiwh.cwiworldgen.terrain.BaseTerrainProfile.inlandUpliftScale(ctx.generationVersion)
                );
            }
        }, controlPoints.shallowOcean, controlPoints.inland);
        
        // Wrap with archipelago layer if enabled
        if (ctx.preset.island().enableArchipelago) {
            var edgeCell = ThreadLocal.withInitial(Cell::new);
            java.util.function.DoubleBinaryOperator edgeSampler = ctx.baseGenerationVersion >= 8 ? (x, z) -> {
                Cell sample = edgeCell.get();
                sample.reset();
                continent.apply(sample, (float) (x / terrainFrequency), (float) (z / terrainFrequency));
                return sample.continentEdge;
            } : null;
            terrain = new IslandBlender(terrain, new ArchipelagoPopulator(ctx.preset.island(), ctx.levels, controlPoints, ctx.seed, ctx.baseGenerationVersion, edgeSampler), ctx.levels);
        }

        Noise beachNoise = Noises.perlin2(ctx.seed.next(), 20, 1);
        beachNoise = Noises.mul(beachNoise, ctx.levels.scale(5));
        final CellPopulator nativeTerrain = terrain;
        final Noise nativeBeachNoise = beachNoise;
        net.quiwh.cwiworldgen.terrain.RegionalLandforms.Sampler base = (cell, x, z) -> {
            cell.terrain = TerrainType.FLATS;
            cell.beachNoise = nativeBeachNoise.compute(x, z, 0);
            continent.apply(cell, x, z);
            region.apply(cell, x, z);
            nativeTerrain.apply(cell, x * terrainFrequency, z * terrainFrequency);
        };
        var watershed = ctx.generationVersion >= 2
                ? new net.quiwh.cwiworldgen.hydrology.watershed.WatershedHydrology(ctx.seed.root(), levels, base, ctx.generationVersion) : null;
        return new Heightmap(terrain, region, continent, climate, levels, controlPoints, terrainFrequency, beachNoise, watershed);
	}
	
	private static boolean isIslandTerrain(Cell cell) {
	    return cell.terrain == TerrainType.ISLAND || cell.terrain == TerrainType.ISLAND_BEACH || cell.terrain == TerrainType.ISLAND_MOUNTAINS;
	}
}

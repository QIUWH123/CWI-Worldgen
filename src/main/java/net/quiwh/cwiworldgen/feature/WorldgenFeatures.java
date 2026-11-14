package net.quiwh.cwiworldgen.feature;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.LargeDripstoneConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.VegetationPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.quiwh.cwiworldgen.carver.BlockCarver;
import net.quiwh.cwiworldgen.carver.BlockCarverConfiguration;

/** Custom codecs needed by the original pack's Darker Depths data dependency closure. */
public final class WorldgenFeatures {
    private static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(Registries.FEATURE, "cwi_worldgen");
    private static final DeferredRegister<WorldCarver<?>> CARVERS = DeferredRegister.create(Registries.CARVER, "cwi_worldgen");

    static {
        FEATURES.register("irregular_sediment", () -> new IrregularSedimentFeature(DiskConfiguration.CODEC));
        FEATURES.register("waterbed_sediments", () -> new WaterbedSedimentFeature(NoneFeatureConfiguration.CODEC));
        FEATURES.register("safe_deadwood", () -> new SafeDeadwoodFeature(SafeDeadwoodFeature.Config.CODEC));
        FEATURES.register("terrain_surface_exposure", () -> new ReferenceSurfaceExposureFeature(NoneFeatureConfiguration.CODEC));
        FEATURES.register("terrain_rock_outcrops", () -> new ReferenceRockOutcropFeature(NoneFeatureConfiguration.CODEC));
        FEATURES.register("terrain_surface_cleanup", () -> new ReferenceSurfaceCleanupFeature(NoneFeatureConfiguration.CODEC));
        FEATURES.register("correspondent_layer", () -> new CorrespondentLayersFeature(CorrespondentLayersConfig.CODEC));
        FEATURES.register("random_floor_placement", () -> new RandomFloorPlacementFeature(RandomFloorPlacementConfig.CODEC));
        FEATURES.register("small_boulder", () -> new SmallBoulderFeature(SmallBoulderConfiguration.CODEC));
        FEATURES.register("catacombs_lava_lining", () -> new CatacombsLavaLiningFeature(PaletteConfiguration.codec(
            "aridrock", "arid_deepslate", "duskrock", "darkslate", "dry_sprouts")));
        FEATURES.register("gemstone", () -> new GemstoneFeature(PaletteConfiguration.codec("amber_cluster")));
        FEATURES.register("huge_glowshroom", () -> new HugeGlowshroomFeature(PaletteConfiguration.codec(
            "mossy_grimestone", "glowshroom", "glowshroom_stem", "glowshroom_block", "glimmering_vines", "glowshroom_heart")));
        FEATURES.register("petrified_branch", () -> new PetrifiedBranchFeature(PalettedConfiguration.codec(
            PetrifiedBranchConfig.CODEC, "petrified_log", "porous_petrified_log", "petrified_roots_plant", "petrified_roots", "amber_cluster")));
        FEATURES.register("lava_vegetation_patch", () -> new LavaVegetationPatchFeature(LavaPatchConfiguration.CODEC.xmap(
            value -> (VegetationPatchConfiguration)value, value -> (LavaPatchConfiguration)value)));
        FEATURES.register("large_molten_pillar", () -> new LargeMoltenPillarFeature(PalettedConfiguration.codec(
            LargeDripstoneConfiguration.CODEC, "darkslate")));
        CARVERS.register("block_carver", () -> new BlockCarver(BlockCarverConfiguration.CODEC));
    }

    private WorldgenFeatures() {}

    public static void register(IEventBus bus) {
        FEATURES.register(bus);
        CARVERS.register(bus);
    }
}

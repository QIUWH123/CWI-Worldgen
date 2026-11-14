package net.quiwh.cwiworldgen.terrain;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource;
import net.quiwh.cwiworldgen.hydrology.current.CurrentFieldBuilder;
import net.quiwh.cwiworldgen.hydrology.current.CurrentFieldCache;
import net.quiwh.cwiworldgen.hydrology.current.CurrentFieldPayload;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeGenerationProfileTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void savedMarkersRetainLegacyAndRoundtripNewGeneration(GameTestHelper helper) {
        var codec = ReferenceTerrainDensity.CODEC.codec();
        var old = codec.parse(JsonOps.INSTANCE, JsonParser.parseString("{\"field\":\"height\"}")).getOrThrow();
        helper.assertTrue(old.generationVersion() == 1, "A saved legacy marker silently changed routing");
        for (int version : new int[]{2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22}) {
            var current = codec.parse(JsonOps.INSTANCE, JsonParser.parseString(
                    "{\"field\":\"height\",\"generation_version\":" + version + "}")).getOrThrow();
            var encoded = codec.encodeStart(JsonOps.INSTANCE, current).getOrThrow();
            helper.assertTrue(encoded.getAsJsonObject().get("generation_version").getAsInt() == version,
                    "Generation identity was lost during save encoding");
        }
        for (int version : new int[]{0, 23}) {
            helper.assertTrue(codec.parse(JsonOps.INSTANCE,
                    JsonParser.parseString("{\"field\":\"height\",\"generation_version\":" + version + "}")).error().isPresent(),
                    "Unsupported generation identity was accepted");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void caveAndLandscapeMarkersRetainEverySavedGeneration(GameTestHelper helper) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var caveCodec = BoundedCaveBiomeSource.CODEC.codec();
        var landscapeCodec = ReferenceLandscapeDensity.CODEC.codec();
        var source = caveCodec.encodeStart(ops,
                (BoundedCaveBiomeSource) currentGenerator(helper).getBiomeSource()).getOrThrow().getAsJsonObject();
        for (int version : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22}) {
            var saved = source.deepCopy();
            if (version == 1) saved.remove("generation_version");
            else saved.addProperty("generation_version", version);
            var caves = caveCodec.parse(ops, saved).getOrThrow();
            var reloadedCaves = caveCodec.parse(ops, caveCodec.encodeStart(ops, caves).getOrThrow()).getOrThrow();
            helper.assertTrue(reloadedCaves.generationVersion() == version,
                    "Cave marker lost saved generation " + version);
            var landscape = new ReferenceLandscapeDensity(caves, version);
            var reloadedLandscape = landscapeCodec.parse(ops,
                    landscapeCodec.encodeStart(ops, landscape).getOrThrow()).getOrThrow();
            helper.assertTrue(reloadedLandscape.generationVersion() == version
                            && reloadedLandscape.caves().generationVersion() == version,
                    "Landscape marker lost its coherent saved generation " + version);
        }
        for (int version : new int[]{0, 23}) {
            var saved = source.deepCopy();
            saved.addProperty("generation_version", version);
            helper.assertTrue(caveCodec.parse(ops, saved).error().isPresent(),
                    "Unsupported cave generation identity was accepted");
            var landscape = landscapeCodec.encodeStart(ops, new ReferenceLandscapeDensity(
                    (BoundedCaveBiomeSource) currentGenerator(helper).getBiomeSource(), 12)).getOrThrow().getAsJsonObject();
            landscape.addProperty("generation_version", version);
            helper.assertTrue(landscapeCodec.parse(ops, landscape).error().isPresent(),
                    "Unsupported landscape generation identity was accepted");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void aRouterRejectsMixedTerrainProfilesBeforeBinding(GameTestHelper helper) {
        var generator = currentGenerator(helper);
        var router = generator.generatorSettings().value().noiseRouter();
        var mixed = router.mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction function) {
                if (function instanceof ReferenceTerrainDensity marker)
                    return new ReferenceTerrainDensity(marker.field(), marker.field() == ReferenceTerrainDensity.Field.HEIGHT_OFFSET ? 2 : 4);
                if (function instanceof ReferenceLandscapeDensity marker)
                    return new ReferenceLandscapeDensity(marker.caves(), 4);
                return function;
            }
        });
        boolean rejected = false;
        try { TerrainGenerationProfile.fromRouter(mixed); }
        catch (IllegalArgumentException expected) { rejected = expected.getMessage().contains("Mixed CWI generation"); }
        helper.assertTrue(rejected, "One world was allowed to bind conflicting terrain and river providers");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void savedGeneratorProfileSurvivesRegistryResourceUpdates(GameTestHelper helper) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        var current = currentGenerator(helper);
        var saved = ChunkGenerator.CODEC.encodeStart(ops, current).getOrThrow().getAsJsonObject();
        helper.assertTrue(saved.getAsJsonObject("biome_source").get("generation_version").getAsInt() == 22,
                "New world did not serialize its terrain-constrained river identity inline");
        var reloaded = (NoiseBasedChunkGenerator) ChunkGenerator.CODEC.parse(ops, saved).getOrThrow();
        helper.assertTrue(TerrainGenerationProfile.fromRouter(reloaded.generatorSettings().value().noiseRouter()) == 22,
                "Current saved generation changed after reload");
        for (int version : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19}) {
            var previous = saved.deepCopy();
            // A previous level.dat can retain this registry holder after resources update.
            previous.addProperty("settings", "minecraft:overworld");
            if (version == 1) previous.getAsJsonObject("biome_source").remove("generation_version");
            else previous.getAsJsonObject("biome_source").addProperty("generation_version", version);
            var legacy = (NoiseBasedChunkGenerator) ChunkGenerator.CODEC.parse(ops, previous).getOrThrow();
            helper.assertTrue(((BoundedCaveBiomeSource) legacy.getBiomeSource()).generationVersion() == version,
                    "Saved biome-source identity changed for profile " + version);
            helper.assertTrue(TerrainGenerationProfile.fromRouter(legacy.generatorSettings().value().noiseRouter()) == version,
                    "Updated registry resources overrode saved profile " + version);
            var twice = (NoiseBasedChunkGenerator) ChunkGenerator.CODEC.parse(ops,
                    ChunkGenerator.CODEC.encodeStart(ops, legacy).getOrThrow()).getOrThrow();
            helper.assertTrue(TerrainGenerationProfile.fromRouter(twice.generatorSettings().value().noiseRouter()) == version,
                    "Saved profile changed on the second save/reload: " + version);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void compactTerrainRetainsDirectedCurrentProtocol(GameTestHelper helper) {
        assertDirectedCurrentProtocol(helper, "compact_wasteland_test", 3);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void naturalRiversRetainDirectedCurrentProtocol(GameTestHelper helper) {
        assertDirectedCurrentProtocol(helper, "natural_wasteland_test", 4);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void refinedRiversRetainDirectedCurrentProtocol(GameTestHelper helper) {
        assertDirectedCurrentProtocol(helper, "refined_wasteland_test", 5);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void streamsRiversRetainDirectedCurrentProtocol(GameTestHelper helper) {
        assertDirectedCurrentProtocol(helper, "streams_wasteland_test", 6);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void compactNaturalRiversRetainDirectedCurrentProtocol(GameTestHelper helper) {
        assertDirectedCurrentProtocol(helper, "compact_natural_wasteland_test", 7);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void naturalGeometryRetainsDirectedCurrentProtocol(GameTestHelper helper) {
        assertDirectedCurrentProtocol(helper, "natural_geometry_wasteland_test", 8);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void riverFrequencyRetainsDirectedCurrentProtocol(GameTestHelper helper) {
        assertDirectedCurrentProtocol(helper, "river_frequency_wasteland_test", 9);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void waterLandscapeUsesCurrentProtocol(GameTestHelper helper) {
        assertDirectedCurrentProtocol(helper, "water_landscape_wasteland_test", 10);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void mountainRangesUseCurrentProtocol(GameTestHelper helper) {
        assertDirectedCurrentProtocol(helper, "mountain_ranges_wasteland_test", 11);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void coordinatedTerrainUsesCurrentProtocol(GameTestHelper helper) {
        assertDirectedCurrentProtocol(helper, "coordinated_wasteland_test", 12);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 6000)
    public static void sceneryShapesUseCurrentProtocol(GameTestHelper helper) {
        assertDirectedCurrentProtocol(helper, "scenery_shapes_wasteland_test", 13);
        helper.succeed();
    }

    private static void assertDirectedCurrentProtocol(GameTestHelper helper, String dimension, int generationVersion) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("cwi_worldgen", dimension)));
        var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null && terrain.generationVersion() == generationVersion,
                "Test dimension did not bind profile " + generationVersion);
        boolean positive = false;
        int checked = 0;
        for (var reach : terrain.hydrologySnapshot(0, 0).reaches()) {
            var middle = reach.points().get(reach.points().size() / 2);
            int x = (int) Math.round(middle.x()), z = (int) Math.round(middle.z());
            var sample = terrain.watershedSample(x, z);
            if (!sample.wet() || sample.basin() || sample.island()) continue;
            var field = CurrentFieldBuilder.build(terrain, new ChunkPos(x >> 4, z >> 4), 1);
            if (field.activeColumns() > 0) {
                // The current wire protocol is independent of saved terrain geometry versions.
                var packet = new CurrentFieldPayload(level.dimension().location(), 2, 1, field.chunkPos(), field);
                var cache = new CurrentFieldCache(4, 8);
                cache.enter(level.dimension().location().toString());
                helper.assertTrue(cache.apply(packet.dimension().toString(), packet.generation(), packet.revision(),
                                packet.chunkPos(), packet.field()) && cache.get(packet.chunkPos()).activeColumns() > 0,
                        "Current metadata failed the existing wire protocol for terrain profile " + generationVersion);
                positive = true;
                System.out.println("CWI_GENERATION_CURRENT=profile:" + generationVersion
                        + ",protocol:2,active_columns:" + field.activeColumns());
                break;
            }
            if (++checked >= 8) break;
        }
        helper.assertTrue(positive, "Terrain profile " + generationVersion
                + " produced no positive completed directed current field");
    }

    private static NoiseBasedChunkGenerator currentGenerator(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        return (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
    }
}

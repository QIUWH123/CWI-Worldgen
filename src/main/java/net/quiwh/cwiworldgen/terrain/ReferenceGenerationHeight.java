package net.quiwh.cwiworldgen.terrain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.ContinentalHydrology;
import org.jetbrains.annotations.Nullable;

/** Bounds block filling, leaving the full interpolation/aquifer domain intact. */
public final class ReferenceGenerationHeight {
    private static final double MIN_FACTOR = .625;
    private static final float MIN_AQUIFER_FLOOR = 16F / 256F;

    private ReferenceGenerationHeight() {}

    /** Only the known direct surface branch provides the required upper bound. */
    public static boolean supports(NoiseGeneratorSettings settings) {
        try {
            // The full-chunk adapter and the preliminary-surface sample lattice
            // are part of the bound, not just the final density expression.
            NoiseSettings noise = settings.noiseSettings();
            if (noise.minY() != -64 || noise.height() != 448
                    || noise.getCellWidth() != 4 || noise.getCellHeight() != 8) return false;
            // Biome holders inside the landscape codec need registry-aware ops.
            // This proof only needs its subtractive composition, so replace the
            // explicit marker with its upper value before encoding the graph.
            int[] landscapes = {0};
            DensityFunction boundedGraph = settings.noiseRouter().finalDensity().mapAll(new DensityFunction.Visitor() {
                @Override public DensityFunction apply(DensityFunction function) {
                    if (function instanceof ReferenceLandscapeDensity) {
                        landscapes[0]++;
                        return DensityFunctions.constant(64.);
                    }
                    return function;
                }
            });
            JsonElement encoded = DensityFunction.HOLDER_HELPER_CODEC.encodeStart(JsonOps.INSTANCE,
                    boundedGraph).result().orElse(null);
            JsonObject root = typed(encoded, "minecraft:min");
            if (root == null) return false;
            // A verified subtractive landscape field cannot add solid or water
            // above the original envelope. Keep checking the entire base graph.
            if (landscapes[0] > 0) {
                if (landscapes[0] != 1 || !number(root.get("argument2"), 64.)) return false;
                root = typed(root.get("argument1"), "minecraft:min");
                if (root == null) return false;
            }
            JsonObject squeeze = typed(root.get("argument1"), "minecraft:squeeze");
            if (squeeze == null) return false;
            JsonObject scale = typed(squeeze.get("argument"), "minecraft:mul");
            if (scale == null || !scale.get("argument1").isJsonPrimitive()
                    || scale.get("argument1").getAsDouble() != .64) return false;
            JsonObject range = typed(scale.get("argument2"), "minecraft:range_choice");
            return range != null && directSurface(range.get("input"))
                    && range.get("when_in_range").equals(range.get("input"))
                    && range.get("min_inclusive").getAsDouble() == -1_000_000.
                    && range.get("max_exclusive").getAsDouble() == 1.5625
                    && (!settings.isAquifersEnabled() || preliminarySurface(
                            DensityFunction.HOLDER_HELPER_CODEC.encodeStart(JsonOps.INSTANCE,
                                    settings.noiseRouter().initialDensityWithoutJaggedness()).result().orElse(null),
                            range.get("input")));
        } catch (RuntimeException unsupportedGraph) {
            return false;
        }
    }

    private static boolean preliminarySurface(JsonElement value, JsonElement surface) {
        JsonObject slide = typed(value, "minecraft:add");
        if (slide == null || !number(slide.get("argument1"), .1171875)) return false;
        JsonObject product = typed(slide.get("argument2"), "minecraft:mul");
        if (product == null) return false;
        JsonObject fade = typed(product.get("argument1"), "minecraft:y_clamped_gradient");
        if (fade == null || !number(fade.get("from_y"), -64.) || !number(fade.get("to_y"), -40.)
                || !number(fade.get("from_value"), 0.) || !number(fade.get("to_value"), 1.)) return false;
        JsonObject offset = typed(product.get("argument2"), "minecraft:add");
        if (offset == null || !number(offset.get("argument1"), -.1171875)) return false;
        JsonObject clamp = typed(offset.get("argument2"), "minecraft:clamp");
        if (clamp == null || !number(clamp.get("min"), -64.) || !number(clamp.get("max"), 64.)) return false;
        JsonObject reduced = typed(clamp.get("input"), "minecraft:add");
        return reduced != null && surface.equals(reduced.get("argument1"))
                && number(reduced.get("argument2"), -90. / 128.);
    }

    /** JsonOps expands registry holders, so overridden dependencies are checked too. */
    private static boolean directSurface(JsonElement value) {
        JsonObject slope = typed(value, "minecraft:mul");
        if (slope == null || !number(slope.get("argument1"), 4.)) return false;
        JsonObject quarter = typed(slope.get("argument2"), "minecraft:quarter_negative");
        JsonObject product = quarter == null ? null : typed(quarter.get("argument"), "minecraft:mul");
        if (product == null || !positiveFactor(product.get("argument1"))) return false;
        JsonObject depth = typed(product.get("argument2"), "minecraft:add");
        if (depth == null) return false;
        JsonObject gradient = typed(depth.get("argument1"), "minecraft:y_clamped_gradient");
        JsonObject height = typed(depth.get("argument2"), "cwi_worldgen:reference_terrain");
        return gradient != null && number(gradient.get("from_y"), -64.)
                && number(gradient.get("to_y"), 384.) && number(gradient.get("from_value"), 1.5)
                && number(gradient.get("to_value"), -2.) && height != null
                && height.get("field").getAsString().equals("height_offset");
    }

    private static boolean positiveFactor(JsonElement value) {
        if (value == null || !value.isJsonObject()) return positiveConstant(value);
        JsonObject object = value.getAsJsonObject();
        String type = object.get("type").getAsString();
        if (type.equals("minecraft:flat_cache") || type.equals("minecraft:cache_2d")) {
            return positiveFactor(object.get("argument"));
        }
        if (type.equals("minecraft:spline")) return positiveSpline(object.get("spline"));
        // Native factor blends 10 toward the spline. Blending itself is disabled
        // before height optimization, so alpha is exactly one here.
        if (!type.equals("minecraft:add") || !number(object.get("argument1"), 10.)) return false;
        JsonObject blend = typed(object.get("argument2"), "minecraft:mul");
        if (blend == null || typed(blend.get("argument1"), "minecraft:blend_alpha") == null) return false;
        JsonObject offset = typed(blend.get("argument2"), "minecraft:add");
        return offset != null && number(offset.get("argument1"), -10.)
                && positiveFactor(offset.get("argument2"));
    }

    private static boolean positiveSpline(JsonElement value) {
        if (value == null || !value.isJsonObject()) return positiveConstant(value);
        JsonObject spline = value.getAsJsonObject();
        if (!finiteCoordinate(spline.get("coordinate")) || !spline.has("points")
                || spline.getAsJsonArray("points").isEmpty()) return false;
        float previous = Float.NEGATIVE_INFINITY;
        for (JsonElement element : spline.getAsJsonArray("points")) {
            JsonObject point = element.getAsJsonObject();
            float location = point.get("location").getAsFloat();
            if (!Float.isFinite(location) || Math.abs(location) > 1_000_000F || location <= previous
                    || !number(point.get("derivative"), 0.) || !positiveSpline(point.get("value"))) return false;
            previous = location;
        }
        return true;
    }

    private static boolean finiteCoordinate(JsonElement value) {
        JsonObject field = typed(value, "cwi_worldgen:reference_terrain");
        if (field != null) {
            String name = field.get("field").getAsString();
            return name.equals("continent") || name.equals("erosion") || name.equals("weirdness");
        }
        JsonObject scale = typed(value, "minecraft:mul");
        if (scale == null || !number(scale.get("argument1"), -3.)) return false;
        JsonObject offset = typed(scale.get("argument2"), "minecraft:add");
        if (offset == null || !number(offset.get("argument1"), -1. / 3.)) return false;
        JsonObject outer = typed(offset.get("argument2"), "minecraft:abs");
        JsonObject innerOffset = outer == null ? null : typed(outer.get("argument"), "minecraft:add");
        if (innerOffset == null || !number(innerOffset.get("argument1"), -2. / 3.)) return false;
        JsonObject inner = typed(innerOffset.get("argument2"), "minecraft:abs");
        JsonObject ridge = inner == null ? null : typed(inner.get("argument"), "cwi_worldgen:reference_terrain");
        return ridge != null && ridge.get("field").getAsString().equals("weirdness");
    }

    private static boolean positiveConstant(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && value.getAsDouble() >= MIN_FACTOR && value.getAsDouble() <= 10.;
    }

    private static boolean number(JsonElement value, double expected) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && value.getAsDouble() == expected;
    }

    public static int height(NoiseSettings noise, NoiseGeneratorSettings settings,
            @Nullable ReferenceTerrain terrain, ChunkPos chunk, Blender blender,
            StructureManager structures, boolean supported) {
        if (!supported || terrain == null || blender != Blender.empty()
                || !noise.equals(settings.noiseSettings())) return noise.height();
        // Beardifiers and modded terrain adjustments can add solid geometry above
        // the sampled surface. Keep the complete range around affected structures.
        if (!structures.startsForStructure(chunk, structure ->
                structure.terrainAdaptation() != TerrainAdjustment.NONE).isEmpty()) {
            return noise.height();
        }
        int ceiling = terrainCeiling(terrain, chunk, settings.seaLevel(), settings.isAquifersEnabled());
        return ceiling == Integer.MAX_VALUE ? noise.height() : alignedHeight(noise, ceiling);
    }

    public static int terrainCeiling(ReferenceTerrain terrain, ChunkPos chunk, int seaLevel) {
        return terrainCeiling(terrain, chunk, seaLevel, true);
    }

    private static int terrainCeiling(ReferenceTerrain terrain, ChunkPos chunk, int seaLevel, boolean aquifers) {
        int ceiling = seaLevel;
        var levels = terrain.levels();
        // All three nearest aquifer centers lie in these chunks: native center
        // candidates span [-16, +25] relative to the owning chunk's origin.
        int radius = aquifers ? 1 : 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                var reader = terrain.prepareChunk(chunk.x + dx, chunk.z + dz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        Cell cell = reader.getCell(x, z);
                        if (!Float.isFinite(cell.height)) return Integer.MAX_VALUE;
                        // With factor >= .625, this floor makes the canonical
                        // initial density exceed .390625 at Y=-48 (>.4104).
                        // Otherwise native preliminarySurfaceLevel can return
                        // Integer.MAX_VALUE, which cannot bound aquifer water.
                        if (aquifers && cell.height < MIN_AQUIFER_FLOOR) return Integer.MAX_VALUE;
                        ceiling = Math.max(ceiling, (int) Math.ceil(cell.height * 256.));
                        if (dx == 0 && dz == 0
                                && (cell.terrain.isRiver() || cell.terrain.isLake() || cell.terrain.isWetland())
                                && cell.riverWaterLevel > 0) {
                            if (cell.cwiPlannedWater && !Float.isFinite(cell.cwiWaterHeight)) return Integer.MAX_VALUE;
                            ceiling = Math.max(ceiling, ReferenceRiverColumn.waterTableY(cell, levels));
                        }
                    }
                }
            }
        }
        // The surface pass can raise a dry bank to an adjacent water table.
        // Include those writes through the same projection used for placement,
        // rather than assuming neighboring ground already bounds them.
        var rivers = new ReferenceRiverColumn.Reader(terrain, chunk.getMinBlockX(), chunk.getMinBlockZ(), 16, 16);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            ceiling = Math.max(ceiling, rivers.sample(chunk.getBlockX(x), chunk.getBlockZ(z)).maximumWriteY());
        }
        return ceiling;
    }

    public static int alignedHeight(NoiseSettings settings, int ceiling) {
        int cell = settings.getCellHeight();
        // Native fluid levels cannot exceed sea level or a preliminary surface.
        // At >=5 blocks above both fluid levels, aquifer pressure is negative
        // and its barrier noise is bypassed, regardless of the auxiliary graphs.
        // The verified 8-block cell plus one also covers float quantization.
        long requested = (long) ceiling + cell + 1 - settings.minY();
        long aligned = Math.floorDiv(requested + cell - 1, cell) * cell;
        return (int) Math.max(cell, Math.min(settings.height(), aligned));
    }

    @Nullable private static JsonObject typed(@Nullable JsonElement value, String type) {
        if (value == null || !value.isJsonObject()) return null;
        JsonObject object = value.getAsJsonObject();
        return object.has("type") && object.get("type").getAsString().equals(type) ? object : null;
    }
}

package net.quiwh.cwiworldgen.structure;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.IntBinaryOperator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.quiwh.cwiworldgen.terrain.ReferenceTerrainDensity;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverColumn;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.hydrology.watershed.EnclosedPondPlanner;

/** Native jigsaw generation with a bounded terrain survey over the selected pieces. */
public final class TerrainCheckedJigsawStructure extends Structure {
    public static final MapCodec<TerrainCheckedJigsawStructure> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            JigsawStructure.CODEC.codec().fieldOf("jigsaw").forGetter(value -> value.jigsaw),
            Codec.intRange(0, 32).fieldOf("max_height_difference").forGetter(value -> value.maxHeightDifference),
            Codec.intRange(0, 16).optionalFieldOf("max_water_depth", 1).forGetter(value -> value.maxWaterDepth)
    ).apply(instance, TerrainCheckedJigsawStructure::new));

    private final JigsawStructure jigsaw;
    private final int maxHeightDifference;
    private final int maxWaterDepth;

    public TerrainCheckedJigsawStructure(JigsawStructure jigsaw, int maxHeightDifference, int maxWaterDepth) {
        super(jigsaw.getModifiedStructureSettings());
        this.jigsaw = jigsaw;
        this.maxHeightDifference = maxHeightDifference;
        this.maxWaterDepth = maxWaterDepth;
    }

    @Override
    public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        if (referenceTerrain(context) != null) {
            return ReferenceTerrainDensity.withAccurateTerrain(() -> findProjectedGenerationPoint(context));
        }
        return findProjectedGenerationPoint(context);
    }

    private Optional<GenerationStub> findProjectedGenerationPoint(GenerationContext context) {
        var candidate = jigsaw.findGenerationPoint(context);
        if (candidate.isEmpty()) return Optional.empty();
        var pieces = candidate.get().getPiecesBuilder();
        if (pieces.isEmpty() || !acceptsTerrain(context, pieces.getBoundingBox(), maxHeightDifference, maxWaterDepth))
            return Optional.empty();
        ReferenceTerrain terrain = referenceTerrain(context);
        if (terrain != null) {
            var anchor = candidate.get().position();
            int projectedSurface = context.chunkGenerator().getFirstOccupiedHeight(anchor.getX(), anchor.getZ(),
                    Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
            if (sampleColumn(context, terrain, anchor.getX(), anchor.getZ()).floor() > projectedSurface) {
                // Later retaining banks must not bury a start projected onto the density surface.
                return Optional.empty();
            }
        }
        // Keep the exact selected pieces; running the delegate twice would advance its RNG.
        return Optional.of(new GenerationStub(candidate.get().position(), Either.right(pieces)));
    }

    /** Surveys the same filtered columns used by native jigsaw projection, including later river fills. */
    public static boolean acceptsTerrain(GenerationContext context, BoundingBox box,
            int maxHeightDifference, int maxWaterDepth) {
        ReferenceTerrain terrain = referenceTerrain(context);
        if (terrain != null) {
            if (terrain.generationVersion() >= 12) {
                // Inclusive 2*128+1 vanilla jigsaw range; reject unexpected oversized templates
                // before bounded planner work. Historical profiles retain their original survey.
                long width = (long) box.maxX() - box.minX() + 1;
                long depth = (long) box.maxZ() - box.minZ() + 1;
                if (width > EnclosedPondPlanner.MAX_RECTANGLE_SPAN || depth > EnclosedPondPlanner.MAX_RECTANGLE_SPAN
                        || terrain.pondRectangleIntersectsWet(box.minX(), box.minZ(), box.maxX(), box.maxZ())) return false;
            }
            if (terrain.landscape() != null && terrain.landscape().intersectsSurfaceFormation(box)) return false;
            return ReferenceTerrainDensity.withAccurateTerrain(() -> {
                Map<Long, TerrainColumn> columns = new HashMap<>();
                BiFunction<Integer, Integer, TerrainColumn> column = (x, z) -> columns.computeIfAbsent(
                        ChunkPos.asLong(x, z), ignored -> sampleColumn(context, terrain, x, z));
                return acceptsTerrain(box, (x, z) -> column.apply(x, z).surface(),
                        (x, z) -> column.apply(x, z).floor(), maxHeightDifference, maxWaterDepth);
            });
        }
        return acceptsTerrain(box,
                (x, z) -> context.chunkGenerator().getFirstOccupiedHeight(x, z,
                        Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState()),
                (x, z) -> context.chunkGenerator().getFirstOccupiedHeight(x, z,
                        Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState()),
                maxHeightDifference, maxWaterDepth);
    }

    private static ReferenceTerrain referenceTerrain(GenerationContext context) {
        return (Object) context.randomState() instanceof TerrainRandomState state ? state.cwiWorldgen$terrain() : null;
    }

    private static TerrainColumn sampleColumn(GenerationContext context, ReferenceTerrain terrain, int x, int z) {
        int surface = context.chunkGenerator().getFirstOccupiedHeight(x, z,
                Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
        int floor = context.chunkGenerator().getFirstOccupiedHeight(x, z,
                Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
        int maximumY = context.heightAccessor().getMaxBuildHeight() - 1;
        ReferenceRiverColumn column = ReferenceRiverColumn.sample(terrain, x, z);
        if (column.hasWater()) {
            surface = Math.max(surface, Math.min(column.waterY(), maximumY));
        }
        if (column.solidTopY() > column.groundY()) {
            floor = Math.max(floor, Math.min(column.solidTopY(), maximumY));
            if (!column.waterCell()) surface = Math.max(surface, floor);
        }
        return new TerrainColumn(surface, floor);
    }

    private record TerrainColumn(int surface, int floor) {}

    /** Survey is exposed for deterministic rejection tests without generating arbitrary chunks. */
    public static boolean acceptsTerrain(BoundingBox box, IntBinaryOperator surfaceHeight,
            IntBinaryOperator floorHeight, int maxHeightDifference, int maxWaterDepth) {
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        // Include both ends even for a template whose width is not divisible by eight.
        for (int x = box.minX(); ; x = Math.min(x + 8, box.maxX())) {
            for (int z = box.minZ(); ; z = Math.min(z + 8, box.maxZ())) {
                int surface = surfaceHeight.applyAsInt(x, z);
                int floor = floorHeight.applyAsInt(x, z);
                minimum = Math.min(minimum, floor);
                maximum = Math.max(maximum, floor);
                if (maximum - minimum > maxHeightDifference || surface - floor > maxWaterDepth) return false;
                if (z == box.maxZ()) break;
            }
            if (x == box.maxX()) break;
        }
        return true;
    }

    @Override
    public StructureType<?> type() {
        return WorldgenStructures.TERRAIN_CHECKED.get();
    }
}

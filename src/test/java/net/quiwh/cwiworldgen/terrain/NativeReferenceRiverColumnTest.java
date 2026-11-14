package net.quiwh.cwiworldgen.terrain;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.heightmap.Levels;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.river.RiverCarverSettings.RiverZone;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.RiverTestTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.terrain.TerrainType;

/** Hand-derived source/flow/retaining contracts shared by all river consumers. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeReferenceRiverColumnTest {
    private static final Levels LEVELS = new Levels(256, 63);

    @GameTest(template = "empty")
    public static void sourceIntervalsRetainFlatSingleStepAndWaterfallSemantics(GameTestHelper helper) {
        checkWaterColumns();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void dryBanksOceanPlaneAndEmergentBarsRetainPlacementSemantics(GameTestHelper helper) {
        checkDryColumns();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void projectedPlacementMatchesFrozenNativeWaterAndBankBlocks(GameTestHelper helper) {
        long[][] fixtures = {
                {0, 3279, 4260}, {0, -939, -150}, {0, 2280, 3120},
                {42, -1600, -7640}, {0, -5200, 4021}, {0, -4640, -911}
        };
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int compared = 0, sources = 0, retaining = 0;
        for (long[] fixture : fixtures) {
            try (var terrain = new ReferenceTerrain(fixture[0])) {
                ChunkPos chunkPos = new ChunkPos((int) fixture[1] >> 4, (int) fixture[2] >> 4);
                ProtoChunk before = emptyChunk(helper, chunkPos), after = emptyChunk(helper, chunkPos);
                int originX = chunkPos.getMinBlockX(), originZ = chunkPos.getMinBlockZ();
                var columns = new ReferenceRiverColumn.Reader(terrain, originX, originZ, 16, 16);
                for (int x = originX; x < originX + 16; x++) for (int z = originZ; z < originZ + 16; z++) {
                    ReferenceRiverColumn column = columns.sample(x, z);
                    helper.assertTrue(column.equals(ReferenceRiverColumn.sample(terrain, x, z)),
                            "Pinned projection differs across a native chunk edge at " + x + "," + z);
                    for (int y = before.getMinBuildHeight(); y <= Math.min(column.groundY(), before.getMaxBuildHeight() - 1); y++) {
                        before.setBlockState(pos.set(x, y, z), Blocks.STONE.defaultBlockState(), false);
                        after.setBlockState(pos, Blocks.STONE.defaultBlockState(), false);
                    }
                    if (column.hasWater()) sources++;
                    if (column.retainingTopY() > column.groundY()) retaining++;
                }
                LegacyRiverSurface.place(before, helper.getLevel().getBiomeManager(), terrain);
                ReferenceRiverSurface.place(after, helper.getLevel().getBiomeManager(), terrain);
                helper.assertTrue(Arrays.equals(before.getPostProcessing(), after.getPostProcessing()),
                        "Shared projection changed scheduled water postprocessing for " + chunkPos);
                for (int x = originX; x < originX + 16; x++) for (int z = originZ; z < originZ + 16; z++) {
                    ReferenceRiverColumn column = columns.sample(x, z);
                    for (int y = before.getMinBuildHeight(); y < before.getMaxBuildHeight(); y++) {
                        pos.set(x, y, z);
                        helper.assertTrue(before.getBlockState(pos) == after.getBlockState(pos),
                                "Shared projection changed native placement at " + pos + " seed=" + fixture[0]);
                        if (y >= column.sourceMinY() && y <= column.sourceMaxY()) {
                            helper.assertTrue(after.getBlockState(pos).is(Blocks.WATER)
                                            && after.getBlockState(pos).getFluidState().isSource(),
                                    "Projected gasket interval contains a flowing, ice or solid block at " + pos);
                        }
                        compared++;
                    }
                }
            }
        }
        helper.assertTrue(sources > 0, "Frozen native fixtures missed actual river/lake placement");
        System.out.println("CWI_RIVER_COLUMN_PARITY=blocks:" + compared + ",wet_columns:" + sources
                + ",retaining_columns:" + retaining + ",fixtures:" + fixtures.length);
        helper.succeed();
    }

    private static ProtoChunk emptyChunk(GameTestHelper helper, ChunkPos position) {
        return new ProtoChunk(position, UpgradeData.EMPTY, helper.getLevel(),
                helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME), null);
    }

    /** Also runs without a server, using the production Cell, Levels and projector. */
    public static void main(String[] args) {
        checkWaterColumns();
        checkDryColumns();
        System.out.println("CWI_RIVER_COLUMN_CONTRACT=PASS");
    }

    private static void checkWaterColumns() {
        Cell river = water(72, 80);
        Cell same = water(71, 80);
        var flat = ReferenceRiverColumn.project(LEVELS, river, same, same, same, same);
        require(flat.hasWater() && flat.groundY() == 72 && flat.placementFloorY() == 72,
                "Flat river lost its original bed");
        require(flat.transition() == ReferenceRiverColumn.Transition.NONE
                && flat.sourceMinY() == 73 && flat.sourceMaxY() == 80,
                "Flat source interval must include its source top");

        var step = ReferenceRiverColumn.project(LEVELS, river, water(70, 79), same, same, same);
        require(step.transition() == ReferenceRiverColumn.Transition.STEP
                && step.placementFloorY() == 72 && step.sourceMinY() == 73 && step.sourceMaxY() == 79,
                "One-block flowing top was counted as a source or bed was adjusted");

        var drop = ReferenceRiverColumn.project(LEVELS, river, water(70, 76), same, same, same);
        require(drop.transition() == ReferenceRiverColumn.Transition.DROP
                && drop.placementFloorY() == 74 && drop.lowestNeighborWaterY() == 76,
                "Waterfall midpoint adjustment changed");
        require(drop.sourceMinY() == 80 && drop.sourceMaxY() == 80 && drop.solidTopY() == 76,
                "Waterfall face must flow above its lower retaining foot, with a source only at the lip");

        Cell lake = water(70, 80);
        lake.terrain = RiverTestTerrain.lake();
        var lakeColumn = ReferenceRiverColumn.project(LEVELS, lake, same, same, same, same);
        require(lakeColumn.hasWater() && lakeColumn.sourceMinY() == 71 && lakeColumn.sourceMaxY() == 80,
                "Lake lost river-compatible source projection");

        var oceanClamp = ReferenceRiverColumn.project(LEVELS, water(55, 60), same, same, same, same);
        require(oceanClamp.waterY() == 62 && oceanClamp.waterTableY() == 60
                && oceanClamp.placementFloorY() == 55 && oceanClamp.sourceMinY() == 62,
                "Below-ocean source interval must retain the gasket ocean clamp");
    }

    private static void checkDryColumns() {
        Cell bank = water(75, 80);
        bank.terrain = TerrainType.HILLS;
        bank.riverZone = RiverZone.Banks;
        Cell neighbor = water(76, 85);
        var column = ReferenceRiverColumn.project(LEVELS, bank, neighbor, bank, bank, bank);
        require(!column.hasWater() && column.retainingTopY() == 85 && column.solidTopY() == 85,
                "Dry bank must retain its highest adjacent river water level");
        require(column.sourceMinY() > column.sourceMaxY(), "Dry bank acquired source water");

        var ownTable = ReferenceRiverColumn.project(LEVELS, bank, bank, bank, bank, bank);
        require(ownTable.retainingTopY() == 80, "Bank lost its own river-zone retaining fill");
        bank.height = 62 / 256F;
        var oceanPlane = ReferenceRiverColumn.project(LEVELS, bank, bank, bank, bank, bank);
        require(oceanPlane.retainingTopY() == 62, "Ocean-plane bank acquired inland table fill");
        bank.height = 61 / 256F;
        var submergedDry = ReferenceRiverColumn.project(LEVELS, bank, neighbor, bank, bank, bank);
        require(submergedDry.retainingTopY() == 61, "Below-ocean dry land acquired retaining fill");

        Cell bar = water(81, 80);
        var emerged = ReferenceRiverColumn.project(LEVELS, bar, neighbor, neighbor, neighbor, neighbor);
        require(!emerged.hasWater() && emerged.retainingTopY() == 81
                && emerged.sourceMinY() > emerged.sourceMaxY(),
                "An emerged river bar must stay dry without being raised to its neighbor's water");
    }

    private static Cell water(int groundY, int waterY) {
        Cell cell = new Cell();
        cell.height = groundY / 256F;
        cell.waterTable = 1F;
        cell.globalContinentScale = 4000F;
        cell.continentSizeModifier = (waterY - 62) / 128F;
        cell.riverWaterLevel = 1F;
        cell.terrain = TerrainType.RIVER;
        return cell;
    }

    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}

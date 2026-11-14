package net.quiwh.cwiworldgen.hydrology.current;

import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;

@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeExistingWaterReflowTest {
    @GameTest(template="empty",templateNamespace="cwi_worldgen",timeoutTicks=6000)
    public static void observedWaterReflowChangesOnlyMetadataAndLeavesMissingChunksAbsent(GameTestHelper helper) {
        var level=helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID,"streams_wasteland_test")));
        helper.assertTrue(level!=null,"Missing profile6 native dimension");
        BlockPos center=new BlockPos(200,304,-200);
        var chunk=level.getChunk(center);
        // An artificial, exposed level basin with a connected two-block-lower outlet.
        // All fixture writes are explicit test setup inside a single loaded chunk.
        for(int dz=-4;dz<=4;dz++)for(int dx=-4;dx<=4;dx++)for(int y=298;y<=307;y++) {
            BlockPos pos=new BlockPos(center.getX()+dx,y,center.getZ()+dz);
            chunk.setBlockState(pos,y==298?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),false);
        }
        for(int dz=-2;dz<=2;dz++)for(int dx=-3;dx<=3;dx++)for(int y=299;y<=(dx==3?302:304);y++)
            chunk.setBlockState(new BlockPos(center.getX()+dx,y,center.getZ()+dz),Blocks.WATER.defaultBlockState(),false);
        var before=new LinkedHashMap<BlockPos,BlockState>();
        for(int dz=-4;dz<=4;dz++)for(int dx=-4;dx<=4;dx++)for(int y=298;y<=307;y++) {
            BlockPos pos=new BlockPos(center.getX()+dx,y,center.getZ()+dz);
            before.put(pos,chunk.getBlockState(pos));
        }
        ExistingWaterReflow.start(level,center,4);
        for(int i=0;i<4;i++)ExistingWaterReflow.tick();
        helper.assertTrue(ExistingWaterReflow.status(level).startsWith("Complete:"),"Incremental reflow did not finish: "+ExistingWaterReflow.status(level));
        var field=CurrentOverrides.get(level).reflowField(chunk.getPos().toLong());
        helper.assertTrue(field!=null && field.column(center.getX()&15,303,center.getZ()&15).active()
                        && field.column(center.getX()&15,303,center.getZ()&15).directionX()>.99,
                "Observed level pool did not route east toward its real lower outlet");
        for(var e:before.entrySet())helper.assertTrue(chunk.getBlockState(e.getKey())==e.getValue(),"Reflow changed terrain/fluid at "+e.getKey());
        BlockPos absent=new BlockPos(1000000,300,1000000);
        helper.assertTrue(level.getChunkSource().getChunkNow(absent.getX()>>4,absent.getZ()>>4)==null,"Missing-chunk fixture is already loaded");
        ExistingWaterReflow.start(level,absent,4);
        for(int i=0;i<4;i++)ExistingWaterReflow.tick();
        helper.assertTrue(level.getChunkSource().getChunkNow(absent.getX()>>4,absent.getZ()>>4)==null,
                "Existing-water scan forced a missing chunk load");
        ExistingWaterReflow.start(level,center,4);
        helper.assertTrue(ExistingWaterReflow.cancel(level) && ExistingWaterReflow.status(level).startsWith("Cancelled"),
                "Reflow cancellation did not preserve the previous complete result");
        helper.assertTrue(CurrentOverrides.get(level).reflowField(chunk.getPos().toLong()).column(
                center.getX()&15,303,center.getZ()&15).active(),"Cancelled job removed the completed currents");
        lateLoadedChunkPreservesUnobservedColumns(helper,level);
        CurrentOverrides.get(level).clearReflow();ServerCurrentFields.invalidate(level);
        System.out.println("CWI_EXISTING_WATER_REFLOW=water_to_outlet:true,block_changes:0,forced_chunk_loads:0,cancel_preserved:true,late_load_preserved:true");
        helper.succeed();
    }

    private static void lateLoadedChunkPreservesUnobservedColumns(GameTestHelper helper,ServerLevel level) {
        // The radius-four disk fits in one chunk. The first 64 cursor positions
        // are skipped while absent, then test setup explicitly loads the chunk.
        // This is an actual service lifecycle test, not a direct mask helper call.
        BlockPos center=new BlockPos(1000008,304,1000008);
        var pos=new ChunkPos(center);
        helper.assertTrue(level.getChunkSource().getChunkNow(pos.x,pos.z)==null,
                "Late-load fixture chunk was already present");
        BlockPos skipped=center.offset(0,-1,-1),observedDry=center.offset(0,-1,3);
        long[][] saved=new long[2][256];
        int skippedIndex=(skipped.getZ()&15)*16+(skipped.getX()&15);
        int dryIndex=(observedDry.getZ()&15)*16+(observedDry.getX()&15);
        saved[0][skippedIndex]=CurrentColumn.of(299,304,1,0,.037,0,0).pack();
        saved[0][dryIndex]=CurrentColumn.of(299,304,0,1,.029,0,0).pack();
        var overrides=CurrentOverrides.get(level);
        overrides.replaceReflow(List.of(new CurrentChunk(pos.toLong(),saved)));
        ExistingWaterReflow.start(level,center,4);
        ExistingWaterReflow.tick();
        helper.assertTrue(ExistingWaterReflow.status(level).startsWith("Scanning 64/81")
                        && level.getChunkSource().getChunkNow(pos.x,pos.z)==null,
                "First incremental scan loaded the missing fixture or completed prematurely");
        var chunk=level.getChunk(pos.x,pos.z); // Explicit fixture loading, never service loading.
        for(int dz=-4;dz<=4;dz++)for(int dx=-4;dx<=4;dx++)for(int y=level.getMinBuildHeight();y<level.getMaxBuildHeight();y++)
            chunk.setBlockState(new BlockPos(center.getX()+dx,y,center.getZ()+dz),Blocks.AIR.defaultBlockState(),false);
        ExistingWaterReflow.tick();
        helper.assertTrue(ExistingWaterReflow.status(level).startsWith("Complete:"),
                "Late-loaded reflow did not finish its remaining columns");
        var result=overrides.reflowField(pos.toLong());
        helper.assertTrue(result!=null && result.packed(0,skippedIndex)==saved[0][skippedIndex],
                "A column skipped while unloaded lost its previously saved current");
        helper.assertTrue(!result.column(observedDry.getX()&15,observedDry.getY(),observedDry.getZ()&15).plannedWater(),
                "A newly observed dry column retained stale water-current metadata");
    }
}

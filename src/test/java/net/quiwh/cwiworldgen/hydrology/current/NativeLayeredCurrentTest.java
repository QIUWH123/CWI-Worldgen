package net.quiwh.cwiworldgen.hydrology.current;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;

@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeLayeredCurrentTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void layeredPacketsKeepStillPrecedenceAndExactLoadedWater(GameTestHelper helper) {
        long[][] layers = new long[4][256];
        layers[0][136] = CurrentColumn.of(60,65,1,0,.04,0,0).pack();
        layers[1][136] = CurrentColumn.of(90,95,0,1,.03,0,0).pack();
        layers[2][136] = CurrentColumn.still(63,64,0,0).pack();
        layers[3][136] = CurrentColumn.of(93,94,-1,0,.02,0,0).pack();
        var field = new CurrentChunk(0,layers);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),helper.getLevel().registryAccess());
        try {
            var packet = new CurrentFieldPayload(ResourceLocation.withDefaultNamespace("overworld"),2,42,0,field);
            CurrentFieldPayload.STREAM_CODEC.encode(buffer,packet);
            helper.assertTrue(buffer.readableBytes()<8400,"Layered current packet escaped its 8KiB field bound");
            var copy=CurrentFieldPayload.STREAM_CODEC.decode(buffer).field();
            helper.assertTrue(copy.layerCount()==4 && copy.column(8,93,8).directionX()<-.99,
                    "Layered packet changed Y-scoped precedence");
            var chunk=new ProtoChunk(new ChunkPos(0,0),UpgradeData.EMPTY,helper.getLevel(),
                    helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME),null);
            for(int y:new int[]{61,63,80,92,93})chunk.setBlockState(new BlockPos(8,y,8),Blocks.WATER.defaultBlockState(),false);
            helper.assertTrue(RuntimeCurrentSampler.sample(copy,chunk,new BlockPos(8,61,8)).active(),"Lower stream lost current");
            helper.assertTrue(!RuntimeCurrentSampler.sample(copy,chunk,new BlockPos(8,63,8)).active(),"Still mask ignored");
            helper.assertTrue(!RuntimeCurrentSampler.sample(copy,chunk,new BlockPos(8,80,8)).plannedWater(),"Stacked gap acquired current");
            helper.assertTrue(RuntimeCurrentSampler.sample(copy,chunk,new BlockPos(8,93,8)).directionX()<-.99,"Authored upper current lost direction");
            chunk.setBlockState(new BlockPos(8,93,8),Blocks.AIR.defaultBlockState(),false);
            helper.assertTrue(!RuntimeCurrentSampler.sample(copy,chunk,new BlockPos(8,93,8)).active(),"Metadata moved dry air");
            var disabled=ServerCurrentFields.strength(copy,0);
            helper.assertTrue(!disabled.column(8,93,8).active() && disabled.column(8,93,8).plannedWater(),
                    "Disabled current policy lost buoyancy bounds or left an authored push active");
        } finally {buffer.release();}
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void incompleteOrExcessiveLayerPacketsAreRejectedBeforeAllocation(GameTestHelper helper) {
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),helper.getLevel().registryAccess());
        try {
            for(int length:new int[]{1,255,257,1025,1280,65535}) {
                buffer.clear();
                buffer.writeResourceLocation(CurrentFieldPayload.PROFILE);
                buffer.writeResourceLocation(ResourceLocation.withDefaultNamespace("overworld"));
                buffer.writeVarInt(2);buffer.writeVarLong(1);buffer.writeLong(0);buffer.writeShort(length);
                boolean rejected=false;
                try {CurrentFieldPayload.STREAM_CODEC.decode(buffer);}catch(IllegalArgumentException expected){rejected=true;}
                helper.assertTrue(rejected,"Invalid layered length reached data allocation: "+length);
            }
        } finally {buffer.release();}
        helper.succeed();
    }
}

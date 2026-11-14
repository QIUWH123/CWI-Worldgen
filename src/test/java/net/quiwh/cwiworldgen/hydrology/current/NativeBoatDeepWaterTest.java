package net.quiwh.cwiworldgen.hydrology.current;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;

@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeBoatDeepWaterTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", batch = "async_currents_boat", timeoutTicks = 120000)
    public static void deepRecoveryCannotCrossAnAirGapToASeparatePond(GameTestHelper helper) {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:streams_wasteland_test")));
        int x = 408, z = 408, bottom = 280, top = 292;
        level.getChunk(x >> 4, z >> 4);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            for (int y = bottom; y <= top + 1; y++)
                level.setBlock(new BlockPos(x + dx,y,z + dz),
                        (y <= top ? Blocks.WATER : Blocks.AIR).defaultBlockState(),2);
        var edits = CurrentOverrides.get(level);
        edits.addPath("deep_boat_test",x-4,z,x+4,z,bottom,top,4,.04);
        ServerCurrentFields.invalidate(level);
        var chunk = new ChunkPos(x >> 4,z >> 4);
        boolean alreadyForced = level.getForcedChunks().contains(chunk.toLong());
        level.setChunkForced(chunk.x,chunk.z,true);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        Runnable cleanup = () -> {
            edits.remove("deep_boat_test");
            ServerCurrentFields.invalidate(level);
            if (!alreadyForced) level.setChunkForced(chunk.x,chunk.z,false);
        };
        awaitField(helper,level,chunk,deadline,cleanup,() -> {
            var boat = new Boat(EntityType.BOAT,level);
            boat.setPos(x+.5,bottom+2,z+.5);
            boat.setDeltaMovement(0,-.1,0);
            helper.assertTrue(Double.isFinite(BoatCurrentSurface.target(boat)),
                    "A boat ten blocks below its connected planned-water surface cannot recover");
            double start = boat.getY();
            boat.tick();
            helper.assertTrue(boat.getY() > start && boat.getDeltaMovement().y <= .15,
                    "Deep-water recovery failed its actual displacement or bounded lift");
            // Current metadata deliberately still spans both bodies: actual water continuity
            // must prevent the upper artificial pond from becoming the lower boat's target.
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
                for (int y = bottom+5; y <= bottom+7; y++)
                    level.setBlock(new BlockPos(x+dx,y,z+dz),Blocks.AIR.defaultBlockState(),2);
            double target = BoatCurrentSurface.target(boat);
            helper.assertTrue(Double.isFinite(target) && target < bottom+5,
                    "Surface following jumped an actual air gap to a separate upper pond");
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
                for (int y = bottom; y <= top+24; y++)
                    level.setBlock(new BlockPos(x+dx,y,z+dz),Blocks.WATER.defaultBlockState(),2);
            helper.assertTrue(!Double.isFinite(BoatCurrentSurface.target(boat)),
                    "The end of a current interval inside continuous water became a false surface");
            edits.addPath("deep_boat_test",x-4,z,x+4,z,bottom,top+24,4,.04);
            ServerCurrentFields.invalidate(level);
            awaitField(helper,level,chunk,deadline,cleanup,() -> {
                helper.assertTrue(!Double.isFinite(BoatCurrentSurface.target(boat)),
                        "A truncated sixteen-block recovery search invented a water surface");
                cleanup.run();
                helper.succeed();
            });
        });
    }

    private static void awaitField(GameTestHelper helper,ServerLevel level,ChunkPos chunk,
                                   long deadline,Runnable cleanup,Runnable ready) {
        ServerCurrentFields.fieldIfLoaded(level,chunk.x,chunk.z);
        helper.runAfterDelay(1,() -> {
            try {
                helper.assertTrue(System.nanoTime() < deadline,"Deep-boat field did not finish within 90 wall-clock seconds");
                ServerCurrentFields.flush();
                if (ServerCurrentFields.fieldIfPresent(level,chunk.x,chunk.z) == null) {
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    awaitField(helper,level,chunk,deadline,cleanup,ready);
                } else ready.run();
            } catch (RuntimeException | Error failure) { cleanup.run(); throw failure; }
        });
    }
}

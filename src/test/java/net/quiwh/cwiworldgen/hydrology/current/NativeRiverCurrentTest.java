package net.quiwh.cwiworldgen.hydrology.current;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverColumn;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

/** Native protocol, real block eligibility, lifecycle, owner and boat integration checks. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeRiverCurrentTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void packetRoundTripPreservesExactColumnsAndRemovalWithoutSeed(GameTestHelper helper) {
        long key = ChunkPos.asLong(-2, 3);
        long[] packed = new long[256];
        packed[0] = CurrentColumn.of(-24, 105, .6, .8, .045, 6, 1).pack();
        packed[255] = CurrentColumn.still(101, 105, 3, 2).pack();
        var packet = new CurrentFieldPayload(ResourceLocation.withDefaultNamespace("overworld"), 2, 17, key, new CurrentChunk(key, packed));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            CurrentFieldPayload.STREAM_CODEC.encode(buffer, packet);
            helper.assertTrue(buffer.readableBytes() <= 2200, "One chunk field exceeded its fixed network budget");
            var decoded = CurrentFieldPayload.STREAM_CODEC.decode(buffer);
            helper.assertTrue(decoded.dimension().equals(packet.dimension()) && decoded.generation() == 2
                            && decoded.revision() == 17 && decoded.chunkPos() == key, "Current packet header changed");
            for (int index = 0; index < 256; index++) helper.assertTrue(decoded.field().packed(index) == packed[index],
                    "Current packet changed an exact block column at " + index);
            helper.assertTrue(decoded.field().column(15, 15).plannedWater() && !decoded.field().column(15, 15).active(),
                    "Still river metadata lost its scoped buoyancy bounds");
            buffer.clear();
            CurrentFieldPayload.STREAM_CODEC.encode(buffer, new CurrentFieldPayload(packet.dimension(), 2, 18, key, null));
            helper.assertTrue(CurrentFieldPayload.STREAM_CODEC.decode(buffer).field() == null, "Unwatch became an empty-but-watched field");
            for (var component : CurrentFieldPayload.class.getRecordComponents()) helper.assertTrue(!component.getName().toLowerCase().contains("seed"),
                    "Seed leaked into current payload API");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void malformedFieldSizesAndProfilesAreRejectedBeforeAllocation(GameTestHelper helper) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            buffer.writeResourceLocation(CurrentFieldPayload.PROFILE);
            buffer.writeResourceLocation(ResourceLocation.withDefaultNamespace("overworld"));
            buffer.writeVarInt(2); buffer.writeVarLong(1); buffer.writeLong(0); buffer.writeShort(65535);
            boolean rejected = false;
            try { CurrentFieldPayload.STREAM_CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Oversized current packet reached an allocation");
            buffer.clear(); buffer.writeResourceLocation(ResourceLocation.withDefaultNamespace("foreign"));
            rejected = false;
            try { CurrentFieldPayload.STREAM_CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Unknown profile was accepted");
            rejected = false;
            try { CurrentColumn.unpack(1L << 63); } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Unknown packed flags were accepted");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void unwatchRewatchDimensionDisconnectAndCacheLimitsKeepPacketOrder(GameTestHelper helper) {
        var cache = new CurrentFieldCache(2, 4);
        var field = new CurrentChunk(0, new long[256]);
        cache.enter("minecraft:overworld");
        helper.assertTrue(cache.apply("minecraft:overworld", 2, 10, 0, field), "Initial watch lost its field");
        helper.assertTrue(cache.apply("minecraft:overworld", 2, 12, 0, null), "Unwatch did not remove its field");
        helper.assertTrue(!cache.apply("minecraft:overworld", 2, 11, 0, field) && cache.get(0) == null,
                "Late build resurrected an unwatched chunk");
        helper.assertTrue(cache.apply("minecraft:overworld", 2, 14, 0, field)
                        && !cache.apply("minecraft:overworld", 2, 13, 0, null), "Old unwatch deleted a rewatched field");
        helper.assertTrue(!cache.apply("minecraft:the_nether", 2, 15, 0, field)
                        && !cache.apply("minecraft:overworld", 1, 15, 0, field), "Foreign dimension/generation crossed the cache boundary");
        for (int i = 1; i <= 8; i++) cache.apply("minecraft:overworld", 2, 20 + i, i, new CurrentChunk(i, new long[256]));
        helper.assertTrue(cache.size() == 2 && cache.revisionCount() == 4, "Current metadata grew past both explicit bounds");
        cache.enter("minecraft:the_end");
        helper.assertTrue(cache.size() == 0 && cache.revisionCount() == 0, "Dimension change retained current fields");
        cache.clear();
        helper.assertTrue(!cache.apply("minecraft:the_end", 2, 99, 0, field), "Disconnected client accepted a delayed field");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void serverWorkOverflowReleasesMotionDeduplicationMarkers(GameTestHelper helper) {
        var queue = new CurrentWorkQueue(2);
        helper.assertTrue(queue.request(11), "Initial missing runtime field was not queued");
        queue.watch(java.util.UUID.randomUUID(), 12, 1);
        queue.watch(java.util.UUID.randomUUID(), 13, 2);
        helper.assertTrue(!queue.queued(11) && queue.request(11), "Dropped motion build was permanently marked queued");
        helper.assertTrue(queue.size() <= 2 && queue.motionCount() <= 2, "Overload escaped queue bounds");
        while (queue.poll() != null) {}
        helper.assertTrue(queue.motionCount() == 0, "Drained queue retained deduplication markers");
        var shared = new CurrentWorkQueue(3);
        shared.watch(java.util.UUID.randomUUID(), 11, 3);
        shared.request(11);
        shared.poll();
        helper.assertTrue(shared.queued(11), "Unrelated watch build cleared a pending motion task");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void currentRequiresActualVanillaWaterInsideItsPlannedInterval(GameTestHelper helper) {
        var level = helper.getLevel();
        var chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        long[] packed = new long[256];
        packed[8 * 16 + 8] = CurrentColumn.of(80, 100, 1, 0, .04, 0, 0).pack();
        var field = new CurrentChunk(0, packed);
        BlockPos inside = new BlockPos(8, 90, 8);
        chunk.setBlockState(inside, Blocks.WATER.defaultBlockState(), false);
        helper.assertTrue(RuntimeCurrentSampler.sample(field, chunk, inside).active(), "Actual river source water lost its current");
        chunk.setBlockState(inside, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1), false);
        helper.assertTrue(RuntimeCurrentSampler.sample(field, chunk, inside).active(), "Flowing vanilla water lost its river current");
        BlockPos stacked = inside.atY(110);
        chunk.setBlockState(stacked, Blocks.WATER.defaultBlockState(), false);
        helper.assertTrue(!RuntimeCurrentSampler.sample(field, chunk, stacked).active(), "Stacked artificial pond acquired a river current");
        for (var block : new net.minecraft.world.level.block.state.BlockState[]{Blocks.AIR.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)}) {
            chunk.setBlockState(inside, block, false);
            helper.assertTrue(!RuntimeCurrentSampler.sample(field, chunk, inside).active(), "Non-vanilla-water block acquired a current: " + block);
        }
        helper.assertTrue(!RuntimeCurrentSampler.sample(field, null, inside).active()
                        && !RuntimeCurrentSampler.sample(field, chunk, new BlockPos(24, 90, 8)).active(),
                "Absent or unowned chunk granted a current");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void currentHasOneMotionOwnerAndPreservesPaddlingAndVerticalVelocity(GameTestHelper helper) {
        var column = CurrentColumn.of(90, 100, 1, 0, .045, 4, 2);
        var slow = CurrentMotion.push(0, .07, column, 1);
        helper.assertTrue(slow.x() > 0 && slow.x() <= .0036 && slow.z() == .07, "Current acceleration is unbounded or destroys lateral motion");
        helper.assertTrue(CurrentMotion.push(.2, .07, column, 1).x() == .2, "Current prevents faster downstream paddling");
        helper.assertTrue(CurrentMotion.owns(false, false, false, false)
                        && !CurrentMotion.owns(true, false, false, false), "Server-owned entity has two motion owners");
        helper.assertTrue(CurrentMotion.owns(true, true, true, false)
                        && !CurrentMotion.owns(false, true, false, false)
                        && !CurrentMotion.owns(true, true, false, false), "Local player prediction has zero/multiple owners");
        helper.assertTrue(!CurrentMotion.owns(false, false, false, true)
                        && !CurrentMotion.owns(true, false, true, true), "Generic entity path also moves boats");
        helper.assertTrue(CurrentFieldBuilder.profileSpeed(5, 12, .3, 4) > CurrentFieldBuilder.profileSpeed(1, 3, 0, 0)
                        && CurrentFieldBuilder.profileSpeed(100, 100, 100, 100) <= .065,
                "Width/depth/slope/drop profiles do not vary conservatively");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void completedNativeRiverFieldsAreDeterministicAndLegacyFieldsStayEmpty(GameTestHelper helper) {
        try (var terrain = new ReferenceTerrain(0L, 2); var legacy = new ReferenceTerrain(0L)) {
            BlockPos point = findWet(terrain);
            var chunk = new ChunkPos(point);
            var first = CurrentFieldBuilder.build(terrain, chunk, 1);
            var second = CurrentFieldBuilder.build(terrain, chunk, 1);
            helper.assertTrue(first.activeColumns() > 0, "No actual directed completed river columns received current metadata");
            for (int index = 0; index < 256; index++) helper.assertTrue(first.packed(index) == second.packed(index), "Current field depends on query order");
            helper.assertTrue(CurrentFieldBuilder.build(legacy, chunk, 1).activeColumns() == 0, "Current metadata changed legacy generation");
            var still = CurrentFieldBuilder.build(terrain, chunk, 0);
            helper.assertTrue(still.activeColumns() == 0 && still.column(point.getX() & 15, point.getZ() & 15).plannedWater(),
                    "Disabling current strength erased independently scoped river buoyancy");
            // A measured production fixture proves that the client's >=2-block waterfall
            // threshold is reached by completed generation, independently of synthetic packets.
            BlockPos dropPoint = new BlockPos(2902, 0, -3808);
            var dropPlan = ReferenceRiverColumn.sample(terrain, dropPoint.getX(), dropPoint.getZ());
            var dropField = CurrentFieldBuilder.build(terrain, new ChunkPos(dropPoint), 1);
            var drop = dropField.column(dropPoint.getX() & 15, dropPoint.getZ() & 15);
            helper.assertTrue(dropPlan.hasWater() && dropPlan.transition() == ReferenceRiverColumn.Transition.DROP
                            && drop.plannedWater() && drop.active() && drop.drop() >= 2
                            && drop.maxY() == dropPlan.waterY()
                            && dropPlan.sourceMinY() <= drop.maxY() && drop.maxY() <= dropPlan.sourceMaxY(),
                    "Completed production river no longer reaches a real source-water waterfall presentation interval");
            System.out.println("CWI_RUNTIME_CURRENT_FIELD=chunk:" + chunk + ",active_columns:" + first.activeColumns());
            System.out.println("CWI_RUNTIME_CURRENT_DROP=fixture:" + dropPoint.getX() + "," + dropPoint.getZ()
                    + ",water_y:" + drop.maxY() + ",drop:" + drop.drop() + ",planned_source:true");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", batch = "async_currents_river", timeoutTicks = 120000)
    public static void modernBoatMotionIsScopedToRealPlannedWaterAndRuntimeMissesNeverLoadChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:watershed_wasteland_test")));
        var terrain = ServerCurrentFields.terrain(level);
        helper.assertTrue(terrain != null && terrain.generationVersion() == 2, "Modern boat fixture lacks generation version 2");
        int absentX = 62500, absentZ = -62500;
        helper.assertTrue(level.getChunkSource().getChunkNow(absentX, absentZ) == null, "No-load probe was already loaded");
        helper.assertTrue(ServerCurrentFields.fieldIfLoaded(level, absentX, absentZ) == null
                        && level.getChunkSource().getChunkNow(absentX, absentZ) == null,
                "Runtime current query loaded a missing Minecraft chunk");
        BlockPos point = findWet(terrain);
        var planned = ReferenceRiverColumn.sample(terrain, point.getX(), point.getZ());
        int y = Math.max(planned.placementFloorY() + 1, planned.waterY() - 1);
        fillWater(level, point.atY(y), planned.waterY());
        // Cold metadata preparation runs independently; allow real server ticks
        // instead of repeatedly flushing before the worker has had CPU time.
        awaitField(helper, level, new ChunkPos(point), field -> {
            var column = field.column(point.getX() & 15, point.getZ() & 15);
            helper.assertTrue(column.contains(y) && column.active(), "Modern boat fixture is outside a directed wet interval");
            Boat river = boat(level, point.atY(y));
            double startX = river.getX(), startY = river.getY(), startZ = river.getZ();
            river.tick();
            helper.assertTrue(river.getDeltaMovement().y > 0 && river.getDeltaMovement().y <= .15,
                    "Modern source-water boat failed bounded surface following or launched too fast");
            helper.assertTrue(river.getDeltaMovement().x * column.directionX() + river.getDeltaMovement().z * column.directionZ() > 0,
                    "The actual Boat.tick motion-owner path did not apply downstream current");
            helper.assertTrue((river.getX() - startX) * column.directionX() + (river.getZ() - startZ) * column.directionZ() > 0
                            && river.getY() > startY, "Boat current/lift changed stored velocity but not its actual same-tick displacement");
            double sourceTarget = BoatCurrentSurface.target(river);
            helper.assertTrue(Double.isFinite(sourceTarget), "Real exposed source-water footprint lost its support target");
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                level.setBlock(new BlockPos(point.getX() + x, planned.waterY(), point.getZ() + z),
                        Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 5), 2);
            double flowingTarget = BoatCurrentSurface.target(river);
            helper.assertTrue(Double.isFinite(flowingTarget) && flowingTarget < sourceTarget - .3,
                    "Flowing-water footprint used the source-block top instead of the actual fluid height");
            double nearLift = CurrentMotion.surfaceVelocity(flowingTarget - .08, -.1, flowingTarget);
            helper.assertTrue(nearLift > 0 && nearLift < .05,
                    "Actual flowing-water target did not reduce lift near the resting surface");
            // A real drop under one half of the footprint stays within the accepted surface band.
            for (int x = -2; x <= 0; x++) for (int z = -2; z <= 2; z++) {
                level.setBlock(new BlockPos(point.getX() + x, planned.waterY(), point.getZ() + z), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(new BlockPos(point.getX() + x, planned.waterY() - 1, point.getZ() + z), Blocks.WATER.defaultBlockState(), 2);
            }
            helper.assertTrue(Double.isFinite(BoatCurrentSurface.target(river)),
                    "A one-block step under a supported boat footprint lost bounded surface following");
            BlockPos high = point.atY(Math.min(level.getMaxBuildHeight() - 5, column.maxY() + 18));
            fillWater(level, high, high.getY() + 2);
            Boat artificial = boat(level, high);
            artificial.tick();
            helper.assertTrue(!Double.isFinite(BoatCurrentSurface.target(artificial)) && !RuntimeCurrentSampler.sample(artificial).active(),
                    "Artificial water above the river inherited modern current or buoyancy");
            Boat unloaded = boat(level, new BlockPos((absentX << 4) + 15, 100, absentZ << 4));
            helper.assertTrue(!Double.isFinite(BoatCurrentSurface.target(unloaded))
                            && level.getChunkSource().getChunkNow(absentX, absentZ) == null,
                    "Buoyancy footprint lookup loaded an absent chunk");
            helper.assertTrue(ServerCurrentFields.cachedFields(level) <= ServerCurrentFields.FIELD_LIMIT, "Server field cache exceeded its bound");
            System.out.println("CWI_RUNTIME_CURRENT_BOAT=source_lift:true,flowing_surface:true,step_surface:true,downstream_push:true,stacked_pond_rejected:true,no_forced_chunk:true");
            helper.succeed();
        });
    }

    private static void awaitField(GameTestHelper helper, ServerLevel level, ChunkPos chunk,
                                   java.util.function.Consumer<CurrentChunk> ready) {
        boolean alreadyForced = level.getForcedChunks().contains(chunk.toLong());
        level.setChunkForced(chunk.x, chunk.z, true);
        Runnable cleanup = () -> { if (!alreadyForced) level.setChunkForced(chunk.x, chunk.z, false); };
        awaitField(helper, level, chunk, ready,
                System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90), cleanup);
    }

    private static void awaitField(GameTestHelper helper, ServerLevel level, ChunkPos chunk,
                                   java.util.function.Consumer<CurrentChunk> ready, long deadline, Runnable cleanup) {
        ServerCurrentFields.fieldIfLoaded(level, chunk.x, chunk.z);
        helper.runAfterDelay(1, () -> {
            try {
                helper.assertTrue(System.nanoTime() < deadline, "River field did not finish within 90 wall-clock seconds");
                ServerCurrentFields.flush();
                var field = ServerCurrentFields.fieldIfPresent(level, chunk.x, chunk.z);
                if (field == null) {
                    // GameTestServer runs unpaced. Yield real CPU time to the external
                    // worker instead of spending its whole tick allowance in milliseconds.
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    awaitField(helper, level, chunk, ready, deadline, cleanup);
                } else {
                    ready.accept(field);
                    cleanup.run();
                }
            } catch (RuntimeException | Error failure) { cleanup.run(); throw failure; }
        });
    }

    private static Boat boat(ServerLevel level, BlockPos point) {
        Boat boat = new Boat(EntityType.BOAT, level);
        boat.setPos(point.getX() + .5, point.getY(), point.getZ() + .5);
        boat.setDeltaMovement(0, -.1, 0);
        return boat;
    }
    private static void fillWater(ServerLevel level, BlockPos point, int surfaceY) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            for (int y = point.getY() - 1; y <= surfaceY; y++)
                level.setBlock(new BlockPos(point.getX() + x, y, point.getZ() + z), Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(new BlockPos(point.getX() + x, surfaceY + 1, point.getZ() + z), Blocks.AIR.defaultBlockState(), 2);
        }
    }
    private static BlockPos findWet(ReferenceTerrain terrain) {
        int tiles = 0;
        for (int x = -2040; x <= 2056; x += 64) for (int z = -2040; z <= 2056; z += 64) {
            if (!terrain.queryHydrology(x, z).validDirection()) continue;
            var column = ReferenceRiverColumn.sample(terrain, x, z);
            if (column.hasWater() && column.transition() != ReferenceRiverColumn.Transition.DROP
                    && column.waterY() - column.placementFloorY() >= 2) return new BlockPos(x, 0, z);
            if (++tiles >= 48) throw new AssertionError("No two-deep completed directed river in 48 native candidates");
        }
        throw new AssertionError("No directed wet river in bounded native search");
    }
}

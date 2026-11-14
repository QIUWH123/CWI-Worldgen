package net.quiwh.cwiworldgen.hydrology.current;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.config.CurrentConfig;
import net.quiwh.cwiworldgen.terrain.ReferenceRiverColumn;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

/** Real profile-seven fields across delayed worker completion and world retirement. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeAsyncCurrentTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", batch = "async_currents_lifecycle", timeoutTicks = 120000)
    public static void coldFieldsLeaveTicksFreeAndRejectInvalidatedAndRetiredCompletions(GameTestHelper helper) throws Exception {
        var level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:compact_natural_wasteland_test")));
        helper.assertTrue(level != null && ServerCurrentFields.terrain(level).generationVersion() == 7,
                "Async integration requires the real profile-seven dimension");
        var terrain = ServerCurrentFields.terrain(level);
        BlockPos point = findWet(terrain);
        var chunk = new ChunkPos(point);
        level.getChunk(chunk.x, chunk.z); // Explicit fixture loading, never a current lookup.
        var generated = CurrentFieldBuilder.build(terrain, chunk, 1);
        helper.assertTrue(generated.activeColumns() > 0, "Async comparison needs actual generated river columns");
        ServerCurrentFields.unloaded(level);
        var probe = new Probe(helper, level, chunk, point, generated, preparer());
        probe.run(() -> {
            probe.checkMissingChunk();
            Barrier first = probe.blockWorker();
            helper.assertTrue(ServerCurrentFields.fieldIfLoaded(level, chunk.x, chunk.z) == null,
                    "A cold current request prepared terrain synchronously");
            probe.flush();
            helper.assertTrue(ServerCurrentFields.fieldIfPresent(level, chunk.x, chunk.z) == null,
                    "Flush waited for a deliberately blocked cold build");
            long dispatchedTick = helper.getTick();
            helper.runAfterDelay(3, () -> probe.run(() -> {
                probe.blockedTicks = helper.getTick() - dispatchedTick;
                helper.assertTrue(probe.blockedTicks >= 3
                                && ServerCurrentFields.fieldIfPresent(level, chunk.x, chunk.z) == null,
                        "Server ticks did not advance while cold current preparation was blocked");
                probe.checkMissingChunk();
                probe.edits.addPath(probe.editId, point.getX() - 4, point.getZ(), point.getX() + 4,
                        point.getZ(), 280, 284, 2, .037);
                ServerCurrentFields.invalidate(level);
                ServerCurrentFields.invalidatePolicy();
                ServerCurrentFields.fieldIfLoaded(level, chunk.x, chunk.z);
                probe.flush();
                // This barrier runs after the obsolete build and before its replacement.
                // An obsolete completion must stay invisible even though the new overlay
                // would be available if publication incorrectly accepted the old job.
                Barrier replacement = probe.blockWorker();
                first.release.countDown();
                probe.await(() -> replacement.entered.getCount() == 0, () -> {
                    probe.flush();
                    helper.assertTrue(ServerCurrentFields.fieldIfPresent(level, chunk.x, chunk.z) == null,
                            "An invalidated worker result became visible before the replacement could run");
                    replacement.release.countDown();
                    probe.awaitField(() -> {
                        probe.assertExactField();
                        probe.retireAndReload();
                    });
                });
            }));
        });
    }

    private static ThreadPoolExecutor preparer() throws ReflectiveOperationException {
        // Keep production free of a test hook. This is the only reflected member;
        // latches occupy its real executor without replacing its work implementation.
        Field field = ServerCurrentFields.class.getDeclaredField("PREPARER");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(null);
    }

    private static BlockPos findWet(ReferenceTerrain terrain) {
        int candidates = 0;
        for (int x = -2040; x <= 2056; x += 64) for (int z = -2040; z <= 2056; z += 64) {
            if (!terrain.queryHydrology(x, z).validDirection()) continue;
            if (terrain.sample(x, z).terrain.isRiver()
                    && ReferenceRiverColumn.sample(terrain, x, z).hasWater()) return new BlockPos(x, 0, z);
            if (++candidates >= 64) throw new AssertionError("No completed profile-seven river in 64 native candidates");
        }
        throw new AssertionError("No directed profile-seven river in the bounded fixture search");
    }

    private static final class Barrier {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
    }

    private static final class Probe {
        final GameTestHelper helper;
        final ServerLevel level;
        final ChunkPos chunk;
        final BlockPos point;
        final CurrentChunk generated;
        final ThreadPoolExecutor executor;
        final CurrentOverrides edits;
        final String editId = "async_current_lifecycle_test";
        final List<Barrier> barriers = new ArrayList<>();
        final long startedTick;
        final long startedNanos = System.nanoTime();
        final boolean alreadyForced;
        long blockedTicks, maxFlushNanos;

        Probe(GameTestHelper helper, ServerLevel level, ChunkPos chunk, BlockPos point,
              CurrentChunk generated, ThreadPoolExecutor executor) {
            this.helper = helper; this.level = level; this.chunk = chunk; this.point = point;
            this.generated = generated; this.executor = executor;
            this.edits = CurrentOverrides.get(level); this.startedTick = helper.getTick();
            this.alreadyForced = level.getForcedChunks().contains(chunk.toLong());
            level.setChunkForced(chunk.x, chunk.z, true);
        }

        void run(Runnable action) {
            try { action.run(); }
            catch (RuntimeException | Error failure) { cleanup(); throw failure; }
        }

        Barrier blockWorker() {
            Barrier barrier = new Barrier();
            barriers.add(barrier);
            executor.execute(() -> {
                barrier.entered.countDown();
                try { barrier.release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            return barrier;
        }

        void flush() {
            long start = System.nanoTime();
            ServerCurrentFields.flush();
            maxFlushNanos = Math.max(maxFlushNanos, System.nanoTime() - start);
        }

        void await(BooleanSupplier condition, Runnable ready) {
            helper.runAfterDelay(1, () -> run(() -> {
                helper.assertTrue(System.nanoTime() - startedNanos < java.util.concurrent.TimeUnit.SECONDS.toNanos(90),
                        "Async field lifecycle did not finish within 90 wall-clock seconds");
                flush();
                if (condition.getAsBoolean()) ready.run();
                else {
                    // Unpaced GameTestServer ticks otherwise exhaust the test before
                    // even a healthy external cold worker receives enough wall time.
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    await(condition, ready);
                }
            }));
        }

        void awaitField(Runnable ready) {
            ServerCurrentFields.fieldIfLoaded(level, chunk.x, chunk.z);
            await(() -> {
                if (ServerCurrentFields.fieldIfPresent(level, chunk.x, chunk.z) != null) return true;
                ServerCurrentFields.fieldIfLoaded(level, chunk.x, chunk.z);
                return false;
            }, ready);
        }

        void checkMissingChunk() {
            int x = 62500, z = -62500;
            helper.assertTrue(level.getChunkSource().getChunkNow(x, z) == null
                            && ServerCurrentFields.fieldIfLoaded(level, x, z) == null
                            && level.getChunkSource().getChunkNow(x, z) == null,
                    "Async current dispatch loaded an absent Minecraft chunk");
        }

        void assertExactField() {
            var actual = ServerCurrentFields.fieldIfPresent(level, chunk.x, chunk.z);
            var expected = ServerCurrentFields.strength(edits.apply(chunk, generated),
                    CurrentConfig.ENABLED.get() ? CurrentConfig.STRENGTH.get() : 0);
            helper.assertTrue(actual != null && actual.layerCount() == expected.layerCount(),
                    "Isolated worker changed the generated or authored layer count");
            for (int layer = 0; layer < expected.layerCount(); layer++)
                for (int index = 0; index < CurrentChunk.COLUMNS; index++)
                    helper.assertTrue(actual.packed(layer, index) == expected.packed(layer, index),
                            "Isolated worker changed an immutable native column at " + layer + ":" + index);
        }

        void retireAndReload() {
            Barrier retirement = blockWorker();
            ServerCurrentFields.invalidate(level);
            ServerCurrentFields.fieldIfLoaded(level, chunk.x, chunk.z);
            flush();
            int worlds = ServerCurrentFields.cachedWorlds();
            ServerCurrentFields.unloaded(level);
            helper.assertTrue(ServerCurrentFields.cachedWorlds() == worlds - 1
                            && ServerCurrentFields.cachedFields(level) == 0,
                    "World retirement retained its current cache or work owner");
            Barrier reloaded = blockWorker();
            edits.addPath(editId, point.getX() + 4, point.getZ(), point.getX() - 4,
                    point.getZ(), 280, 284, 2, .029);
            ServerCurrentFields.fieldIfLoaded(level, chunk.x, chunk.z);
            flush();
            helper.assertTrue(ServerCurrentFields.cachedWorlds() == worlds,
                    "Reload did not create exactly one replacement current owner");
            retirement.release.countDown();
            await(() -> reloaded.entered.getCount() == 0, () -> {
                flush();
                helper.assertTrue(ServerCurrentFields.fieldIfPresent(level, chunk.x, chunk.z) == null,
                        "A retired world's completed job populated the replacement owner's cache");
                reloaded.release.countDown();
                awaitField(() -> {
                    assertExactField();
                    checkMissingChunk();
                    System.out.println("CWI_CURRENT_ASYNC=profile:7,blocked_ticks:" + blockedTicks
                            + ",elapsed_ticks:" + (helper.getTick() - startedTick)
                            + ",elapsed_wall_ms:" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
                            + ",max_flush_ns:" + maxFlushNanos + ",natural_columns:" + generated.activeColumns()
                            + ",stale_overlay_rejected:true,retired_completion_rejected:true,no_forced_chunk:true");
                    cleanup();
                    helper.assertTrue(ServerCurrentFields.cachedFields(level) == 0
                                    && ServerCurrentFields.fieldIfPresent(level, chunk.x, chunk.z) == null,
                            "Completed async test retained current state after world retirement");
                    helper.succeed();
                });
            });
        }

        void cleanup() {
            for (var barrier : barriers) barrier.release.countDown();
            edits.remove(editId);
            ServerCurrentFields.unloaded(level);
            if (!alreadyForced) level.setChunkForced(chunk.x, chunk.z, false);
        }
    }
}

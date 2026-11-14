package net.quiwh.cwiworldgen.hydrology.current;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.quiwh.cwiworldgen.CwiWorldgen;

/** Operator-triggered, incremental metadata reconstruction. No chunks loaded and no block writes. */
@EventBusSubscriber(modid = CwiWorldgen.ID)
public final class ExistingWaterReflow {
    public static final int MAX_RADIUS = 64;
    public static final int COLUMNS_PER_TICK = 64;
    private static final Map<ServerLevel,Job> JOBS = new IdentityHashMap<>();
    private static final Map<ServerLevel,String> STATUS = new IdentityHashMap<>();
    private ExistingWaterReflow() {}

    public static int start(ServerLevel level, BlockPos center, int radius) {
        var terrain = ServerCurrentFields.terrain(level);
        if (terrain == null || terrain.generationVersion() < 2)
            throw new IllegalArgumentException("Reflow requires the CWI Overworld");
        if (radius < 4 || radius > MAX_RADIUS) throw new IllegalArgumentException("Reflow radius must be 4..64 blocks");
        if (JOBS.containsKey(level)) throw new IllegalArgumentException("A reflow is already running; cancel it first");
        JOBS.put(level,new Job(center.getX(),center.getZ(),radius));
        STATUS.put(level,"Scanning loaded water; no terrain changes");
        return 1;
    }
    public static boolean cancel(ServerLevel level) {
        boolean removed = JOBS.remove(level) != null;
        if (removed) STATUS.put(level,"Cancelled; previous current metadata retained");
        return removed;
    }
    public static String status(ServerLevel level) {
        Job job = JOBS.get(level);
        return job == null ? STATUS.getOrDefault(level,"No reflow job")
                : "Scanning " + job.cursor + "/" + job.total + " columns; " + job.surfaces.size() + " observed surfaces";
    }
    @SubscribeEvent public static void onTick(ServerTickEvent.Post event) { tick(); }
    @SubscribeEvent public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) unloaded(level);
    }
    public static void unloaded(ServerLevel level) { JOBS.remove(level); STATUS.remove(level); }
    public static void tick() {
        var iterator = JOBS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            ServerLevel level = entry.getKey(); Job job = entry.getValue();
            try {
                for (int n = 0; n < COLUMNS_PER_TICK && job.cursor < job.total; n++, job.cursor++) job.scan(level);
                if (job.cursor < job.total) continue;
                List<CurrentChunk> fields = job.finish(level);
                CurrentOverrides.get(level).replaceReflow(fields);
                ServerCurrentFields.invalidate(level);
                STATUS.put(level,"Complete: " + job.surfaces.size() + " surfaces in " + fields.size()
                        + " loaded chunks; closed or unobserved outlets remain still");
                iterator.remove();
            } catch (IllegalArgumentException failure) {
                STATUS.put(level,"Reflow rejected: " + failure.getMessage() + "; previous metadata retained");
                iterator.remove();
            }
        }
    }

    private static final class Job {
        final int x,z,radius,side,total;
        int cursor;
        final List<WaterSurfaceReflow.Surface> surfaces = new ArrayList<>();
        final Map<Long,long[][]> fields = new LinkedHashMap<>();
        final Map<Long,boolean[]> observed = new LinkedHashMap<>();
        final Map<Long,net.minecraft.world.level.chunk.LevelChunk> identities = new LinkedHashMap<>();
        Job(int x,int z,int radius) { this.x=x;this.z=z;this.radius=radius;side=radius*2+1;total=side*side; }
        void scan(ServerLevel level) {
            int dx = cursor%side-radius, dz = cursor/side-radius;
            if (dx*dx+dz*dz > radius*radius) return;
            int bx = x+dx,bz = z+dz;
            var chunk = level.getChunkSource().getChunkNow(bx>>4,bz>>4);
            if (chunk == null) return;
            long key=chunk.getPos().toLong();
            if (identities.put(key,chunk)!=chunk) {
                // A reloaded chunk is a new observation epoch; discard stale water from
                // its earlier instance rather than infer an outlet through vanished data.
                fields.put(key,new long[2][256]);observed.put(key,new boolean[256]);
                surfaces.removeIf(s -> ChunkPos.asLong(s.x()>>4,s.z()>>4)==key);
            }
            observed.get(key)[(bz&15)*16+(bx&15)]=true;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(bx,0,bz);
            int found = 0;
            for (int y = level.getMaxBuildHeight()-2; y >= level.getMinBuildHeight(); y--) {
                pos.setY(y);
                if (!chunk.getBlockState(pos).is(Blocks.WATER)) continue;
                if (!chunk.getBlockState(pos.above()).isAir()) continue;
                int top=y,bottom=y;
                while (bottom > level.getMinBuildHeight()
                        && chunk.getBlockState(pos.setY(bottom-1)).is(Blocks.WATER)) bottom--;
                if (++found > 2) throw new IllegalArgumentException("More than two exposed water layers in a column");
                surfaces.add(new WaterSurfaceReflow.Surface(bx,bz,bottom,top));
                y=bottom;
                if (surfaces.size()>WaterSurfaceReflow.MAX_SURFACES) throw new IllegalArgumentException("Reflow surface budget exceeded");
            }
        }
        List<CurrentChunk> finish(ServerLevel level) {
            // Only actually observed columns in the same loaded chunk instance are replaced.
            // Missing columns and partial boundary chunks preserve earlier metadata.
            var previous = CurrentOverrides.get(level);
            fields.keySet().removeIf(key -> level.getChunkSource().getChunkNow(ChunkPos.getX(key),ChunkPos.getZ(key))!=identities.get(key));
            surfaces.removeIf(s -> !fields.containsKey(ChunkPos.asLong(s.x()>>4,s.z()>>4)));
            for (var entry : fields.entrySet()) {
                long key=entry.getKey();
                CurrentChunk old=previous.reflowField(key);
                ReflowObservation.preserveUnobserved(entry.getValue(),old,observed.get(key));
            }
            Map<Long,int[]> counts=new LinkedHashMap<>();
            for (var flow : WaterSurfaceReflow.solve(surfaces)) {
                var s=flow.surface();long key=ChunkPos.asLong(s.x()>>4,s.z()>>4);
                int index=(s.z()&15)*16+(s.x()&15);
                int layer=counts.computeIfAbsent(key, ignored -> new int[256])[index]++;
                fields.get(key)[layer][index]=(flow.speed()==0 ? CurrentColumn.still(s.bottom(),s.top(),0,0)
                        : CurrentColumn.of(s.bottom(),s.top(),flow.x(),flow.z(),flow.speed(),0,0)).pack();
            }
            List<CurrentChunk> result=new ArrayList<>();
            for (var entry : fields.entrySet()) if (level.getChunkSource().getChunkNow(
                    ChunkPos.getX(entry.getKey()),ChunkPos.getZ(entry.getKey()))!=null)
                result.add(new CurrentChunk(entry.getKey(),entry.getValue()));
            return List.copyOf(result);
        }
    }
}

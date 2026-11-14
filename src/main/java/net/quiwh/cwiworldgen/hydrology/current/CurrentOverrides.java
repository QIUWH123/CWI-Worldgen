package net.quiwh.cwiworldgen.hydrology.current;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/** Persistent, bounded metadata only. Rasterizing or querying it never reads Minecraft chunks. */
public final class CurrentOverrides extends SavedData {
    public static final int MAX_MANUAL = 64;
    public static final int MAX_REFLOW_CHUNKS = 1024;
    public static final int MAX_MANUAL_CHUNKS = 1024;
    public static final int MAX_COORDINATE = 29_999_984;
    public static final int MIN_Y = -2032, MAX_Y = 2031;
    private static final int SCHEMA = 1;
    private static final Factory<CurrentOverrides> FACTORY = new Factory<>(CurrentOverrides::new, CurrentOverrides::load);
    private static final String FILE = "cwi_worldgen_currents";
    private LinkedHashMap<String, Edit> manual = new LinkedHashMap<>();
    private Map<Long, List<Edit>> index = Map.of();
    private Map<Long, CurrentChunk> reflow = Map.of();

    /** The caller has already established CWI runtime/dimension eligibility. */
    public static CurrentOverrides get(ServerLevel level) { return level.getDataStorage().computeIfAbsent(FACTORY, FILE); }
    public static CurrentChunk apply(ServerLevel level, ChunkPos pos, CurrentChunk generated) { return get(level).apply(pos, generated); }

    public void addPath(String id, double x1, double z1, double x2, double z2,
                        int minY, int maxY, double radius, double speed) {
        put(new Edit(id, false, x1, z1, x2, z2, minY, maxY, radius, speed));
    }
    public void addStill(String id, double x, double z, int minY, int maxY, double radius) {
        put(new Edit(id, true, x, z, x, z, minY, maxY, radius, 0));
    }
    private void put(Edit edit) {
        var candidate = new LinkedHashMap<>(manual);
        candidate.remove(edit.id()); candidate.put(edit.id(), edit);
        if (candidate.size() > MAX_MANUAL) throw new IllegalArgumentException("At most " + MAX_MANUAL + " manual current edits are allowed");
        var nextIndex = buildIndex(candidate.values()); validateCapacity(nextIndex, reflow);
        manual = candidate; index = nextIndex; setDirty();
    }
    public boolean remove(String id) {
        if (!manual.containsKey(id)) return false;
        var candidate = new LinkedHashMap<>(manual); candidate.remove(id);
        // Removing an occluding edit can expose older intervals, so preflight removals too.
        var nextIndex = buildIndex(candidate.values()); validateCapacity(nextIndex, reflow);
        manual = candidate; index = nextIndex; setDirty(); return true;
    }
    public void clearManual() { manual.clear(); index = Map.of(); setDirty(); }
    public void clearReflow() { reflow = Map.of(); setDirty(); }
    public int manualCount() { return manual.size(); }
    public int reflowCount() { return reflow.size(); }
    public CurrentChunk reflowField(long key) { return reflow.get(key); }
    public List<String> summaries() { return manual.values().stream().map(Edit::summary).toList(); }

    /** Atomic bounded area update; fields outside the supplied chunk keys are retained. */
    public void replaceReflow(Collection<CurrentChunk> fields) {
        if (fields.size() > MAX_REFLOW_CHUNKS) throw new IllegalArgumentException("Too many reflow chunks");
        var candidate = new HashMap<>(reflow); var supplied = new HashSet<Long>();
        for (CurrentChunk field : fields) {
            checkChunk(field.chunkPos());
            if (!supplied.add(field.chunkPos())) throw new IllegalArgumentException("Duplicate reflow chunk");
            if (field.layerCount() > 2) throw new IllegalArgumentException("Reflow supports at most two water layers per chunk");
            if (empty(field)) candidate.remove(field.chunkPos()); else candidate.put(field.chunkPos(), field);
        }
        if (candidate.size() > MAX_REFLOW_CHUNKS) throw new IllegalArgumentException("Persistent reflow limit reached; clear old reflow first");
        validateCapacity(index, candidate); reflow = Map.copyOf(candidate); setDirty();
    }

    public CurrentChunk apply(ChunkPos pos, CurrentChunk generated) {
        if (generated.chunkPos() != pos.toLong()) throw new IllegalArgumentException("Generated field belongs to another chunk");
        CurrentChunk inferred = reflow.get(pos.toLong()); List<Edit> edits = index.getOrDefault(pos.toLong(), List.of());
        if (inferred == null && edits.isEmpty()) return generated;
        long[][] out = new long[CurrentChunk.MAX_LAYERS][CurrentChunk.COLUMNS]; int used = 1;
        for (int i = 0; i < CurrentChunk.COLUMNS; i++) {
            List<CurrentColumn> stack = new ArrayList<>();
            // Original generated rivers are authoritative over inferred existing-water flow.
            append(stack, inferred, i); append(stack, generated, i);
            appendEdits(stack, edits, pos.getMinBlockX() + (i & 15), pos.getMinBlockZ() + (i >> 4));
            List<CurrentColumn> visible = visible(stack);
            if (visible.size() > CurrentChunk.MAX_LAYERS) throw new IllegalArgumentException("Current intervals exceed four layers");
            used = Math.max(used, visible.size());
            for (int layer = 0; layer < visible.size(); layer++) out[layer][i] = visible.get(layer).pack();
        }
        return new CurrentChunk(pos.toLong(), java.util.Arrays.copyOf(out, used));
    }
    private static void append(List<CurrentColumn> out, CurrentChunk field, int index) {
        if (field == null) return;
        for (int layer = 0; layer < field.layerCount(); layer++) {
            CurrentColumn column = CurrentColumn.unpack(field.packed(layer, index)); if (column.plannedWater()) out.add(column);
        }
    }
    private static void appendEdits(List<CurrentColumn> out, List<Edit> edits, int x, int z) {
        // Still masks always win over paths; removing the mask is an explicit operator action.
        for (boolean still : new boolean[]{false, true}) for (Edit edit : edits) if (edit.still() == still) {
            CurrentColumn column = edit.column(x + .5, z + .5); if (column.plannedWater()) out.add(column);
        }
    }
    /** Remove fully hidden intervals; overlapping priority layers preserve non-overlapped heights. */
    private static List<CurrentColumn> visible(List<CurrentColumn> stack) {
        var out = new ArrayList<CurrentColumn>();
        for (int i = 0; i < stack.size(); i++) {
            CurrentColumn candidate = stack.get(i); int cursor = candidate.minY();
            var covers = new ArrayList<CurrentColumn>(stack.subList(i + 1, stack.size()));
            covers.sort(java.util.Comparator.comparingInt(CurrentColumn::minY));
            for (CurrentColumn cover : covers) {
                if (cover.maxY() < cursor) continue;
                if (cover.minY() > cursor) break;
                cursor = Math.max(cursor, cover.maxY() + 1); if (cursor > candidate.maxY()) break;
            }
            if (cursor <= candidate.maxY()) out.add(candidate);
        }
        return out;
    }
    private static void validateCapacity(Map<Long, List<Edit>> edits, Map<Long, CurrentChunk> fields) {
        // Reflow alone is bounded to two layers; one manual interval cannot exhaust
        // the remaining capacity. Avoid scanning unrelated persisted regions on edits.
        for (long key : edits.keySet()) {
            if (edits.get(key).size() + (fields.containsKey(key) ? fields.get(key).layerCount() : 0) < CurrentChunk.MAX_LAYERS) continue;
            var pos = new ChunkPos(key);
            for (int i = 0; i < CurrentChunk.COLUMNS; i++) {
                var stack = new ArrayList<CurrentColumn>(); append(stack, fields.get(key), i);
                appendEdits(stack, edits.getOrDefault(key, List.of()), pos.getMinBlockX() + (i & 15), pos.getMinBlockZ() + (i >> 4));
                // Reserve one physical layer for natural flow; overlapping ranges stay compact.
                if (visible(stack).size() >= CurrentChunk.MAX_LAYERS) throw new IllegalArgumentException("Edits exceed four vertical current layers near "
                        + (pos.getMinBlockX() + (i & 15)) + "," + (pos.getMinBlockZ() + (i >> 4)));
            }
        }
    }
    private static Map<Long, List<Edit>> buildIndex(Collection<Edit> edits) {
        var out = new HashMap<Long, List<Edit>>();
        for (Edit edit : edits) {
            int x0 = ((int) Math.floor(Math.min(edit.x1(), edit.x2()) - edit.radius())) >> 4;
            int x1 = ((int) Math.floor(Math.max(edit.x1(), edit.x2()) + edit.radius())) >> 4;
            int z0 = ((int) Math.floor(Math.min(edit.z1(), edit.z2()) - edit.radius())) >> 4;
            int z1 = ((int) Math.floor(Math.max(edit.z1(), edit.z2()) + edit.radius())) >> 4;
            for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) {
                if (!edit.near(x * 16.0 + 8, z * 16.0 + 8, edit.radius() + Math.sqrt(128))) continue;
                out.computeIfAbsent(ChunkPos.asLong(x, z), ignored -> new ArrayList<>()).add(edit);
                if (out.size() > MAX_MANUAL_CHUNKS) throw new IllegalArgumentException("Manual current footprint exceeds " + MAX_MANUAL_CHUNKS + " chunks");
            }
        }
        return out;
    }
    private static boolean empty(CurrentChunk field) {
        for (int layer = 0; layer < field.layerCount(); layer++) for (int i = 0; i < CurrentChunk.COLUMNS; i++) if (field.packed(layer, i) != 0) return false;
        return true;
    }
    private static void checkChunk(long key) {
        var pos = new ChunkPos(key);
        if (Math.abs((long) pos.x) > (MAX_COORDINATE >> 4) || Math.abs((long) pos.z) > (MAX_COORDINATE >> 4)) throw new IllegalArgumentException("Reflow chunk exceeds world coordinate bounds");
    }
    public static CurrentOverrides load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains("schema", Tag.TAG_INT) || tag.getInt("schema") != SCHEMA) throw new IllegalArgumentException("Unsupported current override schema");
        if (!(tag.get("manual") instanceof ListTag edits) || edits.size() > MAX_MANUAL
                || !(tag.get("reflow") instanceof ListTag fields) || fields.size() > MAX_REFLOW_CHUNKS) throw new IllegalArgumentException("Invalid current override list bounds");
        var data = new CurrentOverrides();
        for (Tag value : edits) {
            if (!(value instanceof CompoundTag edit)) throw new IllegalArgumentException("Invalid manual current entry");
            requireFields(edit, "id", "still", "x1", "z1", "x2", "z2", "min_y", "max_y", "radius", "speed");
            var parsed = new Edit(edit.getString("id"), edit.getBoolean("still"), edit.getDouble("x1"), edit.getDouble("z1"), edit.getDouble("x2"), edit.getDouble("z2"),
                    edit.getInt("min_y"), edit.getInt("max_y"), edit.getDouble("radius"), edit.getDouble("speed"));
            if (data.manual.putIfAbsent(parsed.id(), parsed) != null) throw new IllegalArgumentException("Duplicate manual current id");
        }
        var inferred = new HashMap<Long, CurrentChunk>();
        for (Tag value : fields) {
            if (!(value instanceof CompoundTag field) || !field.contains("chunk", Tag.TAG_LONG)
                    || !(field.get("layers") instanceof ListTag layers) || layers.isEmpty() || layers.size() > 2) throw new IllegalArgumentException("Invalid saved reflow layers");
            long key = field.getLong("chunk"); checkChunk(key); long[][] packed = new long[layers.size()][];
            for (int i = 0; i < packed.length; i++) {
                if (!(layers.get(i) instanceof LongArrayTag array) || array.size() != CurrentChunk.COLUMNS) throw new IllegalArgumentException("Invalid saved reflow column count");
                packed[i] = array.getAsLongArray().clone();
            }
            if (inferred.putIfAbsent(key, new CurrentChunk(key, packed)) != null) throw new IllegalArgumentException("Duplicate saved reflow chunk");
        }
        data.index = buildIndex(data.manual.values()); validateCapacity(data.index, inferred); data.reflow = Map.copyOf(inferred); return data;
    }
    private static void requireFields(CompoundTag tag, String... keys) {
        for (String key : keys) if (!tag.contains(key)) throw new IllegalArgumentException("Missing current edit field: " + key);
        if (!tag.contains("id", Tag.TAG_STRING) || !tag.contains("still", Tag.TAG_BYTE)) throw new IllegalArgumentException("Invalid current edit types");
        for (String key : new String[]{"x1", "z1", "x2", "z2", "radius", "speed"}) if (!tag.contains(key, Tag.TAG_DOUBLE)) throw new IllegalArgumentException("Invalid current numeric type: " + key);
        for (String key : new String[]{"min_y", "max_y"}) if (!tag.contains(key, Tag.TAG_INT)) throw new IllegalArgumentException("Invalid current height type");
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema", SCHEMA); var edits = new ListTag();
        for (Edit edit : manual.values()) {
            var value = new CompoundTag(); value.putString("id", edit.id()); value.putBoolean("still", edit.still());
            value.putDouble("x1", edit.x1()); value.putDouble("z1", edit.z1()); value.putDouble("x2", edit.x2()); value.putDouble("z2", edit.z2());
            value.putInt("min_y", edit.minY()); value.putInt("max_y", edit.maxY()); value.putDouble("radius", edit.radius()); value.putDouble("speed", edit.speed()); edits.add(value);
        }
        tag.put("manual", edits); var fields = new ListTag();
        for (long key : reflow.keySet().stream().sorted().toList()) {
            var value = new CompoundTag(); value.putLong("chunk", key); var field = reflow.get(key); var layers = new ListTag();
            for (int layer = 0; layer < field.layerCount(); layer++) {
                long[] packed = new long[CurrentChunk.COLUMNS]; for (int i = 0; i < packed.length; i++) packed[i] = field.packed(layer, i); layers.add(new LongArrayTag(packed));
            }
            value.put("layers", layers); fields.add(value);
        }
        tag.put("reflow", fields); return tag;
    }
    private record Edit(String id, boolean still, double x1, double z1, double x2, double z2, int minY, int maxY, double radius, double speed) {
        private Edit {
            if (id == null || !id.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Use a 1-32 character lowercase current id");
            for (double coordinate : new double[]{x1,z1,x2,z2}) if (!Double.isFinite(coordinate) || Math.abs(coordinate) > MAX_COORDINATE - 32) throw new IllegalArgumentException("Manual current coordinate is outside world bounds");
            if (minY < MIN_Y || maxY > MAX_Y || minY > maxY) throw new IllegalArgumentException("Invalid manual current height interval");
            if (!Double.isFinite(radius) || radius < .5 || radius > 32) throw new IllegalArgumentException("Current radius must be 0.5-32 blocks");
            double length = Math.hypot(x2-x1, z2-z1);
            if (!still && (length < 1 || length > 1024 || !Double.isFinite(speed) || speed < .001 || speed > CurrentColumn.MAX_SPEED)) throw new IllegalArgumentException("Path length must be 1-1024 blocks and speed 0.001-0.08");
            if (still && (length != 0 || speed != 0)) throw new IllegalArgumentException("A still mask has one center and zero speed");
        }
        private CurrentColumn column(double x, double z) {
            double dx = x2-x1, dz = z2-z1, length = Math.hypot(dx,dz);
            if (!near(x,z,radius)) return CurrentColumn.EMPTY;
            return still ? CurrentColumn.still(minY,maxY,0,0) : CurrentColumn.of(minY,maxY,dx/length,dz/length,speed,0,0);
        }
        private boolean near(double x, double z, double reach) {
            double dx = x2-x1, dz = z2-z1;
            double t = still ? 0 : Math.max(0,Math.min(1,((x-x1)*dx+(z-z1)*dz)/(dx*dx+dz*dz)));
            return Math.hypot(x-(x1+t*dx),z-(z1+t*dz)) <= reach;
        }
        private String summary() { return id + (still ? " still" : " path") + " y=" + minY + ".." + maxY + " radius=" + radius; }
    }
}

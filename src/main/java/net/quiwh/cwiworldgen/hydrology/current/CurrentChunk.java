package net.quiwh.cwiworldgen.hydrology.current;

/** Bounded vertical intervals per exact block column; later layers have explicit precedence. */
public final class CurrentChunk {
    public static final int COLUMNS = 256;
    public static final int MAX_LAYERS = 4;
    private final long chunkPos;
    private final long[][] packed;

    public CurrentChunk(long chunkPos, long[] packed) {
        this(chunkPos, new long[][] {packed});
    }

    public CurrentChunk(long chunkPos, long[][] packed) {
        if (packed == null || packed.length < 1 || packed.length > MAX_LAYERS)
            throw new IllegalArgumentException("A current chunk requires one to four layers");
        this.chunkPos = chunkPos;
        this.packed = new long[packed.length][];
        for (int layer = 0; layer < packed.length; layer++) {
            if (packed[layer] == null || packed[layer].length != COLUMNS)
                throw new IllegalArgumentException("A current layer requires 256 columns");
            this.packed[layer] = packed[layer].clone();
            for (long value : this.packed[layer]) CurrentColumn.unpack(value);
        }
    }

    public long chunkPos() { return chunkPos; }
    public int layerCount() { return packed.length; }
    public long packed(int index) { return packed[0][index]; }
    public long packed(int layer, int index) { return packed[layer][index]; }
    public CurrentColumn column(int localX, int localZ) { return CurrentColumn.unpack(packed[0][(localZ & 15) * 16 + (localX & 15)]); }
    public CurrentColumn column(int localX, int y, int localZ) {
        int index = (localZ & 15) * 16 + (localX & 15);
        for (int layer = packed.length - 1; layer >= 0; layer--) {
            CurrentColumn column = CurrentColumn.unpack(packed[layer][index]);
            if (column.contains(y)) return column;
        }
        return CurrentColumn.EMPTY;
    }
    public int activeColumns() {
        int count = 0;
        for (int index = 0; index < COLUMNS; index++) {
            for (long[] layer : packed) if (CurrentColumn.unpack(layer[index]).active()) { count++; break; }
        }
        return count;
    }
}

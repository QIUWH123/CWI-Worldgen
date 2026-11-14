package net.quiwh.cwiworldgen.hydrology.current;

/** Pure final-layer audio eligibility and voice budget; callers still verify real exposed water. */
public final class CurrentPresentation {
    private CurrentPresentation() {}
    public enum Kind { NONE, STREAM, WATERFALL }
    public static Kind kind(CurrentColumn column) {
        if (!column.active()) return Kind.NONE;
        return column.drop() >= 2 ? Kind.WATERFALL : Kind.STREAM;
    }
    public static CurrentColumn visible(CurrentChunk field,int x,int y,int z) {
        return field == null ? CurrentColumn.EMPTY : field.column(x,y,z);
    }
    public static int voices(int requested,boolean waterfalls,boolean streams) {
        return waterfalls || streams ? Math.clamp(requested,0,3) : 0;
    }
}

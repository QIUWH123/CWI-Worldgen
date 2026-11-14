package net.quiwh.cwiworldgen.terrain;

/** Native solid shell and actual fluid interval; never raises a floor or shoreline. */
public record WaterProtectionColumn(boolean protectedColumn, int groundY, int waterY, int floorY) {
    public static final WaterProtectionColumn NONE = new WaterProtectionColumn(false, 0, 0, 0);

    public boolean protects(int y) { return protectedColumn && y >= floorY && y <= groundY; }
    public boolean containsWater(int y) { return protectedColumn && y > groundY && y <= waterY; }
}

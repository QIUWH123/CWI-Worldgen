package net.quiwh.cwiworldgen.hydrology.watershed;

/** Immutable world-space channel sample. Heights are actual Minecraft block coordinates. */
public record WatershedSample(boolean valid, long reachId, double distance, double width,
                              double tangentX, double tangentZ, boolean wet, boolean dry,
                              boolean basin, double bedY, double waterY, double originalGroundY,
                              boolean island, boolean drop, double progress,
                              boolean shoal, boolean dryCrest, boolean plungePool, boolean gorge,
                              RiverBankContext bankContext) {
    public WatershedSample(boolean valid, long reachId, double distance, double width,
            double tangentX, double tangentZ, boolean wet, boolean dry, boolean basin,
            double bedY, double waterY, double originalGroundY, boolean island, boolean drop, double progress,
            boolean shoal, boolean dryCrest, boolean plungePool, boolean gorge) {
        this(valid,reachId,distance,width,tangentX,tangentZ,wet,dry,basin,bedY,waterY,originalGroundY,
                island,drop,progress,shoal,dryCrest,plungePool,gorge,null);
    }
    public WatershedSample(boolean valid, long reachId, double distance, double width,
            double tangentX, double tangentZ, boolean wet, boolean dry, boolean basin,
            double bedY, double waterY, double originalGroundY, boolean island, boolean drop, double progress) {
        this(valid,reachId,distance,width,tangentX,tangentZ,wet,dry,basin,bedY,waterY,originalGroundY,
                island,drop,progress,false,false,false,false);
    }
    public static WatershedSample absent(double originalGroundY) {
        return new WatershedSample(false,0,0,0,0,0,false,false,false,originalGroundY,0,originalGroundY,false,false,0);
    }
}

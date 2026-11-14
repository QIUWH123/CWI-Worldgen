package net.quiwh.cwiworldgen.terrain;

/** Native terrain selection, independent of hydrology and feature profiles. */
public final class BaseTerrainProfile {
    private BaseTerrainProfile() {}

    public static boolean usesClassicTerrain(int generationVersion) {
        return generationVersion >= 20 && generationVersion <= 22;
    }

    public static int version(int generationVersion) {
        return usesClassicTerrain(generationVersion) ? 1 : generationVersion;
    }

    /** Lower the classic inland steps without moving coastlines or flattening local relief. */
    public static float inlandUpliftScale(int generationVersion) {
        return generationVersion >= 21 ? 0.80F : 1.0F;
    }
}

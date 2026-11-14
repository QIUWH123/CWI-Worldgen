package net.quiwh.cwiworldgen.terrain;

/** Local cliff lithology; its height field never changes terrain geometry. */
public final class CliffBands {
    private static final long WARP = 0x434C494646574152L;
    private static final long THICKNESS = 0x544849434B4E4553L;
    private static final long BREAKS = 0x42414E4442524541L;
    private final long seed;

    public CliffBands(long seed) { this.seed = seed; }

    public enum Material { NONE, STONE, TUFF, ANDESITE }

    @FunctionalInterface
    public interface Heights { int heightAt(int x, int z); }

    /** Only the side shell above the lowest immediate neighboring ground is visible. */
    public record Exposure(int topY, int visibleMinY, boolean eligible) {}

    public static Exposure exposureAt(int x, int z, Heights heights) {
        int top = heights.heightAt(x, z);
        if (top < 50) return new Exposure(top, 49, false);
        int near = minimum(heights, x, z, 1);
        if (top - near < 1) return new Exposure(top, top + 1, false);
        int middle = minimum(heights, x, z, 4);
        int broad = minimum(heights, x, z, 8);
        boolean cliff = top - middle >= 5 && top - broad >= 10;
        return new Exposure(top, Math.max(49, near + 1), cliff);
    }

    private static int minimum(Heights heights, int x, int z, int radius) {
        return Math.min(Math.min(heights.heightAt(x - radius, z), heights.heightAt(x + radius, z)),
                Math.min(heights.heightAt(x, z - radius), heights.heightAt(x, z + radius)));
    }

    /** NONE preserves the biome's three-dimensional geology, including every cave palette. */
    public Material materialAt(String biome, int x, int y, int z, Exposure exposure) {
        if (!exposure.eligible || y < exposure.visibleMinY || y > exposure.topY || y < 49
                || !("cwi:forsaken_land".equals(biome) || "cwi:smoldering_barren".equals(biome))) return Material.NONE;
        // Broad smoothly warped phase and thickness produce tilted unequal bands without region seams.
        double warp = (ReferenceWeathering.sample(seed ^ WARP, x, z) - .5) * 48.;
        double thickness = 8. + ReferenceWeathering.sample(seed ^ THICKNESS, x / 3, z / 3) * 10.;
        long band = (long) Math.floor((y + warp) / thickness);
        double choice = (mix(seed ^ band * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
        // Eroded interruptions are entire coherent wall areas, never isolated noisy pixels.
        if (ReferenceWeathering.sample(seed ^ BREAKS, x, z) > .75) return Material.STONE;
        if (choice < .29) return Material.TUFF;
        if (choice < ("cwi:smoldering_barren".equals(biome) ? .43 : .38)) return Material.ANDESITE;
        return Material.STONE;
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }
}

package net.quiwh.cwiworldgen.terrain;

/** Phase-aligned materials only on an admitted shelf's already visible cliff shell. */
public final class ShelfCliffMaterials {
    private ShelfCliffMaterials() {}

    public static CliffBands.Material materialAt(String biome, int y, CliffBands.Exposure exposure,
                                                 RockShelfRelief.Sample shelf) {
        if (shelf == null || !Float.isFinite(shelf.acceptedHeight()) || !exposure.eligible() || y < 49 || y < exposure.visibleMinY()
                || y > exposure.topY() || shelf.outlineSupport() < .75 || shelf.winnerMargin() < .4
                || !("cwi:forsaken_land".equals(biome) || "cwi:smoldering_barren".equals(biome)))
            return CliffBands.Material.NONE;
        long bed = (long) Math.floor(shelf.bedCoordinate(y));
        double choice = (mix(shelf.districtId() ^ bed * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
        // Existing surface-cliff families only. Biome/cave mass palettes remain untouched.
        if (choice < .29) return CliffBands.Material.TUFF;
        if (choice < ("cwi:smoldering_barren".equals(biome) ? .43 : .38)) return CliffBands.Material.ANDESITE;
        return CliffBands.Material.STONE;
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }
}

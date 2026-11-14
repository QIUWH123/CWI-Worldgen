package net.quiwh.cwiworldgen.terrain;

/** Versioned diffusion into the supplied engine's 32-bit seed interface. */
public final class WorldSeedPolicy {
    private WorldSeedPolicy() {}

    public static int engineSeed(long worldSeed, int generationVersion) {
        // Classic terrain uses the original engine geography for every long
        // seed, including current fields reconstructed outside chunk generation.
        if (generationVersion < 16 || BaseTerrainProfile.usesClassicTerrain(generationVersion)) return (int) worldSeed;
        int low = (int) worldSeed;
        // Canonical signed-32-bit seeds keep their original geography. For each
        // fixed low half, the upper half is mapped bijectively into engine seeds.
        int upperDifference = (int) (worldSeed >>> 32) ^ (low >> 31);
        upperDifference ^= upperDifference >>> 16;
        upperDifference *= 0x85EBCA6B;
        upperDifference ^= upperDifference >>> 13;
        upperDifference *= 0xC2B2AE35;
        upperDifference ^= upperDifference >>> 16;
        return low ^ upperDifference;
    }
}

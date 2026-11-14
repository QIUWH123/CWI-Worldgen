package net.quiwh.cwiworldgen.placement;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/** Pure, world-coordinate groups whose candidate ownership is independent of their source group. */
public final class SceneryClusters {
    public record Point(int x, int z) {}

    private SceneryClusters() {}

    public static List<Point> candidates(long seed, long salt, int chunkX, int chunkZ, double chance,
            int minGroups, int maxGroups, int minMembers, int maxMembers, int radius) {
        // Groups can straddle a border. Reconstruct neighboring sources without reading neighboring chunks.
        var result = new LinkedHashSet<Point>();
        int reach = (radius + 15) / 16;
        for (int sourceX = chunkX - reach; sourceX <= chunkX + reach; sourceX++) {
            for (int sourceZ = chunkZ - reach; sourceZ <= chunkZ + reach; sourceZ++) {
                double richness = richness(seed, sourceX * 16 + 8, sourceZ * 16 + 8);
                Random random = new Random(mix(seed ^ salt ^ mix(sourceX * 0x632be59bd9b4e019L)
                        ^ mix(sourceZ * 0x9e3779b97f4a7c15L)));
                if (richness <= 0 || random.nextDouble() >= chance * (.45 + .55 * richness)) continue;
                int groups = minGroups + (int) Math.floor(random.nextDouble()
                        * (maxGroups - minGroups + 1) * (.45 + .55 * richness));
                for (int group = 0; group < groups; group++) {
                    int centerX = sourceX * 16 + random.nextInt(16);
                    int centerZ = sourceZ * 16 + random.nextInt(16);
                    int members = minMembers + random.nextInt(maxMembers - minMembers + 1);
                    double rotation = random.nextDouble() * Math.PI * 2;
                    double elongation = .65 + random.nextDouble() * .35;
                    for (int member = 0; member < members; member++) {
                        double angle = random.nextDouble() * Math.PI * 2;
                        double distance = Math.sqrt(random.nextDouble()) * radius;
                        double u = Math.cos(angle) * distance;
                        double v = Math.sin(angle) * distance * elongation;
                        int x = centerX + (int) Math.round(u * Math.cos(rotation) - v * Math.sin(rotation));
                        int z = centerZ + (int) Math.round(u * Math.sin(rotation) + v * Math.cos(rotation));
                        if (Math.floorDiv(x, 16) == chunkX && Math.floorDiv(z, 16) == chunkZ
                                && richness(seed, x, z) > 0) result.add(new Point(x, z));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /** Profile 10 uses shorter groves plus independently salted isolated trees in quiet districts. */
    public static List<Point> candidates(long seed, long salt, int chunkX, int chunkZ, double chance,
            int minGroups, int maxGroups, int minMembers, int maxMembers, int radius,
            int generationVersion, boolean scattered) {
        if (generationVersion < 10) return candidates(seed, salt, chunkX, chunkZ, chance,
                minGroups, maxGroups, minMembers, maxMembers, radius);
        var result = new LinkedHashSet<Point>();
        int reach = (radius + 15) / 16;
        for (int sourceX = chunkX - reach; sourceX <= chunkX + reach; sourceX++) {
            for (int sourceZ = chunkZ - reach; sourceZ <= chunkZ + reach; sourceZ++) {
                double richness = groveRichness(seed, sourceX * 16 + 8, sourceZ * 16 + 8);
                Random random = new Random(mix(seed ^ salt ^ mix(sourceX * 0x632be59bd9b4e019L)
                        ^ mix(sourceZ * 0x9e3779b97f4a7c15L)));
                if (richness <= 0 || random.nextDouble() >= chance * (.35 + .65 * richness)) continue;
                int groups = minGroups + (int) Math.floor(random.nextDouble()
                        * (maxGroups - minGroups + 1) * (.45 + .55 * richness));
                for (int group = 0; group < groups; group++) {
                    int centerX = sourceX * 16 + random.nextInt(16);
                    int centerZ = sourceZ * 16 + random.nextInt(16);
                    int members = minMembers + random.nextInt(maxMembers - minMembers + 1);
                    double rotation = random.nextDouble() * Math.PI * 2;
                    double elongation = .65 + random.nextDouble() * .35;
                    for (int member = 0; member < members; member++) {
                        double angle = random.nextDouble() * Math.PI * 2;
                        double distance = Math.sqrt(random.nextDouble()) * radius;
                        double u = Math.cos(angle) * distance, v = Math.sin(angle) * distance * elongation;
                        int x = centerX + (int) Math.round(u * Math.cos(rotation) - v * Math.sin(rotation));
                        int z = centerZ + (int) Math.round(u * Math.sin(rotation) + v * Math.cos(rotation));
                        if (Math.floorDiv(x, 16) == chunkX && Math.floorDiv(z, 16) == chunkZ
                                && groveRichness(seed, x, z) > 0) result.add(new Point(x, z));
                    }
                }
            }
        }
        if (scattered) {
            Random isolated = new Random(mix(seed ^ salt ^ 0x49534f4c41544544L
                    ^ mix(chunkX * 0x632be59bd9b4e019L) ^ mix(chunkZ * 0x9e3779b97f4a7c15L)));
            // A clearing can contain a lone trunk without becoming a uniformly filled grove.
            if (isolated.nextDouble() < chance * .33)
                result.add(new Point(chunkX * 16 + isolated.nextInt(16), chunkZ * 16 + isolated.nextInt(16)));
        }
        return List.copyOf(result);
    }

    public static double groveRichness(long seed, int x, int z) {
        double field = .65 * noise(seed ^ 0x47524f564553L, x, z, 96)
                + .35 * noise(seed ^ 0x554e444552574f4fL, x, z, 32);
        return Math.max(0, Math.min(1, (field - .22) / .56));
    }

    /** Same established broad clearings, sampled at the actual member for the new placement path. */
    public static double richness(long seed, int x, int z) {
        double field = .72 * noise(seed ^ 0x4357495343454e45L, x, z, 192)
                + .28 * noise(seed ^ 0x52454c494546L, x, z, 64);
        return Math.max(0, Math.min(1, (field - .35) / .5));
    }

    private static double noise(long seed, int x, int z, int size) {
        int cx = Math.floorDiv(x, size), cz = Math.floorDiv(z, size);
        double u = Math.floorMod(x, size) / (double) size, v = Math.floorMod(z, size) / (double) size;
        u = u * u * (3 - 2 * u); v = v * v * (3 - 2 * v);
        double a = unit(seed ^ mix(cx * 0x632be59bd9b4e019L) ^ mix(cz * 0x9e3779b97f4a7c15L));
        double b = unit(seed ^ mix((cx + 1L) * 0x632be59bd9b4e019L) ^ mix(cz * 0x9e3779b97f4a7c15L));
        double c = unit(seed ^ mix(cx * 0x632be59bd9b4e019L) ^ mix((cz + 1L) * 0x9e3779b97f4a7c15L));
        double d = unit(seed ^ mix((cx + 1L) * 0x632be59bd9b4e019L) ^ mix((cz + 1L) * 0x9e3779b97f4a7c15L));
        return (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v;
    }

    private static double unit(long seed) { return (mix(seed) >>> 11) * 0x1.0p-53; }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
